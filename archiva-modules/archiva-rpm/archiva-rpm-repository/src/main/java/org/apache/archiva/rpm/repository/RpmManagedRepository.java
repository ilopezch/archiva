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

import org.apache.archiva.repository.ReleaseScheme;
import org.apache.archiva.repository.RepositoryCapabilities;
import org.apache.archiva.repository.RepositoryRequestInfo;
import org.apache.archiva.repository.RepositoryState;
import org.apache.archiva.repository.RepositoryType;
import org.apache.archiva.repository.StandardCapabilities;
import org.apache.archiva.repository.UnsupportedFeatureException;
import org.apache.archiva.repository.base.managed.AbstractManagedRepository;
import org.apache.archiva.repository.features.ArtifactCleanupFeature;
import org.apache.archiva.repository.features.RepositoryFeature;
import org.apache.archiva.repository.storage.fs.FilesystemStorage;

/**
 * RPM managed repository. Stores RPM packages on the local filesystem using the
 * standard yum layout: {@code RPMS/{arch}/{name}-{version}-{release}.{arch}.rpm}.
 * Serves yum/dnf clients via {@code repodata/repomd.xml} and GPG-signed metadata.
 */
public class RpmManagedRepository extends AbstractManagedRepository
{
    public static final String LAYOUT = "rpm-default";

    private final ArtifactCleanupFeature artifactCleanupFeature = new ArtifactCleanupFeature();

    private static final RepositoryCapabilities CAPABILITIES = new StandardCapabilities(
        new ReleaseScheme[]{ ReleaseScheme.RELEASE },
        new String[]{ LAYOUT },
        new String[]{},
        new String[]{ ArtifactCleanupFeature.class.getName() },
        false,
        true,
        false,
        false,
        false
    );

    public RpmManagedRepository( String id, String name, FilesystemStorage storage )
    {
        super( RepositoryType.RPM, id, name, storage );
        setLocation( storage.getRoot().getFilePath().toUri() );
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
        if ( ArtifactCleanupFeature.class.equals( clazz ) )
        {
            return (T) artifactCleanupFeature;
        }
        throw new UnsupportedFeatureException();
    }

    @Override
    public <T extends RepositoryFeature<T>> boolean supportsFeature( Class<T> clazz )
    {
        return ArtifactCleanupFeature.class.equals( clazz );
    }

    @Override
    public boolean hasIndex()
    {
        return false;
    }

    @Override
    public RepositoryRequestInfo getRequestInfo()
    {
        return new RpmRepositoryRequestInfo( this );
    }
}
