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

import org.apache.archiva.repository.RepositoryRequestInfo;
import org.apache.archiva.repository.UnsupportedFeatureException;
import org.apache.archiva.repository.content.ItemSelector;
import org.apache.archiva.repository.content.LayoutException;
import org.apache.archiva.repository.features.RepositoryFeature;

/**
 * Maps npm registry request paths to storage paths.
 * The npm path layout is: {@code {name}/-/{name}-{version}.tgz} for tarballs,
 * or just {@code {name}} for package metadata requests.
 */
public class NpmRepositoryRequestInfo implements RepositoryRequestInfo
{
    private final NpmManagedRepository repository;

    public NpmRepositoryRequestInfo( NpmManagedRepository repository )
    {
        this.repository = repository;
    }

    @Override
    public String toNativePath( String requestPath ) throws LayoutException
    {
        return requestPath;
    }

    @Override
    public ItemSelector toItemSelector( String requestPath ) throws LayoutException
    {
        // TODO: implement full npm path parsing in a follow-up
        throw new LayoutException( "npm path parsing not yet implemented: " + requestPath );
    }

    @Override
    public boolean isMetadata( String requestPath )
    {
        return !requestPath.endsWith( ".tgz" );
    }

    @Override
    public boolean isArchetypeCatalog( String requestPath )
    {
        return false;
    }

    @Override
    public boolean isSupportFile( String requestPath )
    {
        return false;
    }

    @Override
    public boolean isMetadataSupportFile( String requestPath )
    {
        return false;
    }

    @Override
    public String getLayout( String requestPath )
    {
        return NpmManagedRepository.LAYOUT;
    }

    @Override
    public <T extends RepositoryFeature<T>> RepositoryFeature<T> getFeature( Class<T> clazz )
        throws UnsupportedFeatureException
    {
        throw new UnsupportedFeatureException();
    }

    @Override
    public <T extends RepositoryFeature<T>> boolean supportsFeature( Class<T> clazz )
    {
        return false;
    }
}
