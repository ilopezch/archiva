package org.apache.archiva.rest.api.v2.model;
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.archiva.repository.RemoteRepository;
import org.apache.archiva.repository.RepositoryCredentials;
import org.apache.archiva.repository.RepositoryType;
import org.apache.archiva.repository.base.PasswordCredentials;

/**
 * REST DTO for a remote (proxy) RPM repository.
 */
@Schema( name = "RpmRemoteRepository", description = "A remote RPM repository used to mirror packages from an upstream yum/dnf mirror" )
public class RpmRemoteRepository extends Repository
{
    private static final long serialVersionUID = 1L;

    private String loginUser;
    private String loginPassword;
    private String checkPath;
    private long timeoutMs;

    public RpmRemoteRepository()
    {
        super.setCharacteristic( Repository.CHARACTERISTIC_REMOTE );
        super.setType( RepositoryType.RPM.name() );
        super.setLayout( "rpm-default" );
    }

    public static RpmRemoteRepository of( RemoteRepository repo )
    {
        RpmRemoteRepository dto = new RpmRemoteRepository();
        dto.setId( repo.getId() );
        dto.setName( repo.getName() );
        dto.setDescription( repo.getDescription() );
        dto.setLocation( repo.getLocation() != null ? repo.getLocation().toASCIIString() : "" );
        dto.setLayout( repo.getLayout() );
        dto.setCheckPath( repo.getCheckPath() );
        dto.setTimeoutMs( repo.getTimeout() != null ? repo.getTimeout().toMillis() : 0 );
        RepositoryCredentials creds = repo.getLoginCredentials();
        if ( creds instanceof PasswordCredentials )
        {
            PasswordCredentials pc = (PasswordCredentials) creds;
            dto.setLoginUser( pc.getUsername() );
        }
        return dto;
    }

    @Schema( name = "login_user", description = "Username for authentication with the upstream mirror" )
    public String getLoginUser()
    {
        return loginUser;
    }

    public void setLoginUser( String loginUser )
    {
        this.loginUser = loginUser;
    }

    @Schema( name = "login_password", description = "Password for authentication with the upstream mirror" )
    public String getLoginPassword()
    {
        return loginPassword;
    }

    public void setLoginPassword( String loginPassword )
    {
        this.loginPassword = loginPassword;
    }

    @Schema( name = "check_path", description = "Path used to verify availability of the upstream mirror (e.g. repodata/repomd.xml)" )
    public String getCheckPath()
    {
        return checkPath;
    }

    public void setCheckPath( String checkPath )
    {
        this.checkPath = checkPath;
    }

    @Schema( name = "timeout_ms", description = "HTTP request timeout in milliseconds" )
    public long getTimeoutMs()
    {
        return timeoutMs;
    }

    public void setTimeoutMs( long timeoutMs )
    {
        this.timeoutMs = timeoutMs;
    }
}
