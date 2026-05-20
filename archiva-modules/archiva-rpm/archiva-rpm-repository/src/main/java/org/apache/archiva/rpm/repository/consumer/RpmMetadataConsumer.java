package org.apache.archiva.rpm.repository.consumer;

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
import org.apache.archiva.rpm.repository.repodata.RepomdGenerator;
import org.apache.archiva.rpm.repository.repodata.RpmHeaderParser;
import org.apache.archiva.rpm.repository.repodata.RpmPackageInfo;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Scans {@code .rpm} files under {@code RPMS/} and {@code SRPMS/} directories,
 * parses the binary RPM header, and stores artifact, project-version, and project
 * metadata in the Archiva metadata repository.
 *
 * <p>Field mapping in the metadata store:
 * <ul>
 *   <li>namespace → package architecture (e.g. {@code x86_64}, {@code noarch}, {@code src})</li>
 *   <li>project   → package name</li>
 *   <li>version   → {@code version-release} EVR string</li>
 * </ul>
 *
 * <p>After every scan pass {@link #completeScan()} regenerates {@code repodata/} so
 * yum/dnf clients see an up-to-date repository index.  Individual PUT uploads trigger
 * an immediate rebuild via {@code RpmRegistryServlet}, so scan-initiated rebuilds only
 * happen when the scan infrastructure invokes this consumer (e.g. scheduled scans or
 * manual index regeneration) — there is no double-rebuild for single-package uploads.
 */
@Service( "knownRepositoryContentConsumer#rpm-metadata" )
@Scope( "prototype" )
public class RpmMetadataConsumer
    extends AbstractMonitoredConsumer
    implements KnownRepositoryContentConsumer
{
    private static final String ID = "rpm-metadata";
    private static final String DESCRIPTION = "Indexes RPM package metadata from .rpm binary headers";
    private static final List<String> INCLUDES =
        Collections.unmodifiableList( Arrays.asList( "RPMS/**/*.rpm", "SRPMS/**/*.rpm" ) );

    @Inject
    private RepositorySessionFactory repositorySessionFactory;

    private String repoId;
    private Path repoDir;
    private ZonedDateTime whenGathered;
    private final RepomdGenerator repomdGenerator = new RepomdGenerator();

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
        Path rpmFile = repoDir.resolve( path );
        if ( !Files.isRegularFile( rpmFile ) )
        {
            return;
        }

        RpmPackageInfo info;
        try ( InputStream in = Files.newInputStream( rpmFile ) )
        {
            info = RpmHeaderParser.parseHeader( in );
        }
        catch ( IOException e )
        {
            logger.warn( "Could not parse RPM header for {}: {}", path, e.getMessage() );
            return;
        }

        if ( info.name == null || info.version == null || info.release == null || info.arch == null )
        {
            logger.warn( "Incomplete RPM header in {} — skipping", path );
            return;
        }

        String namespace = info.arch;
        String project   = info.name;
        String version   = info.version + "-" + info.release;
        String filename  = rpmFile.getFileName().toString();

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

            meta.updateNamespace( session, repoId, namespace );

            ProjectMetadata projectMeta = new ProjectMetadata();
            projectMeta.setNamespace( namespace );
            projectMeta.setId( project );

            ProjectVersionMetadata versionMeta = buildVersionMetadata( version, info );

            ArtifactMetadata artifact = buildArtifactMetadata(
                filename, namespace, project, version, path, rpmFile );

            meta.updateArtifact( session, repoId, namespace, project, version, artifact );
            meta.updateProjectVersion( session, repoId, namespace, project, versionMeta );
            meta.updateProject( session, repoId, projectMeta );
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

    /**
     * Rebuilds {@code repodata/} once after the full scan completes.  Calling rebuild
     * here rather than inside {@link #processFile(String)} ensures at most one rebuild
     * per scan pass regardless of how many packages were processed.
     */
    @Override
    public void completeScan()
    {
        if ( repoDir != null )
        {
            try
            {
                repomdGenerator.rebuild( repoDir );
            }
            catch ( IOException e )
            {
                logger.error( "Failed to rebuild repodata after scan of {}: {}",
                    repoId, e.getMessage(), e );
            }
        }
    }

    @Override
    public void completeScan( boolean executeOnEntireRepo )
    {
        completeScan();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private ProjectVersionMetadata buildVersionMetadata( String version, RpmPackageInfo info )
    {
        ProjectVersionMetadata meta = new ProjectVersionMetadata();
        meta.setId( version );
        meta.setName( info.name );
        meta.setDescription( info.description );
        meta.setUrl( info.url );
        return meta;
    }

    private ArtifactMetadata buildArtifactMetadata( String filename, String namespace,
                                                    String project, String version,
                                                    String path, Path rpmFile )
    {
        ArtifactMetadata artifact = new ArtifactMetadata();
        artifact.setId( filename );
        artifact.setRepositoryId( repoId );
        artifact.setNamespace( namespace );
        artifact.setProject( project );
        artifact.setProjectVersion( version );
        artifact.setVersion( version );
        artifact.setWhenGathered( whenGathered );
        try
        {
            artifact.setFileLastModified( Files.getLastModifiedTime( rpmFile ).toMillis() );
            artifact.setSize( Files.size( rpmFile ) );
        }
        catch ( IOException e )
        {
            logger.debug( "Could not read file attributes for {}: {}", path, e.getMessage() );
        }
        return artifact;
    }
}
