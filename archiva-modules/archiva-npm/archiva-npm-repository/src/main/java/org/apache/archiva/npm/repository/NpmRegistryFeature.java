package org.apache.archiva.npm.repository;

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

import org.apache.archiva.repository.features.RepositoryFeature;

/**
 * NPM-specific repository feature that carries the upstream registry URL and
 * optional authentication token.
 *
 * <p>Both {@link NpmManagedRepository} and {@link NpmRemoteRepository} support this feature:
 * <ul>
 *   <li>On a {@link NpmRemoteRepository} the {@code registryUrl} is kept in sync with
 *       the repository location set by the provider.</li>
 *   <li>On a {@link NpmManagedRepository} it defaults to
 *       {@value #DEFAULT_REGISTRY_URL} and represents the registry that the proxy
 *       handler should fall back to when no explicit proxy connector is configured.</li>
 * </ul>
 */
public class NpmRegistryFeature implements RepositoryFeature<NpmRegistryFeature>
{
    public static final String DEFAULT_REGISTRY_URL = "https://registry.npmjs.org";

    private String registryUrl;
    private String authToken;

    public NpmRegistryFeature()
    {
        this.registryUrl = DEFAULT_REGISTRY_URL;
    }

    public NpmRegistryFeature( String registryUrl )
    {
        this.registryUrl = ( registryUrl != null && !registryUrl.isEmpty() )
            ? registryUrl : DEFAULT_REGISTRY_URL;
    }

    /**
     * Returns the upstream npm registry base URL, e.g. {@code https://registry.npmjs.org}.
     */
    public String getRegistryUrl()
    {
        return registryUrl;
    }

    /**
     * Sets the upstream npm registry base URL.
     * Passing {@code null} or an empty string resets the value to {@value #DEFAULT_REGISTRY_URL}.
     */
    public void setRegistryUrl( String registryUrl )
    {
        this.registryUrl = ( registryUrl != null && !registryUrl.isEmpty() )
            ? registryUrl : DEFAULT_REGISTRY_URL;
    }

    /**
     * Returns the Bearer auth token used when talking to the upstream registry, or
     * {@code null} if no token is configured (anonymous access).
     */
    public String getAuthToken()
    {
        return authToken;
    }

    public void setAuthToken( String authToken )
    {
        this.authToken = authToken;
    }

    /** Returns {@code true} if a non-empty auth token has been configured. */
    public boolean hasAuthToken()
    {
        return authToken != null && !authToken.isEmpty();
    }

    @Override
    public String toString()
    {
        return "NpmRegistryFeature{registryUrl=" + registryUrl
            + ", hasAuthToken=" + hasAuthToken() + "}";
    }
}
