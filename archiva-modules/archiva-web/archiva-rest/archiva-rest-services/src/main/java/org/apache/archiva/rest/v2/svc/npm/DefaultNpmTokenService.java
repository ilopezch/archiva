package org.apache.archiva.rest.v2.svc.npm;
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

import org.apache.archiva.npm.repository.token.NpmApiToken;
import org.apache.archiva.npm.repository.token.NpmApiTokenStore;
import org.apache.archiva.redback.users.User;
import org.apache.archiva.rest.api.v2.model.NpmApiTokenCreated;
import org.apache.archiva.rest.api.v2.model.NpmApiTokenInfo;
import org.apache.archiva.rest.api.v2.model.NpmApiTokenRequest;
import org.apache.archiva.rest.api.v2.svc.ArchivaRestServiceException;
import org.apache.archiva.rest.api.v2.svc.npm.NpmTokenService;
import org.apache.archiva.rest.v2.svc.AbstractService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.ws.rs.core.Response;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link NpmTokenService}. All operations are scoped to the
 * currently authenticated user — there is no admin-level access to other users' tokens.
 */
@Service( "v2.npmTokenService#rest" )
public class DefaultNpmTokenService extends AbstractService implements NpmTokenService
{
    private final NpmApiTokenStore tokenStore;

    @Autowired
    public DefaultNpmTokenService( NpmApiTokenStore tokenStore )
    {
        this.tokenStore = tokenStore;
    }

    @Override
    public List<NpmApiTokenInfo> getTokens() throws ArchivaRestServiceException
    {
        return tokenStore.listTokens( currentUsername() ).stream()
            .map( DefaultNpmTokenService::toInfo )
            .collect( Collectors.toList() );
    }

    @Override
    public NpmApiTokenCreated generateToken( NpmApiTokenRequest request ) throws ArchivaRestServiceException
    {
        String label = request == null ? "" : StringUtils.trimToEmpty( request.getLabel() );
        NpmApiTokenStore.GeneratedToken generated = tokenStore.generateToken( currentUsername(), label );
        return new NpmApiTokenCreated( toInfo( generated.getToken() ), generated.getPlaintextValue() );
    }

    @Override
    public void revokeToken( String tokenId ) throws ArchivaRestServiceException
    {
        if ( !tokenStore.revokeToken( currentUsername(), tokenId ) )
        {
            throw new ArchivaRestServiceException( "No such token", Response.Status.NOT_FOUND.getStatusCode() );
        }
    }

    private String currentUsername() throws ArchivaRestServiceException
    {
        User user = getAuditInformation().getUser();
        if ( user == null )
        {
            throw new ArchivaRestServiceException( "Not authenticated", Response.Status.FORBIDDEN.getStatusCode() );
        }
        return user.getUsername();
    }

    private static NpmApiTokenInfo toInfo( NpmApiToken token )
    {
        return new NpmApiTokenInfo( token.getId(), token.getLabel(), token.getCreatedAt(), token.getLastUsedAt() );
    }
}
