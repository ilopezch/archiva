package org.apache.archiva.npm.repository.consumer;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.archiva.consumers.AbstractMonitoredConsumer;
import org.apache.archiva.consumers.ConsumerException;
import org.apache.archiva.consumers.KnownRepositoryContentConsumer;
import org.apache.archiva.metadata.model.ArtifactMetadata;
import org.apache.archiva.metadata.model.ProjectMetadata;
import org.apache.archiva.metadata.model.ProjectVersionMetadata;
import org.apache.archiva.metadata.repository.MetadataRepository;
import org.apache.archiva.metadata.repository.MetadataRepositoryException;
import org.apache.archiva.metadata.repository.MetadataSessionException;
import org.apache.archiva.metadata.repository.RepositorySession;
import org.apache.archiva.metadata.repository.RepositorySessionFactory;
import org.apache.archiva.repository.ManagedRepository;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Scans NPM tarballs ({@code {name}/-/{name}-{version}.tgz}), extracts
 * {@code package/package.json} from inside the archive, and stores artifact,
 * project-version, and project metadata in the Archiva metadata repository.
 *
 * <p>ItemSelector field mapping used in the metadata store:
 * <ul>
 *   <li>namespace → scope ({@code @myorg}) or empty string for unscoped packages</li>
 *   <li>project   → package name</li>
 *   <li>version   → semver version string</li>
 * </ul>
 */
@Service( "knownRepositoryContentConsumer#npm-metadata" )
@Scope( "prototype" )
public class NpmMetadataConsumer
    extends AbstractMonitoredConsumer
    implements KnownRepositoryContentConsumer
{
    private static final String ID = "npm-metadata";
    private static final String DESCRIPTION = "Indexes npm package metadata from tarball archives";

    private static final List<String> INCLUDES = Collections.singletonList( "**/-/*.tgz" );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    private RepositorySessionFactory repositorySessionFactory;

    private String repoId;
    private Path repoDir;
    private ZonedDateTime whenGathered;

    @Override
    public String getId()
    {
        return ID;
    }

    @Override
    public String getDescription()
    {
        return DESCRIPTION;
    }

    @Override
    public List<String> getIncludes()
    {
        return INCLUDES;
    }

    @Override
    public List<String> getExcludes()
    {
        return null;
    }

    @Override
    public void beginScan( ManagedRepository repository, Date whenGathered ) throws ConsumerException
    {
        repoId = repository.getId();
        repoDir = repository.getRoot().getFilePath();
        this.whenGathered = ZonedDateTime.ofInstant( whenGathered.toInstant(), ZoneId.of( "UTC" ) );
    }

    @Override
    public void beginScan( ManagedRepository repository, Date whenGathered, boolean executeOnEntireRepo )
        throws ConsumerException
    {
        beginScan( repository, whenGathered );
    }

    @Override
    public void processFile( String path ) throws ConsumerException
    {
        NpmCoords coords = parseCoords( path );
        if ( coords == null )
        {
            logger.debug( "Skipping non-NPM path: {}", path );
            return;
        }

        Path tarball = repoDir.resolve( path );
        if ( !Files.isRegularFile( tarball ) )
        {
            return;
        }

        JsonNode pkg = null;
        try
        {
            String json = extractPackageJson( tarball );
            if ( json != null )
            {
                pkg = MAPPER.readTree( json );
            }
        }
        catch ( IOException e )
        {
            logger.warn( "Could not extract package.json from {}: {}", path, e.getMessage() );
        }

        RepositorySession session = null;
        try
        {
            session = repositorySessionFactory.createSession();
        }
        catch ( MetadataRepositoryException e )
        {
            throw new ConsumerException( "Cannot open metadata session: " + e.getMessage(), e );
        }
        try
        {
            MetadataRepository meta = session.getRepository();

            ProjectMetadata project = new ProjectMetadata();
            project.setNamespace( coords.namespace );
            project.setId( coords.name );

            ProjectVersionMetadata versionMeta = buildVersionMetadata( coords, pkg );

            ArtifactMetadata artifact = buildArtifactMetadata( coords, path, tarball );

            meta.updateArtifact( session, repoId, coords.namespace, coords.name, coords.version, artifact );
            meta.updateProjectVersion( session, repoId, coords.namespace, coords.name, versionMeta );
            meta.updateProject( session, repoId, project );
            session.save();
        }
        catch ( MetadataRepositoryException e )
        {
            logger.warn( "Error persisting metadata for {}: {}", path, e.getMessage(), e );
            try
            {
                session.revert();
            }
            catch ( MetadataSessionException ex )
            {
                logger.error( "Session revert failed: {}", ex.getMessage() );
            }
        }
        catch ( MetadataSessionException e )
        {
            throw new ConsumerException( e.getMessage(), e );
        }
        finally
        {
            session.close();
        }
    }

    @Override
    public void processFile( String path, boolean executeOnEntireRepo ) throws ConsumerException
    {
        processFile( path );
    }

    @Override
    public void completeScan()
    {
        /* no-op */
    }

    @Override
    public void completeScan( boolean executeOnEntireRepo )
    {
        completeScan();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Parses an NPM tarball path into coordinates.
     * Accepted forms:
     * <ul>
     *   <li>{@code name/-/name-version.tgz}</li>
     *   <li>{@code @scope/name/-/name-version.tgz}</li>
     * </ul>
     */
    private NpmCoords parseCoords( String path )
    {
        String[] parts = path.split( "/" );
        String scope = "";
        String name;
        String filename;

        if ( parts[0].startsWith( "@" ) )
        {
            if ( parts.length != 4 || !"-".equals( parts[2] ) )
            {
                return null;
            }
            scope = parts[0];
            name = parts[1];
            filename = parts[3];
        }
        else
        {
            if ( parts.length != 3 || !"-".equals( parts[1] ) )
            {
                return null;
            }
            name = parts[0];
            filename = parts[2];
        }

        if ( !filename.endsWith( ".tgz" ) )
        {
            return null;
        }
        String withoutExt = filename.substring( 0, filename.length() - 4 );
        String prefix = name + "-";
        if ( !withoutExt.startsWith( prefix ) )
        {
            return null;
        }
        String version = withoutExt.substring( prefix.length() );
        return new NpmCoords( scope, name, version, filename );
    }

    private ProjectVersionMetadata buildVersionMetadata( NpmCoords coords, JsonNode pkg )
    {
        ProjectVersionMetadata meta = new ProjectVersionMetadata();
        meta.setId( coords.version );
        if ( pkg != null )
        {
            meta.setName( textOrNull( pkg, "name" ) );
            meta.setDescription( textOrNull( pkg, "description" ) );
            meta.setUrl( textOrNull( pkg, "homepage" ) );
        }
        return meta;
    }

    private ArtifactMetadata buildArtifactMetadata( NpmCoords coords, String path, Path tarball )
    {
        ArtifactMetadata artifact = new ArtifactMetadata();
        artifact.setId( coords.filename );
        artifact.setRepositoryId( repoId );
        artifact.setNamespace( coords.namespace );
        artifact.setProject( coords.name );
        artifact.setProjectVersion( coords.version );
        artifact.setVersion( coords.version );
        artifact.setWhenGathered( whenGathered );
        try
        {
            artifact.setFileLastModified( Files.getLastModifiedTime( tarball ).toMillis() );
            artifact.setSize( Files.size( tarball ) );
        }
        catch ( IOException e )
        {
            logger.debug( "Could not read file attributes for {}: {}", path, e.getMessage() );
        }
        return artifact;
    }

    private String textOrNull( JsonNode node, String field )
    {
        JsonNode child = node.get( field );
        return ( child != null && child.isTextual() ) ? child.asText() : null;
    }

    /**
     * Reads a {@code .tgz} archive and returns the content of the first entry whose
     * name equals {@code package/package.json} (the canonical location for npm packages).
     * Returns {@code null} if the entry is not found or is larger than 1 MB.
     *
     * <p>This is a minimal, zero-dependency tar reader that handles only the standard
     * POSIX/GNU tar header format used by all npm tarballs.
     */
    static String extractPackageJson( Path tarball ) throws IOException
    {
        try ( GZIPInputStream gz = new GZIPInputStream( Files.newInputStream( tarball ) ) )
        {
            byte[] header = new byte[512];
            while ( readFully( gz, header ) == 512 )
            {
                if ( isZeroBlock( header ) )
                {
                    break;
                }
                String name = parseTarName( header );
                long size = parseTarSize( header );
                char typeflag = (char) ( header[156] & 0xFF );
                boolean isRegular = typeflag == '0' || typeflag == 0 || typeflag == ' ';

                if ( isRegular && "package/package.json".equals( name ) && size > 0 && size <= 1_048_576 )
                {
                    byte[] data = new byte[(int) size];
                    readFully( gz, data );
                    return new String( data, StandardCharsets.UTF_8 );
                }
                // Skip to next 512-byte boundary
                long paddedSize = ( ( size + 511L ) / 512L ) * 512L;
                readAndDiscard( gz, paddedSize );
            }
        }
        return null;
    }

    private static int readFully( InputStream in, byte[] buf ) throws IOException
    {
        int total = 0;
        while ( total < buf.length )
        {
            int n = in.read( buf, total, buf.length - total );
            if ( n < 0 )
            {
                return total;
            }
            total += n;
        }
        return total;
    }

    private static void readAndDiscard( InputStream in, long bytes ) throws IOException
    {
        byte[] buf = new byte[4096];
        long remaining = bytes;
        while ( remaining > 0 )
        {
            int toRead = (int) Math.min( remaining, buf.length );
            int n = in.read( buf, 0, toRead );
            if ( n < 0 )
            {
                break;
            }
            remaining -= n;
        }
    }

    private static boolean isZeroBlock( byte[] block )
    {
        for ( byte b : block )
        {
            if ( b != 0 )
            {
                return false;
            }
        }
        return true;
    }

    private static String parseTarName( byte[] header )
    {
        int end = 0;
        while ( end < 100 && header[end] != 0 )
        {
            end++;
        }
        return new String( header, 0, end, StandardCharsets.UTF_8);
    }

    private static long parseTarSize( byte[] header )
    {
        // Bytes 124-135: file size as null/space-terminated octal ASCII string
        int start = 124;
        int end = start;
        while ( end < 136 && header[end] != 0 && header[end] != ' ' )
        {
            end++;
        }
        String octal = new String( header, start, end - start, StandardCharsets.US_ASCII ).trim();
        if ( octal.isEmpty() )
        {
            return 0L;
        }
        try
        {
            return Long.parseLong( octal, 8 );
        }
        catch ( NumberFormatException e )
        {
            return 0L;
        }
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private static final class NpmCoords
    {
        final String namespace;
        final String name;
        final String version;
        final String filename;

        NpmCoords( String namespace, String name, String version, String filename )
        {
            this.namespace = namespace;
            this.name = name;
            this.version = version;
            this.filename = filename;
        }
    }
}
