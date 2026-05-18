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
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRing;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Generates the full {@code repodata/} directory structure for an RPM repository:
 * <ol>
 *   <li>Scans all RPM files under {@code RPMS/} and {@code SRPMS/}</li>
 *   <li>Parses each RPM's binary header</li>
 *   <li>Writes {@code repodata/{checksum}-primary.xml.gz}</li>
 *   <li>Writes stub {@code repodata/{checksum}-filelists.xml.gz}</li>
 *   <li>Writes stub {@code repodata/{checksum}-other.xml.gz}</li>
 *   <li>Writes {@code repodata/repomd.xml}</li>
 *   <li>Signs {@code repomd.xml} as {@code repodata/repomd.xml.asc} (armored detached)</li>
 *   <li>Exports the public key to {@code repokey.gpg}</li>
 * </ol>
 *
 * <p>The GPG key is stored in {@code .repodata/signing.pgp} under the repository root.
 * On first use, a new key pair is auto-generated and persisted.
 *
 * <p>This class is intentionally stateless and not a Spring bean; callers
 * (servlet + consumer) obtain it via {@code new RepomdGenerator()}.
 */
public class RepomdGenerator
{
    private static final Logger log = LoggerFactory.getLogger( RepomdGenerator.class );

    private static final String REPODATA_DIR   = "repodata";
    private static final String KEYSTORE_DIR   = ".repodata";
    private static final String SIGNING_KEY    = "signing.pgp";
    private static final String PUBLIC_KEY_FILE = "repokey.gpg";
    private static final String REPOMD_XML     = "repomd.xml";
    private static final String REPOMD_SIG     = "repomd.xml.asc";

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
        log.info( "Rebuilding repodata for repository at {}", repoRoot );

        // 1. Collect all RPM packages
        List<RpmPackageInfo> packages = RpmScanner.scan( repoRoot );
        log.debug( "Found {} RPM packages", packages.size() );

        // 2. Write metadata XML files
        Path repodataDir = repoRoot.resolve( REPODATA_DIR );
        Files.createDirectories( repodataDir );

        MetadataFile primary   = writePrimary( repodataDir, packages );
        MetadataFile filelists = writeFilelists( repodataDir, packages );
        MetadataFile other     = writeOther( repodataDir, packages );

        // 3. Write repomd.xml
        byte[] repomdBytes = buildRepomdXml( primary, filelists, other );
        Path repomdPath = repodataDir.resolve( REPOMD_XML );
        Files.write( repomdPath, repomdBytes );

        // 4. Sign repomd.xml
        try
        {
            PGPSecretKey signingKey = getOrCreateSigningKey( repoRoot );
            byte[] sig = sign( repomdBytes, signingKey );
            Files.write( repodataDir.resolve( REPOMD_SIG ), sig );
            exportPublicKey( repoRoot, signingKey );
        }
        catch ( PGPException e )
        {
            log.error( "GPG signing failed for repomd.xml: {}", e.getMessage(), e );
            throw new IOException( "GPG signing failed", e );
        }

        log.info( "repodata rebuild complete for {}", repoRoot );
    }

    // -------------------------------------------------------------------------
    // primary.xml.gz
    // -------------------------------------------------------------------------

    private MetadataFile writePrimary( Path repodataDir, List<RpmPackageInfo> packages )
        throws IOException
    {
        byte[] xml = PrimaryXmlBuilder.build( packages );
        return writeCompressed( repodataDir, "primary", "primary.xml", xml );
    }

    // -------------------------------------------------------------------------
    // filelists.xml.gz  (stub — full implementation in TODO)
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
            for ( String f : p.files )
            {
                sb.append( "    <file>" ).append( esc( f ) ).append( "</file>\n" );
            }
            sb.append( "  </package>\n" );
        }
        sb.append( "</filelists>\n" );
        return writeCompressed( repodataDir, "filelists", "filelists.xml",
            sb.toString().getBytes( StandardCharsets.UTF_8 ) );
    }

    // -------------------------------------------------------------------------
    // other.xml.gz  (stub — changelog parsing deferred to TODO)
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
            sb.append( "  </package>\n" );
        }
        sb.append( "</otherdata>\n" );
        return writeCompressed( repodataDir, "other", "other.xml",
            sb.toString().getBytes( StandardCharsets.UTF_8 ) );
    }

    // -------------------------------------------------------------------------
    // repomd.xml
    // -------------------------------------------------------------------------

    private byte[] buildRepomdXml( MetadataFile primary, MetadataFile filelists, MetadataFile other )
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

    private PGPSecretKey getOrCreateSigningKey( Path repoRoot ) throws IOException, PGPException
    {
        Path keyDir  = repoRoot.resolve( KEYSTORE_DIR );
        Path keyFile = keyDir.resolve( SIGNING_KEY );
        Files.createDirectories( keyDir );

        if ( Files.exists( keyFile ) )
        {
            try ( InputStream in = Files.newInputStream( keyFile ) )
            {
                PGPSecretKeyRing ring = new BcPGPSecretKeyRing( PGPUtil.getDecoderStream( in ) );
                return ring.getSecretKey();
            }
        }

        // Generate a new key pair and persist it
        PGPSecretKey key = RpmGpgKeyGenerator.generate( "Archiva RPM Repository <archiva@localhost>" );
        try ( OutputStream out = Files.newOutputStream( keyFile ) )
        {
            key.encode( out );
        }
        return key;
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

    private MetadataFile writeCompressed( Path dir, String type, String xmlName, byte[] xmlBytes )
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
    // Internal DTO
    // -------------------------------------------------------------------------

    static final class MetadataFile
    {
        String filename;
        String openSha256;
        String compressedSha256;
        long   openSize;
        long   compressedSize;
    }
}
