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

import org.apache.archiva.repository.ReleaseScheme;
import org.apache.archiva.repository.RepositoryCapabilities;
import org.apache.archiva.repository.RepositoryState;
import org.apache.archiva.repository.RepositoryType;
import org.apache.archiva.repository.StandardCapabilities;
import org.apache.archiva.repository.UnsupportedFeatureException;
import org.apache.archiva.repository.base.remote.AbstractRemoteRepository;
import org.apache.archiva.repository.features.RepositoryFeature;
import org.apache.archiva.repository.storage.fs.FilesystemStorage;

import java.net.URI;

/**
 * NPM remote repository (proxy cache). Fetches packages from an upstream npm registry
 * such as {@code https://registry.npmjs.org} and caches them locally.
 */
public class NpmRemoteRepository extends AbstractRemoteRepository
{
    private static final RepositoryCapabilities CAPABILITIES = new StandardCapabilities(
        new ReleaseScheme[]{ ReleaseScheme.RELEASE },
        new String[]{ NpmManagedRepository.LAYOUT },
        new String[]{},
        new String[]{ NpmRegistryFeature.class.getName() },
        false,
        true,
        false,
        false,
        false
    );

    private final NpmRegistryFeature registryFeature = new NpmRegistryFeature();

    public NpmRemoteRepository( String id, String name, FilesystemStorage storage )
    {
        super( RepositoryType.NPM, id, name, storage );
        setLastState( RepositoryState.CREATED );
    }

    @Override
    public RepositoryCapabilities getCapabilities()
    {
        return CAPABILITIES;
    }

    @SuppressWarnings( "unchecked" )
    @Override
    public <T extends RepositoryFeature<T>> T getFeature( Class<T> clazz ) throws UnsupportedFeatureException
    {
        if ( NpmRegistryFeature.class.equals( clazz ) )
        {
            return (T) registryFeature;
        }
        throw new UnsupportedFeatureException();
    }

    @Override
    public <T extends RepositoryFeature<T>> boolean supportsFeature( Class<T> clazz )
    {
        return NpmRegistryFeature.class.equals( clazz );
    }

    /**
     * Keeps the registry feature URL in sync with the repository location URI.
     * Called by the provider after {@code setLocation()}.
     */
    public void syncRegistryUrl( URI location )
    {
        if ( location != null )
        {
            registryFeature.setRegistryUrl( location.toString() );
        }
    }

    @Override
    public boolean hasIndex()
    {
        return false;
    }
}
