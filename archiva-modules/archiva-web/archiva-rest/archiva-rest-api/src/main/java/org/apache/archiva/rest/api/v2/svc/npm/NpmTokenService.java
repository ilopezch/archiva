package org.apache.archiva.rest.api.v2.svc.npm;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.archiva.redback.authorization.RedbackAuthorization;
import org.apache.archiva.rest.api.v2.model.NpmApiTokenCreated;
import org.apache.archiva.rest.api.v2.model.NpmApiTokenInfo;
import org.apache.archiva.rest.api.v2.model.NpmApiTokenRequest;
import org.apache.archiva.rest.api.v2.svc.ArchivaRestServiceException;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import java.util.List;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Lets a logged-in user manage their own NPM personal access tokens — opaque,
 * server-issued, revocable credentials that can be used as the {@code _authToken}
 * in a {@code .npmrc} file to authenticate against NPM repositories without
 * exposing the user's actual password.
 *
 * Every endpoint here acts on the currently authenticated user only; there is no
 * way to list, generate, or revoke another user's tokens through this service.
 *
 * Base path: {@code /npm/tokens}
 */
@Path( "npm/tokens" )
@Tag( name = "v2" )
@Tag( name = "v2/Npm" )
public interface NpmTokenService
{
    @GET
    @Produces( {APPLICATION_JSON} )
    @RedbackAuthorization( noRestriction = false, noPermission = true )
    @Operation( summary = "Lists the NPM API tokens belonging to the current user (metadata only, values are never returned again).",
        responses = {
            @ApiResponse( responseCode = "200", description = "List of the user's NPM API tokens" ),
            @ApiResponse( responseCode = "403", description = "Not authenticated" )
        }
    )
    List<NpmApiTokenInfo> getTokens()
        throws ArchivaRestServiceException;

    @POST
    @Consumes( {APPLICATION_JSON} )
    @Produces( {APPLICATION_JSON} )
    @RedbackAuthorization( noRestriction = false, noPermission = true )
    @Operation( summary = "Generates a new NPM API token for the current user. The plaintext value is returned once and cannot be retrieved again.",
        responses = {
            @ApiResponse( responseCode = "200", description = "The generated token, including its plaintext value" ),
            @ApiResponse( responseCode = "403", description = "Not authenticated" )
        }
    )
    NpmApiTokenCreated generateToken( NpmApiTokenRequest request )
        throws ArchivaRestServiceException;

    @DELETE
    @Path( "{id}" )
    @Produces( {APPLICATION_JSON} )
    @RedbackAuthorization( noRestriction = false, noPermission = true )
    @Operation( summary = "Revokes one of the current user's NPM API tokens. Once revoked, the token immediately stops working.",
        responses = {
            @ApiResponse( responseCode = "200", description = "Token revoked" ),
            @ApiResponse( responseCode = "403", description = "Not authenticated" ),
            @ApiResponse( responseCode = "404", description = "No such token for the current user" )
        }
    )
    void revokeToken( @PathParam( "id" ) String tokenId )
        throws ArchivaRestServiceException;
}
