package org.apache.archiva.rpm.repository.repodata;

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

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRing;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Generates the full {@code repodata/} directory structure for an RPM repository:
 * <ol>
 *   <li>Scans all RPM files under {@code RPMS/} and {@code SRPMS/} (with incremental caching)</li>
 *   <li>Parses each RPM's binary header</li>
 *   <li>Writes {@code repodata/{checksum}-primary.xml.gz}</li>
 *   <li>Writes {@code repodata/{checksum}-filelists.xml.gz} (full paths via DIRNAMES/DIRINDEXES)</li>
 *   <li>Writes {@code repodata/{checksum}-other.xml.gz} (with changelog entries)</li>
 *   <li>Optionally writes {@code repodata/{checksum}-prestodelta.xml.gz} when delta RPMs are present</li>
 *   <li>Optionally writes {@code repodata/{checksum}-modules.yaml.gz} when modulemd files are present</li>
 *   <li>Writes {@code repodata/repomd.xml}</li>
 *   <li>Signs {@code repomd.xml} as {@code repodata/repomd.xml.asc} (armored detached)</li>
 *   <li>Exports the public key to {@code repokey.gpg}</li>
 * </ol>
 *
 * <p>The GPG key is stored in {@code .repodata/signing.pgp} under the repository root.
 * An incremental parse cache is stored in {@code .repodata/cache.json}; unchanged RPMs
 * (same filename + mtime) are served from cache to avoid redundant header parsing and
 * SHA-256 computation on every rebuild.
 *
 * <p>This class is intentionally stateless; callers obtain it via {@code new RepomdGenerator()}.
 */
public class RepomdGenerator
{
    private static final Logger log = LoggerFactory.getLogger( RepomdGenerator.class );

    private static final String REPODATA_DIR    = "repodata";
    private static final String KEYSTORE_DIR    = ".repodata";
    private static final String SIGNING_KEY     = "signing.pgp";
    private static final String CACHE_FILE      = "cache.json";
    private static final String PUBLIC_KEY_FILE = "repokey.gpg";
    private static final String REPOMD_XML      = "repomd.xml";
    private static final String REPOMD_SIG      = "repomd.xml.asc";

    static
    {
        if ( Security.getProvider( BouncyCastleProvider.PROVIDER_NAME ) == null )
        {
            Security.addProvider( new BouncyCastleProvider() );
        }
    }

    /**
     * Rebuilds the full repodata directory for the repository rooted at {@code repoRoot}.
     *
     * @param repoRoot absolute path to the managed repository root
     * @throws IOException on any I/O failure
     */
    public void rebuild( Path repoRoot ) throws IOException
    {
        rebuildFromRoots( repoRoot, List.of( repoRoot ) );
    }

    /**
     * Generates merged repodata for a repository group by scanning all member repository roots
     * and writing the combined metadata to {@code groupRoot/repodata/}.
     *
     * @param groupRoot   absolute path to the group's storage root (receives merged repodata)
     * @param memberRoots absolute paths to each member managed repository root
     * @throws IOException on any I/O failure
     */
    public void rebuildMerged( Path groupRoot, List<Path> memberRoots ) throws IOException
    {
        log.info( "Rebuilding merged repodata for group at {} from {} members", groupRoot, memberRoots.size() );
        rebuildFromRoots( groupRoot, memberRoots );
    }

    private void rebuildFromRoots( Path targetRoot, List<Path> sourceRoots ) throws IOException
    {
        log.info( "Rebuilding repodata at {} from {} source root(s)", targetRoot, sourceRoots.size() );

        // 1. Ensure keystore/cache directory exists
        Path keystoreDir = targetRoot.resolve( KEYSTORE_DIR );
        Files.createDirectories( keystoreDir );

        // 2. Load incremental parse cache
        Path cacheFile = keystoreDir.resolve( CACHE_FILE );
        RpmRepodataCache cache = RpmRepodataCache.load( cacheFile );

        // 3. Collect all RPM packages from all source roots (using cache for unchanged files)
        List<RpmPackageInfo> packages = new ArrayList<>();
        for ( Path root : sourceRoots )
        {
            packages.addAll( RpmScanner.scan( root, cache ) );
        }
        log.debug( "Found {} RPM packages total", packages.size() );

        // 4. Persist updated cache
        cache.save( cacheFile );

        // 5. Write metadata XML files
        Path repodataDir = targetRoot.resolve( REPODATA_DIR );
        Files.createDirectories( repodataDir );

        MetadataFile primary   = writePrimary(   repodataDir, packages );
        MetadataFile filelists = writeFilelists( repodataDir, packages );
        MetadataFile other     = writeOther(     repodataDir, packages );
        MetadataFile deltarpm  = writeDeltaRpm(  repodataDir, sourceRoots, packages );
        MetadataFile modules   = writeModules(   repodataDir, sourceRoots );

        // 6. Write repomd.xml
        byte[] repomdBytes = buildRepomdXml( primary, filelists, other, deltarpm, modules );
        Path repomdPath = repodataDir.resolve( REPOMD_XML );
        Files.write( repomdPath, repomdBytes );

        // 7. Sign repomd.xml with the target root's key (group or single repo key)
        try
        {
            PGPSecretKey signingKey = getOrCreateSigningKey( targetRoot );
            byte[] sig = sign( repomdBytes, signingKey );
            Files.write( repodataDir.resolve( REPOMD_SIG ), sig );
            exportPublicKey( targetRoot, signingKey );
        }
        catch ( PGPException e )
        {
            log.error( "GPG signing failed for repomd.xml: {}", e.getMessage(), e );
            throw new IOException( "GPG signing failed", e );
        }

        log.info( "repodata rebuild complete at {}", targetRoot );
    }

    // -------------------------------------------------------------------------
    // primary.xml.gz
    // -------------------------------------------------------------------------

    private MetadataFile writePrimary( Path repodataDir, List<RpmPackageInfo> packages )
        throws IOException
    {
        byte[] xml = PrimaryXmlBuilder.build( packages );
        return writeCompressed( repodataDir, "primary.xml", xml );
    }

    // -------------------------------------------------------------------------
    // filelists.xml.gz — full absolute paths via DIRNAMES + DIRINDEXES
    // -------------------------------------------------------------------------

    private MetadataFile writeFilelists( Path repodataDir, List<RpmPackageInfo> packages )
        throws IOException
    {
        StringBuilder sb = new StringBuilder();
        sb.append( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" );
        sb.append( "<filelists xmlns=\"http://linux.duke.edu/metadata/filelists\"" );
        sb.append( " packages=\"" ).append( packages.size() ).append( "\">\n" );

        for ( RpmPackageInfo p : packages )
        {
            sb.append( "  <package pkgid=\"" ).append( esc( p.pkgId() ) )
              .append( "\" name=\"" ).append( esc( p.name ) )
              .append( "\" arch=\"" ).append( esc( p.arch ) ).append( "\">\n" );
            sb.append( "    <version epoch=\"" ).append( nvl( p.epoch ) )
              .append( "\" ver=\"" ).append( esc( p.version ) )
              .append( "\" rel=\"" ).append( esc( p.release ) ).append( "\"/>\n" );

            if ( !p.dirNames.isEmpty() && !p.files.isEmpty() )
            {
                // Reconstruct absolute paths using DIRNAMES + DIRINDEXES
                for ( int i = 0; i < p.files.size(); i++ )
                {
                    String basename = p.files.get( i );
                    int    dirIdx   = ( i < p.dirIndexes.size() ) ? p.dirIndexes.get( i ) : 0;
                    String dir      = ( dirIdx < p.dirNames.size() ) ? p.dirNames.get( dirIdx ) : "";

                    if ( basename.isEmpty() )
                    {
                        // An empty basename signals a directory entry
                        sb.append( "    <file type=\"dir\">" ).append( esc( dir ) ).append( "</file>\n" );
                    }
                    else
                    {
                        sb.append( "    <file>" ).append( esc( dir + basename ) ).append( "</file>\n" );
                    }
                }
            }
            else
            {
                // Fallback: emit basenames only (no dir info available)
                for ( String f : p.files )
                {
                    sb.append( "    <file>" ).append( esc( f ) ).append( "</file>\n" );
                }
            }

            sb.append( "  </package>\n" );
        }
        sb.append( "</filelists>\n" );
        return writeCompressed( repodataDir, "filelists.xml",
            sb.toString().getBytes( StandardCharsets.UTF_8 ) );
    }

    // -------------------------------------------------------------------------
    // other.xml.gz — with changelog entries
    // -------------------------------------------------------------------------

    private MetadataFile writeOther( Path repodataDir, List<RpmPackageInfo> packages )
        throws IOException
    {
        StringBuilder sb = new StringBuilder();
        sb.append( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" );
        sb.append( "<otherdata xmlns=\"http://linux.duke.edu/metadata/other\"" );
        sb.append( " packages=\"" ).append( packages.size() ).append( "\">\n" );

        for ( RpmPackageInfo p : packages )
        {
            sb.append( "  <package pkgid=\"" ).append( esc( p.pkgId() ) )
              .append( "\" name=\"" ).append( esc( p.name ) )
              .append( "\" arch=\"" ).append( esc( p.arch ) ).append( "\">\n" );
            sb.append( "    <version epoch=\"" ).append( nvl( p.epoch ) )
              .append( "\" ver=\"" ).append( esc( p.version ) )
              .append( "\" rel=\"" ).append( esc( p.release ) ).append( "\"/>\n" );

            int count = Math.min( p.changelogNames.size(),
                        Math.min( p.changelogTimes.size(), p.changelogTexts.size() ) );
            for ( int i = 0; i < count; i++ )
            {
                sb.append( "    <changelog author=\"" ).append( esc( p.changelogNames.get( i ) ) )
                  .append( "\" date=\"" ).append( p.changelogTimes.get( i ) ).append( "\">" )
                  .append( esc( p.changelogTexts.get( i ) ) )
                  .append( "</changelog>\n" );
            }

            sb.append( "  </package>\n" );
        }
        sb.append( "</otherdata>\n" );
        return writeCompressed( repodataDir, "other.xml",
            sb.toString().getBytes( StandardCharsets.UTF_8 ) );
    }

    // -------------------------------------------------------------------------
    // prestodelta.xml.gz — delta RPM metadata
    // -------------------------------------------------------------------------

    private MetadataFile writeDeltaRpm( Path repodataDir, List<Path> sourceRoots,
                                         List<RpmPackageInfo> packages ) throws IOException
    {
        // Build a name→package lookup to resolve epoch for new packages
        Map<String, RpmPackageInfo> pkgByNameArch = new LinkedHashMap<>();
        for ( RpmPackageInfo p : packages )
        {
            pkgByNameArch.put( p.name + "." + p.arch, p );
        }

        List<DeltaEntry> deltas = new ArrayList<>();
        for ( Path root : sourceRoots )
        {
            Path drpmsDir = root.resolve( "drpms" );
            if ( !Files.isDirectory( drpmsDir ) ) continue;
            Files.walkFileTree( drpmsDir, new SimpleFileVisitor<Path>()
            {
                @Override
                public FileVisitResult visitFile( Path file, BasicFileAttributes attrs )
                {
                    if ( file.getFileName().toString().endsWith( ".drpm" ) )
                    {
                        DeltaEntry d = parseDrpmFilename( root, file, pkgByNameArch );
                        if ( d != null ) deltas.add( d );
                    }
                    return FileVisitResult.CONTINUE;
                }
            } );
        }

        if ( deltas.isEmpty() ) return null;

        // Group by new package key (name + epoch + version + release + arch)
        Map<String, List<DeltaEntry>> byNewPkg = new LinkedHashMap<>();
        for ( DeltaEntry d : deltas )
        {
            byNewPkg.computeIfAbsent( d.newPkgKey(), k -> new ArrayList<>() ).add( d );
        }

        StringBuilder sb = new StringBuilder();
        sb.append( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" );
        sb.append( "<prestodelta xmlns=\"http://linux.duke.edu/metadata/delta\">\n" );

        for ( List<DeltaEntry> pkgDeltas : byNewPkg.values() )
        {
            DeltaEntry first = pkgDeltas.get( 0 );
            sb.append( "  <newpackage name=\"" ).append( esc( first.name ) )
              .append( "\" epoch=\"" ).append( nvl( first.newEpoch ) )
              .append( "\" version=\"" ).append( esc( first.newVersion ) )
              .append( "\" release=\"" ).append( esc( first.newRelease ) )
              .append( "\" arch=\"" ).append( esc( first.arch ) ).append( "\">\n" );

            for ( DeltaEntry d : pkgDeltas )
            {
                sb.append( "    <delta oldepoch=\"" ).append( nvl( d.oldEpoch ) )
                  .append( "\" oldversion=\"" ).append( esc( d.oldVersion ) )
                  .append( "\" oldrelease=\"" ).append( esc( d.oldRelease ) ).append( "\">\n" );
                sb.append( "      <filename>" ).append( esc( d.location ) ).append( "</filename>\n" );
                sb.append( "      <sequence>" ).append( esc( d.sequence ) ).append( "</sequence>\n" );
                sb.append( "      <checksum type=\"sha256\">" ).append( d.sha256 ).append( "</checksum>\n" );
                sb.append( "      <size>" ).append( d.fileSize ).append( "</size>\n" );
                sb.append( "    </delta>\n" );
            }
            sb.append( "  </newpackage>\n" );
        }
        sb.append( "</prestodelta>\n" );

        return writeCompressed( repodataDir, "prestodelta.xml",
            sb.toString().getBytes( StandardCharsets.UTF_8 ) );
    }

    /**
     * Parses a delta RPM filename using the makedeltarpm convention:
     * {@code {name}-{oldVer}-{oldRel}_{newVer}-{newRel}.{arch}.drpm}
     */
    private static DeltaEntry parseDrpmFilename( Path repoRoot, Path drpmFile,
                                                  Map<String, RpmPackageInfo> pkgByNameArch )
    {
        String filename = drpmFile.getFileName().toString();
        // Strip .drpm
        String base = filename.substring( 0, filename.length() - 5 );

        // Strip arch (last dot-separated component)
        int lastDot = base.lastIndexOf( '.' );
        if ( lastDot < 0 ) return null;
        String arch = base.substring( lastDot + 1 );
        base = base.substring( 0, lastDot );

        // Split at last underscore: left="{name}-{oldVer}-{oldRel}", right="{newVer}-{newRel}"
        int underscore = base.lastIndexOf( '_' );
        if ( underscore < 0 ) return null;
        String oldPart = base.substring( 0, underscore );
        String newPart = base.substring( underscore + 1 );

        // Parse new EVR
        int dashNew = newPart.lastIndexOf( '-' );
        if ( dashNew < 0 ) return null;
        String newVersion = newPart.substring( 0, dashNew );
        String newRelease = newPart.substring( dashNew + 1 );

        // Parse old: split from right to get {name}-{oldVer}-{oldRel}
        int dashOld = oldPart.lastIndexOf( '-' );
        if ( dashOld < 0 ) return null;
        String oldRelease     = oldPart.substring( dashOld + 1 );
        String nameAndVersion = oldPart.substring( 0, dashOld );
        int    dashName       = nameAndVersion.lastIndexOf( '-' );
        if ( dashName < 0 ) return null;
        String name       = nameAndVersion.substring( 0, dashName );
        String oldVersion = nameAndVersion.substring( dashName + 1 );

        DeltaEntry d = new DeltaEntry();
        d.name       = name;
        d.arch       = arch;
        d.oldVersion = oldVersion;
        d.oldRelease = oldRelease;
        d.oldEpoch   = "0";
        d.newVersion = newVersion;
        d.newRelease = newRelease;
        RpmPackageInfo newPkg = pkgByNameArch.get( name + "." + arch );
        d.newEpoch   = ( newPkg != null && newPkg.epoch != null ) ? newPkg.epoch : "0";

        d.location = repoRoot.relativize( drpmFile ).toString().replace( '\\', '/' );
        try
        {
            d.fileSize = Files.size( drpmFile );
            d.sha256   = fileDigest( drpmFile );
        }
        catch ( IOException e )
        {
            log.warn( "Cannot stat delta RPM {}: {}", drpmFile, e.getMessage() );
            return null;
        }
        d.sequence = name + "-" + oldVersion + "-" + oldRelease + ":" + d.sha256.substring( 0, 16 );

        return d;
    }

    // -------------------------------------------------------------------------
    // modules.yaml.gz — AppStream / RHEL 8+ module metadata
    // -------------------------------------------------------------------------

    private MetadataFile writeModules( Path repodataDir, List<Path> sourceRoots ) throws IOException
    {
        StringBuilder combined = new StringBuilder();

        for ( Path root : sourceRoots )
        {
            if ( !Files.isDirectory( root ) ) continue;
            Files.walkFileTree( root, new SimpleFileVisitor<Path>()
            {
                @Override
                public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) throws IOException
                {
                    String name = file.getFileName().toString();
                    if ( name.endsWith( ".modulemd.yaml" ) || name.endsWith( ".modulemd.yml" ) )
                    {
                        if ( combined.length() > 0 ) combined.append( "\n---\n" );
                        combined.append( new String( Files.readAllBytes( file ), StandardCharsets.UTF_8 ) );
                    }
                    return FileVisitResult.CONTINUE;
                }
            } );
        }

        if ( combined.length() == 0 ) return null;

        String yaml = combined.toString();
        if ( !yaml.startsWith( "---" ) ) yaml = "---\n" + yaml;

        return writeCompressed( repodataDir, "modules.yaml",
            yaml.getBytes( StandardCharsets.UTF_8 ) );
    }

    // -------------------------------------------------------------------------
    // repomd.xml
    // -------------------------------------------------------------------------

    private byte[] buildRepomdXml( MetadataFile primary, MetadataFile filelists, MetadataFile other,
                                    MetadataFile deltarpm, MetadataFile modules )
    {
        long ts = Instant.now().getEpochSecond();
        StringBuilder sb = new StringBuilder();
        sb.append( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" );
        sb.append( "<repomd xmlns=\"http://linux.duke.edu/metadata/repo\"" );
        sb.append( " xmlns:rpm=\"http://linux.duke.edu/metadata/rpm\">\n" );
        sb.append( "  <revision>" ).append( ts ).append( "</revision>\n" );
        appendDataElement( sb, "primary",   primary,   ts );
        appendDataElement( sb, "filelists", filelists, ts );
        appendDataElement( sb, "other",     other,     ts );
        if ( deltarpm != null ) appendDataElement( sb, "deltarpm", deltarpm, ts );
        if ( modules  != null ) appendDataElement( sb, "modules",  modules,  ts );
        sb.append( "</repomd>\n" );
        return sb.toString().getBytes( StandardCharsets.UTF_8 );
    }

    private void appendDataElement( StringBuilder sb, String type, MetadataFile mf, long ts )
    {
        sb.append( "  <data type=\"" ).append( type ).append( "\">\n" );
        sb.append( "    <checksum type=\"sha256\">" ).append( mf.compressedSha256 ).append( "</checksum>\n" );
        sb.append( "    <open-checksum type=\"sha256\">" ).append( mf.openSha256 ).append( "</open-checksum>\n" );
        sb.append( "    <location href=\"repodata/" ).append( mf.filename ).append( "\"/>\n" );
        sb.append( "    <timestamp>" ).append( ts ).append( "</timestamp>\n" );
        sb.append( "    <size>" ).append( mf.compressedSize ).append( "</size>\n" );
        sb.append( "    <open-size>" ).append( mf.openSize ).append( "</open-size>\n" );
        sb.append( "  </data>\n" );
    }

    // -------------------------------------------------------------------------
    // GPG signing
    // -------------------------------------------------------------------------

    /**
     * Holds information about the repository's GPG signing key.
     */
    public static final class GpgKeyDetails
    {
        public final String fingerprint;
        public final String userId;
        public final String algorithm;
        public final int    bitStrength;
        public final Instant created;
        public final Instant expires;
        public final String armoredPublicKey;

        GpgKeyDetails( String fingerprint, String userId, String algorithm,
                       int bitStrength, Instant created, Instant expires,
                       String armoredPublicKey )
        {
            this.fingerprint     = fingerprint;
            this.userId          = userId;
            this.algorithm       = algorithm;
            this.bitStrength     = bitStrength;
            this.created         = created;
            this.expires         = expires;
            this.armoredPublicKey = armoredPublicKey;
        }
    }

    /**
     * Returns the signing key details for the repository at {@code repoRoot},
     * creating the key if it does not exist yet.
     */
    public GpgKeyDetails getKeyDetails( Path repoRoot ) throws IOException, PGPException
    {
        return getKeyDetails( repoRoot, null, null );
    }

    /**
     * Returns the signing key details, respecting an optional operator-supplied key.
     *
     * @param repoRoot    repository root
     * @param gpgKeyPath  path to an operator-supplied secret key file, or {@code null}
     * @param gpgUserId   custom user-ID for key generation, or {@code null} for the default
     */
    public GpgKeyDetails getKeyDetails( Path repoRoot, String gpgKeyPath, String gpgUserId )
        throws IOException, PGPException
    {
        PGPSecretKey key = getOrCreateSigningKey( repoRoot, gpgKeyPath, gpgUserId );
        return buildKeyDetails( key );
    }

    /**
     * Forces generation of a new auto-generated key, replacing any existing one.
     * The operator-supplied key path (if set) is NOT deleted; only the auto-key
     * at {@code .repodata/signing.pgp} is replaced.
     *
     * @param repoRoot   repository root
     * @param gpgUserId  user-ID for the new key, or {@code null} for the default
     */
    public GpgKeyDetails rotateKey( Path repoRoot, String gpgUserId ) throws IOException, PGPException
    {
        Path keyFile = repoRoot.resolve( KEYSTORE_DIR ).resolve( SIGNING_KEY );
        Files.deleteIfExists( keyFile );

        String identity = ( gpgUserId != null && !gpgUserId.isBlank() )
            ? gpgUserId
            : "Archiva RPM Repository <archiva@localhost>";
        PGPSecretKey newKey = RpmGpgKeyGenerator.generate( identity );
        Files.createDirectories( keyFile.getParent() );
        try ( OutputStream out = Files.newOutputStream( keyFile ) )
        {
            newKey.encode( out );
        }
        exportPublicKey( repoRoot, newKey );
        return buildKeyDetails( newKey );
    }

    private PGPSecretKey getOrCreateSigningKey( Path repoRoot, String gpgKeyPath, String gpgUserId )
        throws IOException, PGPException
    {
        // 1. Operator-supplied external key takes priority
        if ( gpgKeyPath != null && !gpgKeyPath.isBlank() )
        {
            Path externalKey = Path.of( gpgKeyPath );
            if ( Files.exists( externalKey ) )
            {
                try ( InputStream in = Files.newInputStream( externalKey ) )
                {
                    PGPSecretKeyRing ring = new BcPGPSecretKeyRing( PGPUtil.getDecoderStream( in ) );
                    return ring.getSecretKey();
                }
            }
            log.warn( "gpgKeyPath '{}' does not exist; falling back to auto-generated key", gpgKeyPath );
        }

        Path keyDir  = repoRoot.resolve( KEYSTORE_DIR );
        Path keyFile = keyDir.resolve( SIGNING_KEY );
        Files.createDirectories( keyDir );

        if ( Files.exists( keyFile ) )
        {
            try ( InputStream in = Files.newInputStream( keyFile ) )
            {
                PGPSecretKeyRing ring = new BcPGPSecretKeyRing( PGPUtil.getDecoderStream( in ) );
                PGPSecretKey existing = ring.getSecretKey();
                // Rotate automatically if the key has expired
                if ( isExpired( existing.getPublicKey() ) )
                {
                    log.info( "GPG signing key at {} has expired; rotating automatically", keyFile );
                    Files.delete( keyFile );
                    // Fall through to generate a fresh key below
                }
                else
                {
                    return existing;
                }
            }
        }

        String identity = ( gpgUserId != null && !gpgUserId.isBlank() )
            ? gpgUserId
            : "Archiva RPM Repository <archiva@localhost>";
        PGPSecretKey key = RpmGpgKeyGenerator.generate( identity );
        try ( OutputStream out = Files.newOutputStream( keyFile ) )
        {
            key.encode( out );
        }
        return key;
    }

    private PGPSecretKey getOrCreateSigningKey( Path repoRoot ) throws IOException, PGPException
    {
        return getOrCreateSigningKey( repoRoot, null, null );
    }

    private static boolean isExpired( PGPPublicKey pub )
    {
        long validSeconds = pub.getValidSeconds();
        if ( validSeconds <= 0 )
        {
            return false;
        }
        Instant expiry = pub.getCreationTime().toInstant().plusSeconds( validSeconds );
        return Instant.now().isAfter( expiry );
    }

    private GpgKeyDetails buildKeyDetails( PGPSecretKey key ) throws IOException
    {
        PGPPublicKey pub = key.getPublicKey();

        byte[] fpBytes = pub.getFingerprint();
        StringBuilder fp = new StringBuilder();
        for ( byte b : fpBytes ) fp.append( String.format( "%02X", b ) );

        String userId = "unknown";
        Iterator<String> ids = pub.getUserIDs();
        if ( ids.hasNext() )
        {
            userId = ids.next();
        }

        String algorithm = algorithmName( pub.getAlgorithm() );

        long validSeconds = pub.getValidSeconds();
        Instant expires = ( validSeconds > 0 )
            ? pub.getCreationTime().toInstant().plusSeconds( validSeconds )
            : null;

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try ( ArmoredOutputStream armoredOut = new ArmoredOutputStream( buf ) )
        {
            pub.encode( armoredOut );
        }
        String armored = buf.toString( StandardCharsets.UTF_8 );

        return new GpgKeyDetails(
            fp.toString(),
            userId,
            algorithm,
            pub.getBitStrength(),
            pub.getCreationTime().toInstant(),
            expires,
            armored
        );
    }

    private static String algorithmName( int algorithm )
    {
        switch ( algorithm )
        {
            case PublicKeyAlgorithmTags.RSA_GENERAL:
            case PublicKeyAlgorithmTags.RSA_SIGN:    return "RSA";
            case PublicKeyAlgorithmTags.DSA:          return "DSA";
            case PublicKeyAlgorithmTags.ECDSA:        return "ECDSA";
            case PublicKeyAlgorithmTags.EDDSA:        return "EdDSA";
            default:                                  return "algorithm-" + algorithm;
        }
    }

    private byte[] sign( byte[] data, PGPSecretKey secretKey ) throws PGPException, IOException
    {
        PGPPrivateKey privateKey = secretKey.extractPrivateKey(
            new BcPBESecretKeyDecryptorBuilder( new BcPGPDigestCalculatorProvider() ).build( new char[0] ) );

        PGPSignatureGenerator sigGen = new PGPSignatureGenerator(
            new BcPGPContentSignerBuilder( secretKey.getPublicKey().getAlgorithm(),
                HashAlgorithmTags.SHA256 ) );
        sigGen.init( PGPSignature.BINARY_DOCUMENT, privateKey );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try ( ArmoredOutputStream armoredOut = new ArmoredOutputStream( out ) )
        {
            sigGen.update( data );
            sigGen.generate().encode( armoredOut );
        }
        return out.toByteArray();
    }

    private void exportPublicKey( Path repoRoot, PGPSecretKey secretKey ) throws IOException
    {
        Path pubKeyPath = repoRoot.resolve( PUBLIC_KEY_FILE );
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try ( ArmoredOutputStream armoredOut = new ArmoredOutputStream( out ) )
        {
            secretKey.getPublicKey().encode( armoredOut );
        }
        Files.write( pubKeyPath, out.toByteArray() );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private MetadataFile writeCompressed( Path dir, String xmlName, byte[] xmlBytes )
        throws IOException
    {
        String openSha256 = sha256hex( xmlBytes );

        ByteArrayOutputStream gzipBuf = new ByteArrayOutputStream();
        try ( GZIPOutputStream gz = new GZIPOutputStream( gzipBuf ) )
        {
            gz.write( xmlBytes );
        }
        byte[] compressed = gzipBuf.toByteArray();
        String compressedSha256 = sha256hex( compressed );

        String filename = compressedSha256 + "-" + xmlName + ".gz";
        Files.write( dir.resolve( filename ), compressed );

        MetadataFile mf = new MetadataFile();
        mf.filename         = filename;
        mf.openSha256       = openSha256;
        mf.compressedSha256 = compressedSha256;
        mf.openSize         = xmlBytes.length;
        mf.compressedSize   = compressed.length;
        return mf;
    }

    private static String fileDigest( Path file ) throws IOException
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance( "SHA-256" );
            try ( InputStream in = new DigestInputStream( Files.newInputStream( file ), md ) )
            {
                byte[] buf = new byte[65536];
                while ( in.read( buf ) != -1 )
                {
                    // digest updated by DigestInputStream
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for ( byte b : digest ) sb.append( String.format( "%02x", b ) );
            return sb.toString();
        }
        catch ( NoSuchAlgorithmException e )
        {
            throw new RuntimeException( "SHA-256 not available", e );
        }
    }

    static String sha256hex( byte[] data )
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance( "SHA-256" );
            byte[] digest = md.digest( data );
            StringBuilder sb = new StringBuilder();
            for ( byte b : digest ) sb.append( String.format( "%02x", b ) );
            return sb.toString();
        }
        catch ( NoSuchAlgorithmException e )
        {
            throw new RuntimeException( "SHA-256 not available", e );
        }
    }

    private static String esc( String s )
    {
        if ( s == null ) return "";
        return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" )
                .replace( "\"", "&quot;" ).replace( "'", "&apos;" );
    }

    private static String nvl( String s )
    {
        return ( s == null || s.isEmpty() ) ? "0" : s;
    }

    // -------------------------------------------------------------------------
    // Internal DTOs
    // -------------------------------------------------------------------------

    static final class MetadataFile
    {
        String filename;
        String openSha256;
        String compressedSha256;
        long   openSize;
        long   compressedSize;
    }

    private static final class DeltaEntry
    {
        String name, arch;
        String newEpoch, newVersion, newRelease;
        String oldEpoch, oldVersion, oldRelease;
        String location, sha256, sequence;
        long   fileSize;

        /** Key used to group delta entries under the same {@code <newpackage>} element. */
        String newPkgKey()
        {
            return name + "\0" + arch + "\0" + newVersion + "\0" + newRelease;
        }
    }
}
