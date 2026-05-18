package org.apache.archiva.rpm.repository;

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
 * Maps yum/dnf request paths to RPM storage paths.
 * Repodata paths and RPM package paths are returned unchanged (they map 1:1 to storage).
 */
public class RpmRepositoryRequestInfo implements RepositoryRequestInfo
{
    private final RpmManagedRepository repository;

    public RpmRepositoryRequestInfo( RpmManagedRepository repository )
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
        // TODO: implement full RPM path parsing in a follow-up
        throw new LayoutException( "RPM path parsing not yet implemented: " + requestPath );
    }

    @Override
    public boolean isMetadata( String requestPath )
    {
        return requestPath.contains( "repodata/" );
    }

    @Override
    public boolean isArchetypeCatalog( String requestPath )
    {
        return false;
    }

    @Override
    public boolean isSupportFile( String requestPath )
    {
        return requestPath.endsWith( ".asc" ) || requestPath.endsWith( ".gpg" );
    }

    @Override
    public boolean isMetadataSupportFile( String requestPath )
    {
        return requestPath.endsWith( "repomd.xml.asc" );
    }

    @Override
    public String getLayout( String requestPath )
    {
        return RpmManagedRepository.LAYOUT;
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
