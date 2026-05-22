package org.apache.archiva.rpm.repository.servlet;

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

import org.apache.archiva.redback.authentication.AuthenticationException;
import org.apache.archiva.redback.authentication.AuthenticationResult;
import org.apache.archiva.redback.authorization.UnauthorizedException;
import org.apache.archiva.redback.integration.filter.authentication.HttpAuthenticator;
import org.apache.archiva.redback.policy.AccountLockedException;
import org.apache.archiva.redback.policy.MustChangePasswordException;
import org.apache.archiva.redback.system.SecuritySession;
import org.apache.archiva.repository.ManagedRepository;
import org.apache.archiva.repository.RepositoryGroup;
import org.apache.archiva.repository.RepositoryRegistry;
import org.apache.archiva.repository.RepositoryType;
import org.apache.archiva.rpm.repository.repodata.RepomdGenerator;
import org.apache.archiva.security.ServletAuthenticator;
import org.apache.archiva.security.common.ArchivaRoleConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servlet that serves the RPM repository protocol to yum/dnf clients.
 *
 * <p>Mounted at {@code /rpm/*} in {@code web.xml}. URL structure:
 * <pre>
 *   GET    /rpm/{repoId}/repodata/repomd.xml          — repo index
 *   GET    /rpm/{repoId}/repodata/repomd.xml.asc       — GPG signature
 *   GET    /rpm/{repoId}/repodata/{checksum}-*.xml.gz  — metadata files
 *   GET    /rpm/{repoId}/repokey.gpg                   — public key
 *   GET    /rpm/{repoId}/RPMS/{arch}/{file}.rpm         — download package
 *   GET    /rpm/{repoId}/SRPMS/{file}.rpm               — download source package
 *   PUT    /rpm/{repoId}/RPMS/{arch}/{file}.rpm         — upload package (full or chunked)
 *   DELETE /rpm/{repoId}/RPMS/{arch}/{file}.rpm         — remove package
 * </pre>
 *
 * <p>After a successful PUT, repodata is automatically rebuilt.
 *
 * <h3>ETag / conditional GET</h3>
 * Metadata files (≤10 MB) carry a strong SHA-256 ETag; larger files (RPMs) use a
 * {@code "size-mtime"} weak form. A matching {@code If-None-Match} header results
 * in a 304 Not Modified response.
 *
 * <h3>Content-Range / resume upload</h3>
 * PUT requests may carry a {@code Content-Range: bytes start-end/total} header.
 * Partial chunks return 202 Accepted; the final chunk returns 201 Created and
 * triggers a repodata rebuild.
 *
 * <h3>Checksum verification</h3>
 * An optional {@code X-Checksum-SHA256} request header is verified against the
 * uploaded bytes before they are committed to storage.
 */
public class RpmRegistryServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger( RpmRegistryServlet.class );

    /** Files smaller than this threshold get a strong SHA-256 ETag; larger files use size+mtime. */
    private static final long CHEAP_ETAG_THRESHOLD = 10L * 1024 * 1024;
    private static final String REALM = "Archiva RPM Repository";

    private transient RepositoryRegistry repositoryRegistry;
    private transient ServletAuthenticator servletAuth;
    private transient HttpAuthenticator httpAuth;
    private final RepomdGenerator repomdGenerator = new RepomdGenerator();

    @Override
    public void init() throws ServletException
    {
        WebApplicationContext ctx =
            WebApplicationContextUtils.getWebApplicationContext( getServletContext() );
        if ( ctx != null )
        {
            repositoryRegistry = ctx.getBean( RepositoryRegistry.class );
            servletAuth = optionalBean( ctx, "servletAuthenticator", ServletAuthenticator.class );
            httpAuth    = optionalBean( ctx, "httpAuthenticator#basic", HttpAuthenticator.class );
        }
        if ( repositoryRegistry == null )
        {
            log.warn( "RpmRegistryServlet: RepositoryRegistry not available — servlet disabled" );
        }
    }

    private <T> T optionalBean( WebApplicationContext ctx, String name, Class<T> type )
    {
        try
        {
            return ctx.getBean( name, type );
        }
        catch ( Exception e )
        {
            log.info( "No {} bean registered; access control disabled", name );
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // GET — serve repodata and packages
    // -------------------------------------------------------------------------

    @Override
    protected void doGet( HttpServletRequest req, HttpServletResponse resp )
        throws ServletException, IOException
    {
        RpmRequest rpmReq = parse( req );
        if ( rpmReq == null )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "Invalid RPM repository URL" );
            return;
        }

        if ( repositoryRegistry == null )
        {
            resp.sendError( HttpServletResponse.SC_SERVICE_UNAVAILABLE, "RepositoryRegistry not available" );
            return;
        }

        // Try managed repository first
        ManagedRepository repo = repositoryRegistry.getManagedRepository( rpmReq.repoId );
        if ( repo != null && repo.getType() == RepositoryType.RPM )
        {
            if ( !checkReadAccess( req, resp, rpmReq.repoId ) ) return;
            serveFile( rpmReq.path, repo.getRoot().getFilePath(), req, resp );
            return;
        }

        // Try repository group
        RepositoryGroup group = repositoryRegistry.getRepositoryGroup( rpmReq.repoId );
        if ( group != null && group.getType() == RepositoryType.RPM )
        {
            if ( !checkReadAccess( req, resp, rpmReq.repoId ) ) return;
            serveFromGroup( rpmReq, group, req, resp );
            return;
        }

        resp.sendError( HttpServletResponse.SC_NOT_FOUND,
            "No RPM repository or group found with id: " + rpmReq.repoId );
    }

    private void serveFile( String path, Path repoRoot, HttpServletRequest req, HttpServletResponse resp )
        throws IOException
    {
        Path target = repoRoot.resolve( path ).normalize();
        if ( !target.startsWith( repoRoot ) )
        {
            resp.sendError( HttpServletResponse.SC_FORBIDDEN, "Path traversal rejected" );
            return;
        }
        if ( !Files.exists( target ) )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Not found: " + path );
            return;
        }

        String etag = computeETag( target );
        resp.setHeader( "ETag", etag );

        String ifNoneMatch = req.getHeader( "If-None-Match" );
        if ( etag.equals( ifNoneMatch ) )
        {
            resp.setStatus( HttpServletResponse.SC_NOT_MODIFIED );
            return;
        }

        resp.setContentType( contentTypeFor( path ) );
        resp.setContentLengthLong( Files.size( target ) );
        try ( InputStream in = Files.newInputStream( target );
              OutputStream out = resp.getOutputStream() )
        {
            byte[] buf = new byte[65536];
            int n;
            while ( ( n = in.read( buf ) ) != -1 )
            {
                out.write( buf, 0, n );
            }
        }
    }

    private void serveFromGroup( RpmRequest rpmReq, RepositoryGroup group,
                                 HttpServletRequest req, HttpServletResponse resp )
        throws IOException
    {
        List<ManagedRepository> members = group.getRepositories();
        if ( members.isEmpty() )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND,
                "Repository group " + rpmReq.repoId + " has no members" );
            return;
        }

        Path groupRoot = group.getRoot().getFilePath();

        if ( rpmReq.path.startsWith( "repodata/" ) || rpmReq.path.equals( "repokey.gpg" ) )
        {
            // Rebuild merged repodata on demand if repomd.xml is absent
            Path repomdXml = groupRoot.resolve( "repodata/repomd.xml" );
            if ( !Files.exists( repomdXml ) )
            {
                List<Path> memberRoots = members.stream()
                    .map( m -> m.getRoot().getFilePath() )
                    .collect( Collectors.toList() );
                try
                {
                    Files.createDirectories( groupRoot );
                    repomdGenerator.rebuildMerged( groupRoot, memberRoots );
                }
                catch ( IOException e )
                {
                    log.error( "Failed to build merged repodata for group {}: {}",
                        rpmReq.repoId, e.getMessage(), e );
                    resp.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Could not generate merged repodata" );
                    return;
                }
            }
            serveFile( rpmReq.path, groupRoot, req, resp );
            return;
        }

        // For package requests, search member repositories in order
        for ( ManagedRepository member : members )
        {
            Path memberRoot = member.getRoot().getFilePath();
            Path candidate  = memberRoot.resolve( rpmReq.path ).normalize();
            if ( candidate.startsWith( memberRoot ) && Files.exists( candidate ) )
            {
                serveFile( rpmReq.path, memberRoot, req, resp );
                return;
            }
        }

        resp.sendError( HttpServletResponse.SC_NOT_FOUND,
            "Not found in group " + rpmReq.repoId + ": " + rpmReq.path );
    }

    // -------------------------------------------------------------------------
    // PUT — upload an RPM package (full or chunked) and rebuild repodata
    // -------------------------------------------------------------------------

    @Override
    protected void doPut( HttpServletRequest req, HttpServletResponse resp )
        throws ServletException, IOException
    {
        RpmRequest rpmReq = parse( req );
        if ( rpmReq == null
            || ( !rpmReq.path.startsWith( "RPMS/" ) && !rpmReq.path.startsWith( "SRPMS/" ) ) )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST,
                "PUT is only allowed under RPMS/ or SRPMS/" );
            return;
        }
        if ( !rpmReq.path.endsWith( ".rpm" ) )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "Only .rpm files may be uploaded" );
            return;
        }

        ManagedRepository repo = resolveRepo( rpmReq.repoId, resp );
        if ( repo == null ) return;

        if ( !checkWriteAccess( req, resp, rpmReq.repoId ) ) return;

        Path repoRoot = repo.getRoot().getFilePath();
        Path target   = repoRoot.resolve( rpmReq.path ).normalize();
        if ( !target.startsWith( repoRoot ) )
        {
            resp.sendError( HttpServletResponse.SC_FORBIDDEN, "Path traversal rejected" );
            return;
        }

        String contentRangeHeader = req.getHeader( "Content-Range" );
        if ( contentRangeHeader != null )
        {
            handleRangeUpload( rpmReq, repoRoot, target, contentRangeHeader, req, resp );
        }
        else
        {
            handleFullUpload( rpmReq, repoRoot, target, req, resp );
        }
    }

    /**
     * Handles a standard (non-chunked) PUT.  The body is written to a temp file
     * so the optional {@code X-Checksum-SHA256} header can be verified before
     * the file is moved into place.
     */
    private void handleFullUpload( RpmRequest rpmReq, Path repoRoot, Path target,
                                   HttpServletRequest req, HttpServletResponse resp )
        throws IOException
    {
        Files.createDirectories( target.getParent() );
        Path tempFile = target.resolveSibling( target.getFileName() + ".tmp." + System.nanoTime() );
        String expectedChecksum = req.getHeader( "X-Checksum-SHA256" );

        try
        {
            MessageDigest digest = getSHA256();
            try ( InputStream raw = req.getInputStream();
                  InputStream in  = digest != null ? new DigestInputStream( raw, digest ) : raw )
            {
                Files.copy( in, tempFile, StandardCopyOption.REPLACE_EXISTING );
            }

            if ( expectedChecksum != null && digest != null )
            {
                String actual = hexString( digest.digest() );
                if ( !actual.equalsIgnoreCase( expectedChecksum ) )
                {
                    log.warn( "SHA-256 mismatch for {}: expected={} actual={}",
                        rpmReq.path, expectedChecksum, actual );
                    resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "X-Checksum-SHA256 mismatch" );
                    return;
                }
            }

            Files.move( tempFile, target, StandardCopyOption.REPLACE_EXISTING );
            tempFile = null;
        }
        finally
        {
            if ( tempFile != null )
            {
                try { Files.deleteIfExists( tempFile ); } catch ( IOException ignored ) { }
            }
        }

        log.info( "Uploaded RPM {} to repo {}", rpmReq.path, rpmReq.repoId );

        try
        {
            repomdGenerator.rebuild( repoRoot );
        }
        catch ( IOException e )
        {
            log.error( "Failed to rebuild repodata after upload: {}", e.getMessage(), e );
            resp.setStatus( HttpServletResponse.SC_ACCEPTED );
            return;
        }

        resp.setStatus( HttpServletResponse.SC_CREATED );
    }

    /**
     * Handles a chunked PUT carrying a {@code Content-Range} header.  Each chunk
     * is written at the indicated byte offset using a {@link FileChannel}.
     * Returns 202 Accepted for partial chunks and 201 Created when the final chunk
     * completes the file.
     */
    private void handleRangeUpload( RpmRequest rpmReq, Path repoRoot, Path target,
                                    String contentRangeHeader,
                                    HttpServletRequest req, HttpServletResponse resp )
        throws IOException
    {
        ContentRange range = ContentRange.parse( contentRangeHeader );
        if ( range == null )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST,
                "Invalid Content-Range header: " + contentRangeHeader );
            return;
        }

        Files.createDirectories( target.getParent() );

        try ( FileChannel fc = FileChannel.open( target,
                  StandardOpenOption.CREATE, StandardOpenOption.WRITE );
              InputStream in = req.getInputStream() )
        {
            fc.position( range.start );
            byte[] buf = new byte[65536];
            int n;
            while ( ( n = in.read( buf ) ) != -1 )
            {
                ByteBuffer bb = ByteBuffer.wrap( buf, 0, n );
                while ( bb.hasRemaining() )
                {
                    fc.write( bb );
                }
            }
        }

        boolean lastChunk = ( range.end + 1 >= range.total );
        if ( !lastChunk )
        {
            resp.setStatus( HttpServletResponse.SC_ACCEPTED );
            return;
        }

        // Final chunk — verify checksum of the complete file if header was provided
        String expectedChecksum = req.getHeader( "X-Checksum-SHA256" );
        if ( expectedChecksum != null )
        {
            String actual = sha256Hex( target );
            if ( actual != null && !actual.equalsIgnoreCase( expectedChecksum ) )
            {
                log.warn( "SHA-256 mismatch for {} after range upload: expected={} actual={}",
                    rpmReq.path, expectedChecksum, actual );
                Files.deleteIfExists( target );
                resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "X-Checksum-SHA256 mismatch" );
                return;
            }
        }

        log.info( "Uploaded RPM {} to repo {} (range upload complete)", rpmReq.path, rpmReq.repoId );

        try
        {
            repomdGenerator.rebuild( repoRoot );
        }
        catch ( IOException e )
        {
            log.error( "Failed to rebuild repodata after range upload: {}", e.getMessage(), e );
            resp.setStatus( HttpServletResponse.SC_ACCEPTED );
            return;
        }

        resp.setStatus( HttpServletResponse.SC_CREATED );
    }

    // -------------------------------------------------------------------------
    // DELETE — remove a package and rebuild repodata
    // -------------------------------------------------------------------------

    @Override
    protected void doDelete( HttpServletRequest req, HttpServletResponse resp )
        throws ServletException, IOException
    {
        RpmRequest rpmReq = parse( req );
        if ( rpmReq == null )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST );
            return;
        }

        ManagedRepository repo = resolveRepo( rpmReq.repoId, resp );
        if ( repo == null ) return;

        if ( !checkDeleteAccess( req, resp, rpmReq.repoId ) ) return;

        Path repoRoot = repo.getRoot().getFilePath();
        Path target   = repoRoot.resolve( rpmReq.path ).normalize();

        if ( !target.startsWith( repoRoot ) )
        {
            resp.sendError( HttpServletResponse.SC_FORBIDDEN, "Path traversal rejected" );
            return;
        }
        if ( !Files.exists( target ) )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

        Files.delete( target );
        log.info( "Deleted RPM {} from repo {}", rpmReq.path, rpmReq.repoId );

        try
        {
            repomdGenerator.rebuild( repoRoot );
        }
        catch ( IOException e )
        {
            log.error( "Failed to rebuild repodata after delete: {}", e.getMessage(), e );
        }

        resp.setStatus( HttpServletResponse.SC_OK );
    }

    // -------------------------------------------------------------------------
    // Access control
    // -------------------------------------------------------------------------

    private boolean checkReadAccess( HttpServletRequest req, HttpServletResponse resp, String repoId )
        throws IOException
    {
        return checkAccess( req, resp, repoId, ArchivaRoleConstants.OPERATION_READ_REPOSITORY );
    }

    private boolean checkWriteAccess( HttpServletRequest req, HttpServletResponse resp, String repoId )
        throws IOException
    {
        return checkAccess( req, resp, repoId, ArchivaRoleConstants.OPERATION_ADD_ARTIFACT );
    }

    private boolean checkDeleteAccess( HttpServletRequest req, HttpServletResponse resp, String repoId )
        throws IOException
    {
        return checkAccess( req, resp, repoId, ArchivaRoleConstants.OPERATION_DELETE_ARTIFACT );
    }

    /**
     * Returns {@code true} when the request is authorised for the given operation,
     * or when no authentication beans are configured (permissive mode).
     * Sends an HTTP 401 / 403 response and returns {@code false} on denial.
     */
    private boolean checkAccess( HttpServletRequest req, HttpServletResponse resp,
                                 String repoId, String operation )
        throws IOException
    {
        if ( servletAuth == null || httpAuth == null )
        {
            return true;
        }

        try
        {
            AuthenticationResult result = httpAuth.getAuthenticationResult( req, null );
            SecuritySession securitySession = httpAuth.getSecuritySession( req.getSession( true ) );

            if ( result != null && result.isAuthenticated() )
            {
                if ( servletAuth.isAuthenticated( req, result )
                    && servletAuth.isAuthorized( req, securitySession, repoId, operation ) )
                {
                    return true;
                }
            }
            else
            {
                // Unauthenticated request — try guest access
                if ( servletAuth.isAuthorized( "guest", repoId, operation ) )
                {
                    return true;
                }
            }
        }
        catch ( AuthenticationException | AccountLockedException | MustChangePasswordException e )
        {
            resp.setHeader( "WWW-Authenticate", "Basic realm=\"" + REALM + "\"" );
            resp.sendError( HttpServletResponse.SC_UNAUTHORIZED, e.getMessage() );
            return false;
        }
        catch ( UnauthorizedException e )
        {
            if ( ArchivaRoleConstants.OPERATION_READ_REPOSITORY.equals( operation ) )
            {
                resp.setHeader( "WWW-Authenticate", "Basic realm=\"" + REALM + "\"" );
                resp.sendError( HttpServletResponse.SC_UNAUTHORIZED, e.getMessage() );
            }
            else
            {
                resp.sendError( HttpServletResponse.SC_FORBIDDEN, e.getMessage() );
            }
            return false;
        }
        catch ( Exception e )
        {
            log.warn( "Access check error: {}", e.getMessage() );
            resp.setHeader( "WWW-Authenticate", "Basic realm=\"" + REALM + "\"" );
            resp.sendError( HttpServletResponse.SC_UNAUTHORIZED );
            return false;
        }

        resp.setHeader( "WWW-Authenticate", "Basic realm=\"" + REALM + "\"" );
        resp.sendError( HttpServletResponse.SC_UNAUTHORIZED );
        return false;
    }

    // -------------------------------------------------------------------------
    // ETag helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a quoted ETag string.  For files up to {@link #CHEAP_ETAG_THRESHOLD}
     * a strong SHA-256 ETag is used; for larger files a weak {@code "size-mtime"} form
     * is used to avoid hashing multi-megabyte RPMs on every request.
     */
    String computeETag( Path file ) throws IOException
    {
        long size = Files.size( file );
        if ( size <= CHEAP_ETAG_THRESHOLD )
        {
            String hash = sha256Hex( file );
            if ( hash != null )
            {
                return "\"" + hash + "\"";
            }
        }
        long mtime = Files.getLastModifiedTime( file ).toMillis();
        return "\"" + size + "-" + mtime + "\"";
    }

    private String sha256Hex( Path file ) throws IOException
    {
        MessageDigest digest = getSHA256();
        if ( digest == null ) return null;
        try ( InputStream in = Files.newInputStream( file );
              DigestInputStream dis = new DigestInputStream( in, digest ) )
        {
            byte[] buf = new byte[65536];
            //noinspection StatementWithEmptyBody
            while ( dis.read( buf ) != -1 ) { }
        }
        return hexString( digest.digest() );
    }

    private static String hexString( byte[] bytes )
    {
        StringBuilder sb = new StringBuilder( bytes.length * 2 );
        for ( byte b : bytes )
        {
            sb.append( String.format( "%02x", b & 0xFF ) );
        }
        return sb.toString();
    }

    private static MessageDigest getSHA256()
    {
        try
        {
            return MessageDigest.getInstance( "SHA-256" );
        }
        catch ( NoSuchAlgorithmException e )
        {
            log.warn( "SHA-256 not available — checksum and strong ETag disabled" );
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ManagedRepository resolveRepo( String repoId, HttpServletResponse resp ) throws IOException
    {
        if ( repositoryRegistry == null )
        {
            resp.sendError( HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "RepositoryRegistry not available" );
            return null;
        }
        ManagedRepository repo = repositoryRegistry.getManagedRepository( repoId );
        if ( repo == null || repo.getType() != RepositoryType.RPM )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND,
                "No RPM repository found with id: " + repoId );
            return null;
        }
        return repo;
    }

    /**
     * Parses the servlet path {@code /rpm/{repoId}/{path...}} into repoId + path.
     * Returns null if the path is malformed.
     */
    private RpmRequest parse( HttpServletRequest req )
    {
        String info = req.getPathInfo();
        if ( info == null || info.length() < 2 )
        {
            return null;
        }
        String stripped = info.startsWith( "/" ) ? info.substring( 1 ) : info;
        int slash = stripped.indexOf( '/' );
        if ( slash < 0 )
        {
            return null;
        }
        RpmRequest r = new RpmRequest();
        r.repoId = stripped.substring( 0, slash );
        r.path   = stripped.substring( slash + 1 );
        return r;
    }

    private String contentTypeFor( String path )
    {
        if ( path.endsWith( ".xml" ) )        return "application/xml";
        if ( path.endsWith( ".xml.gz" ) )     return "application/x-gzip";
        if ( path.endsWith( ".asc" ) )        return "text/plain";
        if ( path.endsWith( ".gpg" ) )        return "application/pgp-keys";
        if ( path.endsWith( ".rpm" ) )        return "application/x-rpm";
        return "application/octet-stream";
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /**
     * Parsed {@code Content-Range} header value: {@code bytes start-end/total}.
     */
    static final class ContentRange
    {
        final long start;
        final long end;
        final long total;

        private ContentRange( long start, long end, long total )
        {
            this.start = start;
            this.end   = end;
            this.total = total;
        }

        /**
         * Parses a {@code Content-Range} header of the form {@code bytes start-end/total}.
         * Returns {@code null} when the value is absent, malformed, or out of range.
         */
        static ContentRange parse( String header )
        {
            if ( header == null ) return null;
            String value = header.trim();
            if ( !value.startsWith( "bytes " ) ) return null;
            value = value.substring( 6 ).trim();
            int dash  = value.indexOf( '-' );
            int slash = value.indexOf( '/' );
            if ( dash < 0 || slash < 0 || dash > slash ) return null;
            try
            {
                long start = Long.parseLong( value.substring( 0, dash ) );
                long end   = Long.parseLong( value.substring( dash + 1, slash ) );
                long total = Long.parseLong( value.substring( slash + 1 ) );
                if ( start < 0 || end < start || total <= end ) return null;
                return new ContentRange( start, end, total );
            }
            catch ( NumberFormatException e )
            {
                return null;
            }
        }
    }

    private static final class RpmRequest
    {
        String repoId;
        String path;
    }
}
