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
import org.apache.archiva.repository.base.group.AbstractRepositoryGroup;
import org.apache.archiva.repository.storage.fs.FilesystemStorage;

/**
 * Groups one or more NPM managed repositories under a single virtual registry endpoint.
 */
public class NpmRepositoryGroup extends AbstractRepositoryGroup
{
    private static final RepositoryCapabilities CAPABILITIES = new StandardCapabilities(
        new ReleaseScheme[]{ ReleaseScheme.RELEASE },
        new String[]{ NpmManagedRepository.LAYOUT },
        new String[]{},
        new String[]{},
        false,
        false,
        false,
        false,
        false
    );

    public NpmRepositoryGroup( String id, String name, FilesystemStorage storage )
    {
        super( RepositoryType.NPM, id, name, storage );
        setCapabilities( CAPABILITIES );
        setLastState( RepositoryState.CREATED );
    }

    @Override
    public boolean hasIndex()
    {
        return false;
    }
}
