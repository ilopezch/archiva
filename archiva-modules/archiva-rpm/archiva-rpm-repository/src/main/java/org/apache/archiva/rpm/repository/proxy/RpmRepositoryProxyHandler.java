package org.apache.archiva.rpm.repository.proxy;

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
import java.util.List;

/**
 * Proxy handler for RPM (yum/dnf) repositories. Fetches packages and repodata
 * from an upstream mirror (e.g. a CentOS, Fedora, or EPEL mirror) using plain
 * HTTP, then caches them locally so yum/dnf clients can pull through Archiva.
 *
 * <p>Unlike the NPM handler, no metadata rewriting is required — yum clients
 * use the local Archiva URL directly via the proxy connector.
 */
@Service( "repositoryProxyHandler#rpm" )
public class RpmRepositoryProxyHandler extends DefaultRepositoryProxyHandler
{
    private static final Logger log = LoggerFactory.getLogger( RpmRepositoryProxyHandler.class );

    private static final List<RepositoryType> SUPPORTED_TYPES = new ArrayList<>();

    static
    {
        SUPPORTED_TYPES.add( RepositoryType.RPM );
    }

    @Override
    public List<RepositoryType> supports()
    {
        return SUPPORTED_TYPES;
    }

    /**
     * Downloads {@code url} into {@code tmpResource} using Apache HttpClient.
     * Supports conditional GET (If-Modified-Since) to avoid redundant transfers.
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
            HttpGet request = new HttpGet( url );
            request.setHeader( HttpHeaders.ACCEPT, "application/octet-stream, application/xml, */*" );

            if ( resource.exists() )
            {
                long lastModified = resource.getModificationTime().toEpochMilli();
                request.setHeader( HttpHeaders.IF_MODIFIED_SINCE,
                    new java.util.Date( lastModified ).toString() );
            }

            log.debug( "RPM proxy GET {}", url );
            HttpResponse response = httpClient.execute( request );
            int status = response.getStatusLine().getStatusCode();

            if ( status == HttpStatus.SC_NOT_MODIFIED )
            {
                throw new NotModifiedException( "Not modified: " + url );
            }
            if ( status == HttpStatus.SC_NOT_FOUND )
            {
                throw new NotFoundException( "Not found on upstream mirror: " + url );
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

            try ( InputStream in = entity.getContent();
                  OutputStream out = tmpResource.getWriteStream( true ) )
            {
                in.transferTo( out );
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

    private CloseableHttpClient buildHttpClient( ProxyConnector connector, RemoteRepository remoteRepository )
    {
        HttpClientBuilder builder = HttpClients.custom();

        int timeoutMs = (int) remoteRepository.getTimeout().toMillis();
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout( timeoutMs )
            .setSocketTimeout( timeoutMs )
            .build();
        builder.setDefaultRequestConfig( requestConfig );

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
