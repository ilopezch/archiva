package org.apache.archiva.npm.repository.servlet;

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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.archiva.indexer.search.RepositorySearch;
import org.apache.archiva.indexer.search.RepositorySearchException;
import org.apache.archiva.indexer.search.SearchResultHit;
import org.apache.archiva.indexer.search.SearchResultLimits;
import org.apache.archiva.indexer.search.SearchResults;
import org.apache.archiva.proxy.model.RepositoryProxyHandler;
import org.apache.archiva.redback.authentication.AuthenticationException;
import org.apache.archiva.redback.authentication.AuthenticationResult;
import org.apache.archiva.redback.authorization.UnauthorizedException;
import org.apache.archiva.redback.integration.filter.authentication.HttpAuthenticator;
import org.apache.archiva.redback.policy.AccountLockedException;
import org.apache.archiva.redback.policy.MustChangePasswordException;
import org.apache.archiva.redback.system.SecuritySession;
import org.apache.archiva.repository.ManagedRepository;
import org.apache.archiva.repository.RepositoryRegistry;
import org.apache.archiva.repository.base.ArchivaRepositoryRegistry;
import org.apache.archiva.repository.storage.StorageAsset;
import org.apache.archiva.security.ServletAuthenticator;
import org.apache.archiva.security.common.ArchivaRoleConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servlet implementing the npm registry protocol over HTTP.
 *
 * <p>URL pattern: {@code /npm/{repoId}/{...npmPath}}
 *
 * <ul>
 *   <li>GET  {@code /npm/{repoId}/{name}}                              — full package metadata JSON</li>
 *   <li>GET  {@code /npm/{repoId}/{name}/{version}}                    — single-version metadata JSON</li>
 *   <li>GET  {@code /npm/{repoId}/{name}/-/{file}}                     — download tarball</li>
 *   <li>GET  {@code /npm/{repoId}/-/package/{name}/dist-tags}          — list dist-tags</li>
 *   <li>GET  {@code /npm/{repoId}/-/v1/search?text=...}                — search packages</li>
 *   <li>GET  {@code /npm/{repoId}/-/v1/done?session=UUID}              — web login polling</li>
 *   <li>POST {@code /npm/{repoId}/-/v1/login}                          — initiate web login</li>
 *   <li>POST {@code /npm/{repoId}/-/v1/session/{uuid}/confirm}         — browser confirms login</li>
 *   <li>PUT  {@code /npm/{repoId}/{name}}                              — publish package</li>
 *   <li>PUT  {@code /npm/{repoId}/-/package/{name}/dist-tags/{t}}      — set dist-tag</li>
 *   <li>DELETE {@code /npm/{repoId}/{name}}                            — unpublish package</li>
 * </ul>
 *
 * <p>Scoped packages use an additional {@code @scope} segment before the name.
 */
public class NpmRegistryServlet extends HttpServlet
{
    private static final Logger log = LoggerFactory.getLogger( NpmRegistryServlet.class );

    private static final long serialVersionUID = 1L;

    private static final int DEFAULT_SEARCH_SIZE = 20;

    private RepositoryRegistry repositoryRegistry;
    private RepositoryProxyHandler proxyHandler;
    private ServletAuthenticator servletAuth;
    private HttpAuthenticator httpAuth;
    private RepositorySearch repositorySearch;

    private final ObjectMapper mapper = new ObjectMapper();

    // ---- Web login session store ----------------------------------------

    private static final long SESSION_TTL_MS = 10L * 60 * 1000; // 10 minutes

    private static final ConcurrentHashMap<String, NpmPendingLogin> pendingSessions =
        new ConcurrentHashMap<>();

    private static final class NpmPendingLogin
    {
        final String uuid;
        final String repoId;
        final long createdAt = System.currentTimeMillis();
        volatile String token; // null until browser confirms

        NpmPendingLogin( String uuid, String repoId )
        {
            this.uuid = uuid;
            this.repoId = repoId;
        }

        boolean isExpired()
        {
            return System.currentTimeMillis() - createdAt > SESSION_TTL_MS;
        }
    }

    @Override
    public void init( ServletConfig config ) throws ServletException
    {
        super.init( config );
        WebApplicationContext ctx =
            WebApplicationContextUtils.getRequiredWebApplicationContext( config.getServletContext() );

        repositoryRegistry = ctx.getBean( ArchivaRepositoryRegistry.class );

        // Optional beans — gracefully disabled when not configured
        proxyHandler = optionalBean( ctx, "repositoryProxyHandler#npm", RepositoryProxyHandler.class,
                                     "npm proxy handler; proxy fetch disabled" );
        servletAuth = optionalBean( ctx, "servletAuthenticator", ServletAuthenticator.class,
                                    "ServletAuthenticator; access control disabled" );
        httpAuth = optionalBean( ctx, "httpAuthenticator#basic", HttpAuthenticator.class,
                                 "HttpAuthenticator; access control disabled" );
        repositorySearch = optionalBean( ctx, "repositorySearch#maven", RepositorySearch.class,
                                         "RepositorySearch; search endpoint will return 501" );
    }

    private <T> T optionalBean( WebApplicationContext ctx, String name, Class<T> type, String warnMessage )
    {
        try
        {
            return ctx.getBean( name, type );
        }
        catch ( Exception e )
        {
            log.info( "No {} bean registered; {}", name, warnMessage );
            return null;
        }
    }

    // ---- routing --------------------------------------------------------

    @Override
    protected void doGet( HttpServletRequest req, HttpServletResponse resp ) throws ServletException, IOException
    {
        String[] parts = splitPath( req );
        if ( parts == null )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "Invalid npm registry URL" );
            return;
        }
        String repoId = parts[0];

        ManagedRepository repo = repositoryRegistry.getManagedRepository( repoId );
        if ( repo == null )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Repository not found: " + repoId );
            return;
        }

        // /{repoId}/-/v1/done?session=UUID — web login polling (no auth; UUID is the shared secret)
        if ( parts.length >= 4 && "-".equals( parts[1] ) && "v1".equals( parts[2] ) && "done".equals( parts[3] ) )
        {
            handleWebLoginDone( req, resp );
            return;
        }

        if ( !checkReadAccess( req, resp, repoId ) )
        {
            return;
        }

        // /{repoId}/-/v1/search?text=...
        if ( parts.length >= 4 && "-".equals( parts[1] ) && "v1".equals( parts[2] ) && "search".equals( parts[3] ) )
        {
            serveSearch( repo, req, resp );
            return;
        }

        // /{repoId}/-/package/{encodedName}/dist-tags
        if ( parts.length >= 5 && "-".equals( parts[1] ) && "package".equals( parts[2] )
            && "dist-tags".equals( parts[4] ) )
        {
            serveDistTags( repo, parts[3], resp );
            return;
        }

        NpmRequest npmReq = NpmRequest.parse( parts );
        if ( npmReq == null )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "Unrecognised npm path" );
            return;
        }

        if ( npmReq.tarball != null )
        {
            serveTarball( repo, npmReq, resp );
        }
        else if ( npmReq.version != null )
        {
            serveVersionMetadata( repo, npmReq, resp );
        }
        else
        {
            serveMetadata( repo, npmReq, resp );
        }
    }

    @Override
    protected void doPut( HttpServletRequest req, HttpServletResponse resp ) throws ServletException, IOException
    {
        String[] parts = splitPath( req );
        if ( parts == null )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "Invalid npm registry URL" );
            return;
        }
        String repoId = parts[0];

        ManagedRepository repo = repositoryRegistry.getManagedRepository( repoId );
        if ( repo == null )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Repository not found: " + repoId );
            return;
        }

        if ( !checkWriteAccess( req, resp, repoId ) )
        {
            return;
        }

        // /{repoId}/-/package/{encodedName}/dist-tags/{tag}
        if ( parts.length >= 6 && "-".equals( parts[1] ) && "package".equals( parts[2] )
            && "dist-tags".equals( parts[4] ) )
        {
            putDistTag( repo, parts[3], parts[5], req, resp );
            return;
        }

        NpmRequest npmReq = NpmRequest.parse( parts );
        if ( npmReq == null )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "Unrecognised npm path" );
            return;
        }

        publishPackage( repo, npmReq, req, resp );
    }

    @Override
    protected void doPost( HttpServletRequest req, HttpServletResponse resp ) throws ServletException, IOException
    {
        String[] parts = splitPath( req );
        if ( parts == null )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "Invalid npm registry URL" );
            return;
        }
        String repoId = parts[0];

        if ( repositoryRegistry.getManagedRepository( repoId ) == null )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Repository not found: " + repoId );
            return;
        }

        // POST /{repoId}/-/v1/login — npm web login initiation
        if ( parts.length >= 4 && "-".equals( parts[1] ) && "v1".equals( parts[2] )
            && "login".equals( parts[3] ) )
        {
            handleWebLoginInit( repoId, req, resp );
            return;
        }

        // POST /{repoId}/-/v1/session/{uuid}/confirm — browser confirms credentials
        if ( parts.length >= 6 && "-".equals( parts[1] ) && "v1".equals( parts[2] )
            && "session".equals( parts[3] ) && "confirm".equals( parts[5] ) )
        {
            handleWebLoginConfirm( repoId, parts[4], req, resp );
            return;
        }

        resp.sendError( HttpServletResponse.SC_METHOD_NOT_ALLOWED );
    }

    @Override
    protected void doDelete( HttpServletRequest req, HttpServletResponse resp ) throws ServletException, IOException
    {
        String[] parts = splitPath( req );
        if ( parts == null )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "Invalid npm registry URL" );
            return;
        }
        String repoId = parts[0];

        ManagedRepository repo = repositoryRegistry.getManagedRepository( repoId );
        if ( repo == null )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Repository not found: " + repoId );
            return;
        }

        if ( !checkDeleteAccess( req, resp, repoId ) )
        {
            return;
        }

        NpmRequest npmReq = NpmRequest.parse( parts );
        if ( npmReq == null )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "Unrecognised npm path" );
            return;
        }

        unpublishPackage( repo, npmReq, resp );
    }

    // ---- GET handlers ---------------------------------------------------

    private void serveMetadata( ManagedRepository repo, NpmRequest npmReq, HttpServletResponse resp )
        throws IOException
    {
        String metaPath = npmReq.packagePath() + "/package.json";
        StorageAsset asset = resolveWithProxy( repo, metaPath );

        if ( asset == null || !asset.exists() )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Package not found: " + npmReq.packageName() );
            return;
        }

        resp.setContentType( "application/json" );
        resp.setStatus( HttpServletResponse.SC_OK );
        streamAsset( asset, resp );
    }

    private void serveVersionMetadata( ManagedRepository repo, NpmRequest npmReq, HttpServletResponse resp )
        throws IOException
    {
        String metaPath = npmReq.packagePath() + "/package.json";
        StorageAsset asset = resolveWithProxy( repo, metaPath );

        if ( asset == null || !asset.exists() )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Package not found: " + npmReq.packageName() );
            return;
        }

        JsonNode fullDoc;
        try ( InputStream in = asset.getReadStream() )
        {
            fullDoc = mapper.readTree( in );
        }
        catch ( IOException e )
        {
            log.warn( "Could not parse package.json for {}: {}", npmReq.packageName(), e.getMessage() );
            resp.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
            return;
        }

        JsonNode versionNode = fullDoc.path( "versions" ).path( npmReq.version );
        if ( versionNode.isMissingNode() )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND,
                            "Version " + npmReq.version + " not found for " + npmReq.packageName() );
            return;
        }

        resp.setContentType( "application/json" );
        resp.setStatus( HttpServletResponse.SC_OK );
        mapper.writeValue( resp.getOutputStream(), versionNode );
    }

    private void serveTarball( ManagedRepository repo, NpmRequest npmReq, HttpServletResponse resp )
        throws IOException
    {
        String tarballPath = npmReq.packagePath() + "/-/" + npmReq.tarball;
        StorageAsset asset = resolveWithProxy( repo, tarballPath );

        if ( asset == null || !asset.exists() )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Tarball not found: " + npmReq.tarball );
            return;
        }

        resp.setContentType( "application/octet-stream" );
        resp.setContentLengthLong( asset.getSize() );
        resp.setStatus( HttpServletResponse.SC_OK );
        streamAsset( asset, resp );
    }

    /** Returns the dist-tags object from the stored package.json. */
    private void serveDistTags( ManagedRepository repo, String encodedName, HttpServletResponse resp )
        throws IOException
    {
        String packagePath = encodedName + "/package.json";
        StorageAsset asset = repo.getAsset( packagePath );
        if ( !asset.exists() )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Package not found: " + encodedName );
            return;
        }

        JsonNode doc;
        try ( InputStream in = asset.getReadStream() )
        {
            doc = mapper.readTree( in );
        }
        catch ( IOException e )
        {
            resp.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
            return;
        }

        JsonNode distTags = doc.path( "dist-tags" );
        if ( distTags.isMissingNode() )
        {
            distTags = mapper.createObjectNode();
        }
        resp.setContentType( "application/json" );
        resp.setStatus( HttpServletResponse.SC_OK );
        mapper.writeValue( resp.getOutputStream(), distTags );
    }

    /**
     * Searches packages using the Lucene index and returns the npm v1 search JSON format.
     * Returns 501 Not Implemented when no search backend is configured.
     */
    private void serveSearch( ManagedRepository repo, HttpServletRequest req, HttpServletResponse resp )
        throws IOException
    {
        if ( repositorySearch == null )
        {
            resp.sendError( HttpServletResponse.SC_NOT_IMPLEMENTED, "Search not available" );
            return;
        }

        String text = req.getParameter( "text" );
        if ( text == null || text.isEmpty() )
        {
            text = "*";
        }

        int size = DEFAULT_SEARCH_SIZE;
        String sizeParam = req.getParameter( "size" );
        if ( sizeParam != null )
        {
            try { size = Integer.parseInt( sizeParam ); } catch ( NumberFormatException ignored ) { }
        }

        SearchResults results;
        try
        {
            SearchResultLimits limits = new SearchResultLimits( 0 );
            limits.setPageSize( size );
            results = repositorySearch.search( "guest", Collections.singletonList( repo.getId() ),
                                               text, limits, Collections.emptyList() );
        }
        catch ( RepositorySearchException e )
        {
            log.warn( "Search failed: {}", e.getMessage() );
            resp.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Search error: " + e.getMessage() );
            return;
        }

        ObjectNode response = mapper.createObjectNode();
        ArrayNode objects = response.putArray( "objects" );
        for ( SearchResultHit hit : results.getHits() )
        {
            ObjectNode obj = objects.addObject();
            ObjectNode pkg = obj.putObject( "package" );
            String name = hit.getArtifactId();
            if ( hit.getGroupId() != null && hit.getGroupId().startsWith( "@" ) )
            {
                name = hit.getGroupId() + "/" + name;
            }
            pkg.put( "name", name != null ? name : "" );
            if ( !hit.getVersions().isEmpty() )
            {
                pkg.put( "version", hit.getVersions().get( hit.getVersions().size() - 1 ) );
            }
            obj.putObject( "score" ).put( "final", 1.0 );
            obj.put( "searchScore", 1.0 );
        }
        response.put( "total", results.getTotalHits() );
        response.put( "time", DateTimeFormatter.ISO_INSTANT.format( Instant.now() ) );

        resp.setContentType( "application/json" );
        resp.setStatus( HttpServletResponse.SC_OK );
        mapper.writeValue( resp.getOutputStream(), response );
    }

    // ---- Web login handlers ---------------------------------------------

    /**
     * Initiates the npm web login flow. Creates a pending session and returns
     * {@code loginUrl} (for the browser) and {@code doneUrl} (for npm to poll).
     */
    private void handleWebLoginInit( String repoId, HttpServletRequest req, HttpServletResponse resp )
        throws IOException
    {
        pendingSessions.values().removeIf( NpmPendingLogin::isExpired );

        String uuid = UUID.randomUUID().toString();
        pendingSessions.put( uuid, new NpmPendingLogin( uuid, repoId ) );

        String base = getBaseUrl( req );
        String loginUrl = base + "/#npm-login/" + uuid + "/" + repoId;
        String doneUrl  = base + "/npm/" + repoId + "/-/v1/done?session=" + uuid;

        ObjectNode body = mapper.createObjectNode();
        body.put( "loginUrl", loginUrl );
        body.put( "doneUrl", doneUrl );

        resp.setContentType( "application/json" );
        resp.setStatus( HttpServletResponse.SC_OK );
        mapper.writeValue( resp.getWriter(), body );
    }

    /**
     * npm polls this endpoint until the browser has confirmed the login.
     * Returns 202 while pending; 200 with {@code {"token":"..."}} once confirmed.
     */
    private void handleWebLoginDone( HttpServletRequest req, HttpServletResponse resp )
        throws IOException
    {
        String uuid = req.getParameter( "session" );
        if ( uuid == null || uuid.isEmpty() )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "Missing session parameter" );
            return;
        }

        NpmPendingLogin session = pendingSessions.get( uuid );
        if ( session == null || session.isExpired() )
        {
            pendingSessions.remove( uuid );
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Session not found or expired" );
            return;
        }

        if ( session.token == null )
        {
            resp.setContentType( "application/json" );
            resp.setStatus( HttpServletResponse.SC_ACCEPTED ); // 202 — still waiting
            resp.getWriter().write( "{\"message\":\"Waiting for browser login\"}" );
            return;
        }

        pendingSessions.remove( uuid );
        ObjectNode body = mapper.createObjectNode();
        body.put( "token", session.token );
        resp.setContentType( "application/json" );
        resp.setStatus( HttpServletResponse.SC_OK );
        mapper.writeValue( resp.getWriter(), body );
    }

    /**
     * Called by the browser after the user logs in. Verifies the supplied Basic-auth
     * credentials and marks the pending session so the npm poller receives a token.
     */
    private void handleWebLoginConfirm( String repoId, String uuid,
                                        HttpServletRequest req, HttpServletResponse resp )
        throws IOException
    {
        NpmPendingLogin session = pendingSessions.get( uuid );
        if ( session == null || session.isExpired() || !repoId.equals( session.repoId ) )
        {
            pendingSessions.remove( uuid );
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Session not found or expired" );
            return;
        }

        if ( !checkWriteAccess( req, resp, repoId ) )
        {
            return;
        }

        String authHeader = req.getHeader( "Authorization" );
        if ( authHeader == null || !authHeader.startsWith( "Basic " ) )
        {
            resp.sendError( HttpServletResponse.SC_UNAUTHORIZED, "Basic credentials required" );
            return;
        }

        session.token = authHeader.substring( 6 ).trim();

        resp.setContentType( "application/json" );
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.getWriter().write( "{\"ok\":true}" );
    }

    /** Derives the webapp base URL from the request or the {@code archiva.npm.external-url} property. */
    private String getBaseUrl( HttpServletRequest req )
    {
        String external = System.getProperty( "archiva.npm.external-url", "" ).trim();
        if ( !external.isEmpty() )
        {
            return external.replaceAll( "/+$", "" );
        }
        StringBuilder sb = new StringBuilder();
        sb.append( req.getScheme() ).append( "://" ).append( req.getServerName() );
        int port = req.getServerPort();
        boolean defaultPort = ( "http".equals( req.getScheme() ) && port == 80 )
            || ( "https".equals( req.getScheme() ) && port == 443 );
        if ( !defaultPort )
        {
            sb.append( ':' ).append( port );
        }
        String ctx = req.getContextPath();
        if ( ctx != null && !ctx.isEmpty() )
        {
            sb.append( ctx );
        }
        return sb.toString();
    }

    // ---- PUT handlers ---------------------------------------------------

    /**
     * Sets a dist-tag to a specific version. The request body is a quoted JSON string
     * containing the version, e.g. {@code "1.2.3"}.
     */
    private void putDistTag( ManagedRepository repo, String encodedName, String tag,
                             HttpServletRequest req, HttpServletResponse resp )
        throws IOException
    {
        String packagePath = encodedName + "/package.json";
        StorageAsset asset = repo.getAsset( packagePath );
        if ( !asset.exists() )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Package not found: " + encodedName );
            return;
        }

        JsonNode doc;
        try ( InputStream in = asset.getReadStream() )
        {
            doc = mapper.readTree( in );
        }
        catch ( IOException e )
        {
            resp.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
            return;
        }

        // Body is a JSON string like "1.2.3"
        String version;
        try
        {
            JsonNode bodyNode = mapper.readTree( req.getInputStream() );
            version = bodyNode.asText();
        }
        catch ( IOException e )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "Invalid request body" );
            return;
        }

        ObjectNode mutable = ( (ObjectNode) doc );
        if ( !mutable.has( "dist-tags" ) || !mutable.get( "dist-tags" ).isObject() )
        {
            mutable.putObject( "dist-tags" );
        }
        ( (ObjectNode) mutable.get( "dist-tags" ) ).put( tag, version );

        try ( OutputStream out = asset.getWriteStream( true ) )
        {
            mapper.writerWithDefaultPrettyPrinter().writeValue( out, mutable );
        }
        catch ( Exception e )
        {
            log.error( "Failed to update dist-tag for {}", encodedName, e );
            resp.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
            return;
        }

        resp.setContentType( "application/json" );
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.getWriter().write( "{\"ok\":true}" );
    }

    /**
     * Handles {@code npm publish}. The client sends a JSON body with package metadata
     * and base64-encoded tarballs under {@code _attachments}. Tarballs are verified
     * against {@code dist.shasum} (SHA-1) before being written to storage.
     */
    private void publishPackage( ManagedRepository repo, NpmRequest npmReq,
                                 HttpServletRequest req, HttpServletResponse resp )
        throws IOException
    {
        JsonNode body;
        try
        {
            body = mapper.readTree( req.getInputStream() );
        }
        catch ( IOException e )
        {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST, "Invalid publish request JSON" );
            return;
        }

        String packagePath = npmReq.packagePath();

        // Write/update package.json (strip _attachments before saving)
        ObjectNode meta = ( (ObjectNode) body ).deepCopy();
        meta.remove( "_attachments" );
        StorageAsset metaAsset = repo.getAsset( packagePath + "/package.json" );
        try ( OutputStream out = metaAsset.getWriteStream( true ) )
        {
            mapper.writerWithDefaultPrettyPrinter().writeValue( out, meta );
        }
        catch ( Exception e )
        {
            log.error( "Failed to write package.json for {}", npmReq.packageName(), e );
            resp.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
            return;
        }

        // Write and verify tarballs from _attachments
        JsonNode attachments = body.get( "_attachments" );
        if ( attachments != null )
        {
            Iterator<Map.Entry<String, JsonNode>> it = attachments.fields();
            while ( it.hasNext() )
            {
                Map.Entry<String, JsonNode> entry = it.next();
                String filename = entry.getKey();
                String b64Data = entry.getValue().path( "data" ).asText( null );
                if ( b64Data == null )
                {
                    continue;
                }

                byte[] tarballBytes;
                try
                {
                    tarballBytes = Base64.getDecoder().decode( b64Data );
                }
                catch ( IllegalArgumentException e )
                {
                    resp.sendError( HttpServletResponse.SC_BAD_REQUEST,
                                    "Invalid base64 attachment data for " + filename );
                    return;
                }

                // Verify SHA-1 checksum
                if ( !verifyChecksum( body, npmReq.name, filename, tarballBytes ) )
                {
                    resp.sendError( HttpServletResponse.SC_BAD_REQUEST,
                                    "Checksum mismatch for " + filename );
                    return;
                }

                StorageAsset tarballAsset = repo.getAsset( packagePath + "/-/" + filename );
                try ( OutputStream out = tarballAsset.getWriteStream( true ) )
                {
                    out.write( tarballBytes );
                }
                catch ( Exception e )
                {
                    log.error( "Failed to write tarball {} for {}", filename, npmReq.packageName(), e );
                    resp.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
                    return;
                }
                log.info( "Published {}/{} to repository {}", npmReq.packageName(), filename, npmReq.repoId );
            }
        }

        resp.setStatus( HttpServletResponse.SC_CREATED );
        resp.setContentType( "application/json" );
        resp.getWriter().write( "{\"ok\":true}" );
    }

    // ---- DELETE handler (unpublish) -------------------------------------

    /**
     * Removes the entire package directory from storage, supporting {@code npm unpublish}.
     * The npm client appends {@code /-rev/{rev}} to the URL; that suffix is stripped by
     * {@link NpmRequest#parse(String[])} and does not affect the deletion target.
     */
    private void unpublishPackage( ManagedRepository repo, NpmRequest npmReq, HttpServletResponse resp )
        throws IOException
    {
        String packagePath = npmReq.packagePath();
        StorageAsset asset = repo.getAsset( packagePath );

        if ( !asset.exists() )
        {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Package not found: " + npmReq.packageName() );
            return;
        }

        Path pkgDir = asset.getFilePath();
        if ( pkgDir == null )
        {
            resp.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                            "Cannot resolve filesystem path for " + packagePath );
            return;
        }

        try
        {
            deleteRecursive( pkgDir );
        }
        catch ( IOException e )
        {
            log.error( "Failed to delete package {}: {}", packagePath, e.getMessage() );
            resp.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to delete package" );
            return;
        }

        log.info( "Unpublished package {} from repository {}", npmReq.packageName(), npmReq.repoId );
        resp.setContentType( "application/json" );
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.getWriter().write( "{\"ok\":true}" );
    }

    // ---- Checksum verification ------------------------------------------

    /**
     * Verifies a tarball against {@code dist.shasum} (SHA-1 hex) recorded in the
     * publish body. Returns {@code true} when the checksum is absent (legacy clients)
     * or matches; {@code false} on mismatch.
     */
    private boolean verifyChecksum( JsonNode body, String packageName, String filename, byte[] tarballBytes )
    {
        String version = extractVersion( packageName, filename );
        if ( version == null )
        {
            return true; // can't determine version — skip
        }

        JsonNode dist = body.path( "versions" ).path( version ).path( "dist" );
        String expectedShasum = dist.path( "shasum" ).asText( null );
        if ( expectedShasum == null )
        {
            return true; // no checksum provided — skip
        }

        try
        {
            MessageDigest sha1 = MessageDigest.getInstance( "SHA-1" );
            byte[] digest = sha1.digest( tarballBytes );
            String actualShasum = bytesToHex( digest );
            if ( !actualShasum.equalsIgnoreCase( expectedShasum ) )
            {
                log.warn( "SHA-1 mismatch for {}: expected={} actual={}", filename, expectedShasum, actualShasum );
                return false;
            }

            // Also verify SRI sha512 integrity field when present
            String integrity = dist.path( "integrity" ).asText( null );
            if ( integrity != null && integrity.startsWith( "sha512-" ) )
            {
                MessageDigest sha512 = MessageDigest.getInstance( "SHA-512" );
                byte[] digest512 = sha512.digest( tarballBytes );
                String actualIntegrity = "sha512-" + Base64.getEncoder().encodeToString( digest512 );
                if ( !actualIntegrity.equals( integrity ) )
                {
                    log.warn( "SHA-512 integrity mismatch for {}: expected={} actual={}",
                              filename, integrity, actualIntegrity );
                    return false;
                }
            }
        }
        catch ( NoSuchAlgorithmException e )
        {
            log.warn( "SHA-1 not available — skipping checksum for {}", filename );
        }

        return true;
    }

    private static String bytesToHex( byte[] bytes )
    {
        StringBuilder sb = new StringBuilder( bytes.length * 2 );
        for ( byte b : bytes )
        {
            sb.append( String.format( "%02x", b & 0xFF ) );
        }
        return sb.toString();
    }

    private static String extractVersion( String packageName, String filename )
    {
        if ( !filename.endsWith( ".tgz" ) )
        {
            return null;
        }
        String withoutExt = filename.substring( 0, filename.length() - 4 );
        String prefix = packageName + "-";
        if ( !withoutExt.startsWith( prefix ) )
        {
            return null;
        }
        return withoutExt.substring( prefix.length() );
    }

    // ---- Proxy + streaming helpers --------------------------------------

    private StorageAsset resolveWithProxy( ManagedRepository repo, String path )
    {
        StorageAsset local = repo.getAsset( path );
        if ( local.exists() )
        {
            return local;
        }

        if ( proxyHandler == null || !proxyHandler.hasProxies( repo ) )
        {
            return local;
        }

        try
        {
            StorageAsset fetched = proxyHandler.fetchFromProxies( repo, path );
            return ( fetched != null && fetched.exists() ) ? fetched : local;
        }
        catch ( Exception e )
        {
            log.warn( "Proxy fetch failed for path {} in repo {}: {}", path, repo.getId(), e.getMessage() );
            return local;
        }
    }

    private void streamAsset( StorageAsset asset, HttpServletResponse resp ) throws IOException
    {
        try ( InputStream in = asset.getReadStream();
              OutputStream out = resp.getOutputStream() )
        {
            in.transferTo( out );
        }
        catch ( Exception e )
        {
            log.error( "Error streaming asset {}", asset.getPath(), e );
        }
    }

    // ---- Access control -------------------------------------------------

    /**
     * Returns {@code true} when the request is authorised for reading, or when
     * no authentication beans are configured (permissive mode).
     * Sends an HTTP 401 / 403 response and returns {@code false} on denial.
     */
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

    private boolean checkAccess( HttpServletRequest req, HttpServletResponse resp, String repoId, String operation )
        throws IOException
    {
        if ( servletAuth == null || httpAuth == null )
        {
            return true; // security not configured — allow
        }

        // Translate Bearer token (issued by web login) to Basic so Redback can verify it
        String authHeader = req.getHeader( "Authorization" );
        if ( authHeader != null && authHeader.startsWith( "Bearer " ) )
        {
            final String basicValue = "Basic " + authHeader.substring( 7 ).trim();
            req = new HttpServletRequestWrapper( req )
            {
                @Override
                public String getHeader( String name )
                {
                    return "Authorization".equalsIgnoreCase( name ) ? basicValue : super.getHeader( name );
                }

                @Override
                public Enumeration<String> getHeaders( String name )
                {
                    if ( "Authorization".equalsIgnoreCase( name ) )
                    {
                        return Collections.enumeration( Collections.singletonList( basicValue ) );
                    }
                    return super.getHeaders( name );
                }
            };
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
                // Try guest access for read operations
                String guest = "guest";
                if ( servletAuth.isAuthorized( guest, repoId, operation ) )
                {
                    return true;
                }
            }
        }
        catch ( AuthenticationException | AccountLockedException | MustChangePasswordException e )
        {
            resp.setHeader( "WWW-Authenticate", "Basic realm=\"Archiva NPM Repository\"" );
            resp.sendError( HttpServletResponse.SC_UNAUTHORIZED, e.getMessage() );
            return false;
        }
        catch ( UnauthorizedException e )
        {
            if ( ArchivaRoleConstants.OPERATION_READ_REPOSITORY.equals( operation ) )
            {
                resp.setHeader( "WWW-Authenticate", "Basic realm=\"Archiva NPM Repository\"" );
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
            resp.setHeader( "WWW-Authenticate", "Basic realm=\"Archiva NPM Repository\"" );
            resp.sendError( HttpServletResponse.SC_UNAUTHORIZED );
            return false;
        }

        resp.setHeader( "WWW-Authenticate", "Basic realm=\"Archiva NPM Repository\"" );
        resp.sendError( HttpServletResponse.SC_UNAUTHORIZED );
        return false;
    }

    // ---- Filesystem helpers ---------------------------------------------

    private static void deleteRecursive( Path path ) throws IOException
    {
        if ( Files.isDirectory( path ) )
        {
            try ( java.util.stream.Stream<Path> walk = Files.walk( path ) )
            {
                walk.sorted( java.util.Comparator.reverseOrder() )
                    .forEach( p -> {
                        try { Files.deleteIfExists( p ); }
                        catch ( IOException e ) { throw new java.io.UncheckedIOException( e ); }
                    } );
            }
        }
        else
        {
            Files.deleteIfExists( path );
        }
    }

    // ---- URL parsing ----------------------------------------------------

    /**
     * Splits the servlet pathInfo into an array of path segments, with the leading
     * slash removed. Returns {@code null} when the pathInfo is missing or too short.
     */
    private static String[] splitPath( HttpServletRequest req )
    {
        String pathInfo = req.getPathInfo();
        if ( pathInfo == null || pathInfo.length() < 2 )
        {
            return null;
        }
        String[] parts = pathInfo.substring( 1 ).split( "/" );
        if ( parts.length < 2 )
        {
            return null;
        }
        return parts;
    }

    /**
     * Parsed npm request path.
     * URL shape: {@code /{repoId}[/@scope]/{name}[/{version}|/-/{tarball}][/-rev/{rev}]}
     */
    static class NpmRequest
    {
        final String repoId;
        final String scope;    // null for unscoped packages
        final String name;
        final String version;  // null unless version-specific metadata request
        final String tarball;  // null for metadata requests

        NpmRequest( String repoId, String scope, String name, String version, String tarball )
        {
            this.repoId = repoId;
            this.scope = scope;
            this.name = name;
            this.version = version;
            this.tarball = tarball;
        }

        /** Storage-relative path prefix for this package. */
        String packagePath()
        {
            return scope != null ? scope + "/" + name : name;
        }

        String packageName()
        {
            return scope != null ? scope + "/" + name : name;
        }

        /**
         * Parse path segments produced by {@link NpmRegistryServlet#splitPath}.
         * Handles:
         * <ul>
         *   <li>{@code [repoId, name]}</li>
         *   <li>{@code [repoId, @scope, name]}</li>
         *   <li>{@code [repoId, name, version]}</li>
         *   <li>{@code [repoId, @scope, name, version]}</li>
         *   <li>{@code [repoId, name, -, tarball]}</li>
         *   <li>{@code [repoId, @scope, name, -, tarball]}</li>
         *   <li>{@code [repoId, name, -rev, rev]} — DELETE; -rev ignored</li>
         *   <li>{@code [repoId, @scope, name, -rev, rev]} — DELETE; -rev ignored</li>
         * </ul>
         * Returns {@code null} when the path cannot be mapped.
         */
        static NpmRequest parse( String[] parts )
        {
            if ( parts == null || parts.length < 2 )
            {
                return null;
            }

            String repoId = parts[0];
            String scope = null;
            String name;
            String version = null;
            String tarball = null;

            int idx = 1;
            if ( parts[idx].startsWith( "@" ) )
            {
                scope = parts[idx];
                idx++;
            }
            if ( idx >= parts.length )
            {
                return null;
            }
            name = parts[idx];
            idx++;

            if ( idx < parts.length )
            {
                String next = parts[idx];
                if ( "-".equals( next ) && idx + 1 < parts.length )
                {
                    // tarball: /-/{filename}
                    tarball = parts[idx + 1];
                }
                else if ( "-rev".equals( next ) )
                {
                    // DELETE /-rev/{rev} — ignore the revision
                }
                else
                {
                    // version-specific metadata
                    version = next;
                }
            }

            return new NpmRequest( repoId, scope, name, version, tarball );
        }
    }
}
