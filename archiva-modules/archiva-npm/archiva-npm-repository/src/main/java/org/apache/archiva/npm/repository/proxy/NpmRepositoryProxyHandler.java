package org.apache.archiva.npm.repository.proxy;

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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.archiva.proxy.base.DefaultRepositoryProxyHandler;
import org.apache.archiva.proxy.base.NotFoundException;
import org.apache.archiva.proxy.base.NotModifiedException;
import org.apache.archiva.proxy.base.ProxyException;
import org.apache.archiva.proxy.model.NetworkProxy;
import org.apache.archiva.proxy.model.ProxyConnector;
import org.apache.archiva.proxy.model.RepositoryProxyHandler;
import org.apache.archiva.repository.ManagedRepository;
import org.apache.archiva.repository.RemoteRepository;
import org.apache.archiva.repository.RepositoryCredentials;
import org.apache.archiva.repository.RepositoryType;
import org.apache.archiva.repository.base.PasswordCredentials;
import org.apache.archiva.repository.storage.StorageAsset;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Proxy handler for NPM repositories. Fetches packages from an upstream npm registry
 * (e.g. {@code https://registry.npmjs.org}) using plain HTTP, caches them locally,
 * and rewrites {@code dist.tarball} URLs in the cached metadata so clients pull
 * tarballs through Archiva rather than directly from the upstream registry.
 */
@Service( "repositoryProxyHandler#npm" )
public class NpmRepositoryProxyHandler extends DefaultRepositoryProxyHandler
{
    private static final Logger log = LoggerFactory.getLogger( NpmRepositoryProxyHandler.class );

    private static final List<RepositoryType> SUPPORTED_TYPES = new ArrayList<>();

    static
    {
        SUPPORTED_TYPES.add( RepositoryType.NPM );
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<RepositoryType> supports()
    {
        return SUPPORTED_TYPES;
    }

    /**
     * Downloads {@code url} into {@code tmpResource} using Apache HttpClient.
     * Checksum files are skipped — npm packages carry integrity data inside the
     * {@code package.json} metadata ({@code dist.shasum} / {@code dist.integrity}).
     *
     * <p>After a successful metadata ({@code package.json}) download the tarball
     * URLs inside the document are rewritten to point back to this Archiva instance.
     */
    @Override
    protected void transferResources( ProxyConnector connector, RemoteRepository remoteRepository,
                                      StorageAsset tmpResource, StorageAsset[] checksumFiles,
                                      String url, String remotePath,
                                      StorageAsset resource, Path workingDirectory,
                                      ManagedRepository repository )
        throws ProxyException, NotModifiedException
    {
        CloseableHttpClient httpClient = buildHttpClient( connector, remoteRepository );
        try
        {
            // The base class builds the URL as remoteRepo.location + storagePath, e.g.
            // https://registry.npmjs.org/@scope/name/package.json — but the npm registry
            // API endpoint is just /@scope/name (no /package.json suffix).
            String apiUrl = remotePath.endsWith( "/package.json" )
                ? url.substring( 0, url.length() - "/package.json".length() )
                : url;
            HttpGet request = new HttpGet( apiUrl );
            request.setHeader( HttpHeaders.ACCEPT, "application/json, application/octet-stream, */*" );
            request.setHeader( "npm-session", "archiva-proxy" );

            // Conditional GET: skip if not modified
            if ( resource.exists() )
            {
                long lastModified = resource.getModificationTime().toEpochMilli();
                request.setHeader( HttpHeaders.IF_MODIFIED_SINCE,
                    new java.util.Date( lastModified ).toString() );
            }

            log.debug( "NPM proxy GET {}", apiUrl );
            HttpResponse response = httpClient.execute( request );
            int status = response.getStatusLine().getStatusCode();

            if ( status == HttpStatus.SC_NOT_MODIFIED )
            {
                throw new NotModifiedException( "Not modified: " + url );
            }
            if ( status == HttpStatus.SC_NOT_FOUND )
            {
                throw new NotFoundException( "Not found on upstream registry: " + url );
            }
            if ( status < 200 || status >= 300 )
            {
                throw new ProxyException( "Unexpected HTTP " + status + " fetching " + url );
            }

            HttpEntity entity = response.getEntity();
            if ( entity == null )
            {
                throw new ProxyException( "Empty response body from upstream for " + url );
            }

            // Write to tmp file, then rewrite tarball URLs if this is a metadata response
            boolean isMetadata = remotePath.endsWith( "package.json" ) || !remotePath.contains( "/-/" );
            try ( InputStream in = entity.getContent();
                  OutputStream out = tmpResource.getWriteStream( true ) )
            {
                if ( isMetadata )
                {
                    byte[] body = in.readAllBytes();
                    byte[] rewritten = rewriteTarballUrls( body, connector, remoteRepository, repository );
                    out.write( rewritten );
                }
                else
                {
                    in.transferTo( out );
                }
            }
            catch ( Exception e )
            {
                throw new ProxyException( "Failed writing downloaded content for " + url + ": " + e.getMessage(), e );
            }
        }
        catch ( IOException e )
        {
            throw new ProxyException( "HTTP error fetching " + url + ": " + e.getMessage(), e );
        }
        finally
        {
            try { httpClient.close(); } catch ( IOException ignored ) { }
        }
    }

    // -------------------------------------------------------------------------
    // Tarball URL rewriting
    // -------------------------------------------------------------------------

    /**
     * Rewrites all {@code dist.tarball} URLs in a npm package metadata document
     * so they point to this Archiva proxy rather than the upstream registry.
     *
     * <p>Input tarball URL shape:
     * {@code https://registry.npmjs.org/{name}/-/{name}-{version}.tgz}
     *
     * <p>Rewritten shape:
     * {@code http(s)://<archiva-host>/npm/{managedRepoId}/{name}/-/{name}-{version}.tgz}
     */
    private byte[] rewriteTarballUrls( byte[] jsonBody, ProxyConnector connector,
                                       RemoteRepository remoteRepository,
                                       ManagedRepository managedRepository )
    {
        try
        {
            JsonNode root = mapper.readTree( jsonBody );
            if ( !root.isObject() )
            {
                return jsonBody;
            }

            String localBase = buildLocalBase( managedRepository );
            if ( localBase == null )
            {
                log.debug( "archiva.npm.external-url not set; skipping tarball URL rewriting" );
                return jsonBody;
            }

            String upstreamBase = remoteRepository.getLocation().toString();
            if ( !upstreamBase.endsWith( "/" ) )
            {
                upstreamBase = upstreamBase + "/";
            }

            boolean modified = rewriteVersions( (ObjectNode) root, upstreamBase, localBase );

            return modified ? mapper.writeValueAsBytes( root ) : jsonBody;
        }
        catch ( Exception e )
        {
            log.warn( "Could not rewrite tarball URLs in metadata: {}", e.getMessage() );
            return jsonBody;
        }
    }

    private boolean rewriteVersions( ObjectNode root, String upstreamBase, String localBase )
    {
        JsonNode versions = root.get( "versions" );
        if ( versions == null || !versions.isObject() )
        {
            return false;
        }

        boolean modified = false;
        Iterator<Map.Entry<String, JsonNode>> it = versions.fields();
        while ( it.hasNext() )
        {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode versionNode = entry.getValue();
            if ( !versionNode.isObject() )
            {
                continue;
            }
            JsonNode dist = versionNode.get( "dist" );
            if ( dist == null || !dist.isObject() )
            {
                continue;
            }
            JsonNode tarball = dist.get( "tarball" );
            if ( tarball != null && tarball.isTextual() )
            {
                String original = tarball.asText();
                if ( original.startsWith( upstreamBase ) )
                {
                    String rewritten = localBase + original.substring( upstreamBase.length() );
                    ( (ObjectNode) dist ).put( "tarball", rewritten );
                    log.debug( "Rewrote tarball URL {} -> {}", original, rewritten );
                    modified = true;
                }
            }
        }
        return modified;
    }

    /**
     * Builds the local base URL for the managed repository.
     * Reads {@code archiva.npm.external-url} system property (e.g.
     * {@code http://localhost:8080/archiva}) to construct the absolute
     * tarball URL prefix. Returns {@code null} when the property is not set,
     * which causes the caller to skip URL rewriting.
     */
    private String buildLocalBase( ManagedRepository repo )
    {
        String externalUrl = System.getProperty( "archiva.npm.external-url", "" ).trim();
        if ( externalUrl.isEmpty() )
        {
            return null;
        }
        if ( !externalUrl.endsWith( "/" ) )
        {
            externalUrl = externalUrl + "/";
        }
        return externalUrl + "npm/" + repo.getId() + "/";
    }

    @Override
    public void addNetworkproxy( String id, NetworkProxy networkProxy )
    {
        // no-op; network proxies are read via getNetworkProxy(id) from the parent class
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public <T extends RepositoryProxyHandler> T getHandler( Class<T> clazz ) throws IllegalArgumentException
    {
        if ( clazz.isAssignableFrom( this.getClass() ) )
        {
            return (T) this;
        }
        throw new IllegalArgumentException( "This Proxy Handler is not a subclass of " + clazz );
    }

    // -------------------------------------------------------------------------
    // HttpClient construction
    // -------------------------------------------------------------------------

    private CloseableHttpClient buildHttpClient( ProxyConnector connector, RemoteRepository remoteRepository )
    {
        HttpClientBuilder builder = HttpClients.custom();

        // Timeout from remote repository config
        int timeoutMs = (int) remoteRepository.getTimeout().toMillis();
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout( timeoutMs )
            .setSocketTimeout( timeoutMs )
            .build();
        builder.setDefaultRequestConfig( requestConfig );

        // Authentication for the upstream registry
        RepositoryCredentials repoCreds = remoteRepository.getLoginCredentials();
        if ( repoCreds instanceof PasswordCredentials )
        {
            PasswordCredentials pc = (PasswordCredentials) repoCreds;
            if ( pc.getUsername() != null && pc.getPassword() != null )
            {
                CredentialsProvider cp = new BasicCredentialsProvider();
                cp.setCredentials( AuthScope.ANY,
                    new UsernamePasswordCredentials( pc.getUsername(), new String( pc.getPassword() ) ) );
                builder.setDefaultCredentialsProvider( cp );
            }
        }

        // Network (HTTP) proxy
        String proxyId = connector.getProxyId();
        if ( proxyId != null && !proxyId.isEmpty() )
        {
            NetworkProxy networkProxy = getNetworkProxy( proxyId );
            if ( networkProxy != null )
            {
                HttpHost proxyHost = new HttpHost( networkProxy.getHost(), networkProxy.getPort(),
                    networkProxy.getProtocol() );
                builder.setProxy( proxyHost );

                if ( networkProxy.getUsername() != null && networkProxy.getPassword() != null )
                {
                    CredentialsProvider cp = new BasicCredentialsProvider();
                    cp.setCredentials(
                        new AuthScope( networkProxy.getHost(), networkProxy.getPort() ),
                        new UsernamePasswordCredentials( networkProxy.getUsername(),
                            new String( networkProxy.getPassword() ) ) );
                    builder.setDefaultCredentialsProvider( cp );
                }
            }
        }

        return builder.build();
    }
}
