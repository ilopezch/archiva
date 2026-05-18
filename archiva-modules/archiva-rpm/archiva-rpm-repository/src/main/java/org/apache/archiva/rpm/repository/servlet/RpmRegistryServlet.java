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

import org.apache.archiva.repository.ManagedRepository;
import org.apache.archiva.repository.RepositoryRegistry;
import org.apache.archiva.repository.RepositoryType;
import org.apache.archiva.rpm.repository.repodata.RepomdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Servlet that serves the RPM repository protocol to yum/dnf clients.
 *
 * <p>Mounted at {@code /rpm/*} in {@code web.xml}. URL structure:
 * <pre>
 *   GET  /rpm/{repoId}/repodata/repomd.xml          — repo index
 *   GET  /rpm/{repoId}/repodata/repomd.xml.asc       — GPG signature
 *   GET  /rpm/{repoId}/repodata/{checksum}-*.xml.gz  — metadata files
 *   GET  /rpm/{repoId}/repokey.gpg                   — public key
 *   GET  /rpm/{repoId}/RPMS/{arch}/{file}.rpm         — download package
 *   GET  /rpm/{repoId}/SRPMS/{file}.rpm               — download source package
 *   PUT  /rpm/{repoId}/RPMS/{arch}/{file}.rpm         — upload package
 * </pre>
 *
 * <p>After a successful PUT, repodata is automatically rebuilt.
 */
public class RpmRegistryServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger( RpmRegistryServlet.class );

    private transient RepositoryRegistry repositoryRegistry;
    private final RepomdGenerator repomdGenerator = new RepomdGenerator();

    @Override
    public void init() throws ServletException
    {
        WebApplicationContext ctx =
            WebApplicationContextUtils.getWebApplicationContext( getServletContext() );
        if ( ctx != null )
        {
            repositoryRegistry = ctx.getBean( RepositoryRegistry.class );
        }
        if ( repositoryRegistry == null )
        {
            log.warn( "RpmRegistryServlet: RepositoryRegistry not available — servlet disabled" );
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

        ManagedRepository repo = resolveRepo( rpmReq.repoId, resp );
        if ( repo == null ) return;

        Path repoRoot = repo.getRoot().getFilePath();
        Path target   = repoRoot.resolve( rpmReq.path ).normalize();

        if ( !target.startsWith( repoRoot ) )
        {
            resp.sendError( HttpServletResponse.SC_FORBIDDEN, "Path traversal rejected" );
            return;
        }

        if ( !Files.exists( target ) )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Not found: " + rpmReq.path );
            return;
        }

        resp.setContentType( contentTypeFor( rpmReq.path ) );
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

    // -------------------------------------------------------------------------
    // PUT — upload an RPM package and trigger repodata rebuild
    // -------------------------------------------------------------------------

    @Override
    protected void doPut( HttpServletRequest req, HttpServletResponse resp )
        throws ServletException, IOException
    {
        RpmRequest rpmReq = parse( req );
        if ( rpmReq == null || ( !rpmReq.path.startsWith( "RPMS/" ) && !rpmReq.path.startsWith( "SRPMS/" ) ) )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "PUT is only allowed under RPMS/ or SRPMS/" );
            return;
        }
        if ( !rpmReq.path.endsWith( ".rpm" ) )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "Only .rpm files may be uploaded" );
            return;
        }

        ManagedRepository repo = resolveRepo( rpmReq.repoId, resp );
        if ( repo == null ) return;

        Path repoRoot = repo.getRoot().getFilePath();
        Path target   = repoRoot.resolve( rpmReq.path ).normalize();

        if ( !target.startsWith( repoRoot ) )
        {
            resp.sendError( HttpServletResponse.SC_FORBIDDEN, "Path traversal rejected" );
            return;
        }

        Files.createDirectories( target.getParent() );
        try ( InputStream in = req.getInputStream() )
        {
            Files.copy( in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING );
        }

        log.info( "Uploaded RPM {} to repo {}", rpmReq.path, rpmReq.repoId );

        // Rebuild repodata after upload
        try
        {
            repomdGenerator.rebuild( repoRoot );
        }
        catch ( IOException e )
        {
            log.error( "Failed to rebuild repodata after upload: {}", e.getMessage(), e );
            // RPM is saved but metadata may be stale — return 202 Accepted
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
        // Strip leading slash
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

    private static final class RpmRequest
    {
        String repoId;
        String path;
    }
}
