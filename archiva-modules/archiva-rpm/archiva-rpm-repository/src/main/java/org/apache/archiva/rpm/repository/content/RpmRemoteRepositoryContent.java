package org.apache.archiva.rpm.repository.content;

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
import org.apache.archiva.repository.content.base.ArchivaItemSelector;

/**
 * RPM remote repository content. Translates RPM package selectors to upstream yum/dnf mirror paths.
 * The path structure mirrors the standard yum layout used by managed repositories.
 */
public class RpmRemoteRepositoryContent implements RemoteRepositoryContent
{
    private RemoteRepository repository;

    public RpmRemoteRepositoryContent( RemoteRepository repository )
    {
        this.repository = repository;
    }

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
        String arch = selector.getNamespace();
        String name = selector.getProjectId();
        String version = selector.getVersion();

        if ( "src".equals( arch ) )
        {
            if ( version != null && !version.isEmpty() )
            {
                return "SRPMS/" + name + "-" + version + ".src.rpm";
            }
            return "SRPMS";
        }

        if ( arch != null && !arch.isEmpty() )
        {
            if ( name != null && !name.isEmpty() && version != null && !version.isEmpty() )
            {
                return "RPMS/" + arch + "/" + name + "-" + version + "." + arch + ".rpm";
            }
            return "RPMS/" + arch;
        }

        return "RPMS";
    }

    @Override
    public ItemSelector toItemSelector( String path ) throws LayoutException
    {
        if ( path == null || path.isEmpty() )
        {
            throw new LayoutException( "Empty RPM path" );
        }
        String p = path.startsWith( "/" ) ? path.substring( 1 ) : path;

        if ( p.startsWith( "SRPMS/" ) )
        {
            String filename = p.substring( "SRPMS/".length() );
            return ArchivaItemSelector.builder()
                .withNamespace( "src" )
                .withProjectId( extractName( filename, ".src.rpm" ) )
                .withVersion( extractVersionRelease( filename, ".src.rpm" ) )
                .build();
        }

        if ( p.startsWith( "RPMS/" ) )
        {
            String rest = p.substring( "RPMS/".length() );
            int slash = rest.indexOf( '/' );
            if ( slash < 0 )
            {
                return ArchivaItemSelector.builder().withNamespace( rest ).build();
            }
            String arch = rest.substring( 0, slash );
            String filename = rest.substring( slash + 1 );
            String suffix = "." + arch + ".rpm";
            return ArchivaItemSelector.builder()
                .withNamespace( arch )
                .withProjectId( extractName( filename, suffix ) )
                .withVersion( extractVersionRelease( filename, suffix ) )
                .build();
        }

        throw new LayoutException( "Unrecognised RPM path: " + path );
    }

    private String extractName( String filename, String suffix )
    {
        String base = filename.endsWith( suffix )
            ? filename.substring( 0, filename.length() - suffix.length() )
            : filename;
        int last = base.lastIndexOf( '-' );
        if ( last > 0 )
        {
            base = base.substring( 0, last );
        }
        int second = base.lastIndexOf( '-' );
        return second > 0 ? base.substring( 0, second ) : base;
    }

    private String extractVersionRelease( String filename, String suffix )
    {
        String base = filename.endsWith( suffix )
            ? filename.substring( 0, filename.length() - suffix.length() )
            : filename;
        int last = base.lastIndexOf( '-' );
        if ( last <= 0 )
        {
            return "";
        }
        String release = base.substring( last + 1 );
        String nameVer = base.substring( 0, last );
        int second = nameVer.lastIndexOf( '-' );
        if ( second <= 0 )
        {
            return release;
        }
        return nameVer.substring( second + 1 ) + "-" + release;
    }
}
