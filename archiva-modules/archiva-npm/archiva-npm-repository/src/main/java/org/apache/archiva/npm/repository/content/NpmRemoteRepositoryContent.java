package org.apache.archiva.npm.repository.content;

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

import org.apache.archiva.repository.RemoteRepository;
import org.apache.archiva.repository.RemoteRepositoryContent;
import org.apache.archiva.repository.content.ItemSelector;
import org.apache.archiva.repository.content.LayoutException;

/**
 * NPM remote repository content. Translates npm package selectors to upstream registry paths.
 */
public class NpmRemoteRepositoryContent implements RemoteRepositoryContent
{
    private RemoteRepository repository;

    @Override
    public String getId()
    {
        return repository.getId();
    }

    @Override
    public RemoteRepository getRepository()
    {
        return repository;
    }

    @Override
    public void setRepository( RemoteRepository repository )
    {
        this.repository = repository;
    }

    @Override
    public String toPath( ItemSelector selector )
    {
        String scope = selector.getNamespace();
        String name = selector.getProjectId();
        String version = selector.getVersion();

        String base = ( scope != null && !scope.isEmpty() ) ? scope + "/" + name : name;
        if ( version != null && !version.isEmpty() )
        {
            return base + "/-/" + name + "-" + version + ".tgz";
        }
        return base;
    }

    @Override
    public ItemSelector toItemSelector( String path ) throws LayoutException
    {
        throw new LayoutException( "npm path parsing not yet implemented: " + path );
    }
}
