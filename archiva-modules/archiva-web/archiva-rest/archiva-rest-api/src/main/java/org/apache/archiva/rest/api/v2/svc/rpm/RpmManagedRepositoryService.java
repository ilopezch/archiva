package org.apache.archiva.rest.api.v2.svc.rpm;
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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.archiva.components.rest.model.PagedResult;
import org.apache.archiva.redback.authorization.RedbackAuthorization;
import org.apache.archiva.rest.api.v2.model.RpmManagedRepository;
import org.apache.archiva.rest.api.v2.svc.ArchivaRestServiceException;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.apache.archiva.rest.api.v2.svc.RestConfiguration.DEFAULT_PAGE_LIMIT;
import static org.apache.archiva.security.common.ArchivaRoleConstants.OPERATION_MANAGE_CONFIGURATION;

/**
 * CRUD service for managed RPM repositories.
 * Base path: {@code /repositories/rpm/managed}
 */
@Path( "repositories/rpm/managed" )
@Tag( name = "v2" )
@Tag( name = "v2/Repositories" )
public interface RpmManagedRepositoryService
{
    @GET
    @Produces( {APPLICATION_JSON} )
    @RedbackAuthorization( permissions = {OPERATION_MANAGE_CONFIGURATION} )
    @Operation( summary = "Returns all managed RPM repositories.",
        parameters = {
            @Parameter( name = "q", description = "Search term" ),
            @Parameter( name = "offset", description = "Offset of the first element" ),
            @Parameter( name = "limit", description = "Maximum number of items to return" ),
            @Parameter( name = "orderBy", description = "Attributes to sort by" ),
            @Parameter( name = "order", description = "Sort direction: asc or desc" )
        },
        security = {@SecurityRequirement( name = OPERATION_MANAGE_CONFIGURATION )},
        responses = {
            @ApiResponse( responseCode = "200", description = "List of RPM managed repositories" ),
            @ApiResponse( responseCode = "403", description = "Authenticated user does not have permission" )
        }
    )
    PagedResult<RpmManagedRepository> getManagedRepositories(
        @QueryParam( "q" ) @DefaultValue( "" ) String searchTerm,
        @QueryParam( "offset" ) @DefaultValue( "0" ) Integer offset,
        @QueryParam( "limit" ) @DefaultValue( DEFAULT_PAGE_LIMIT ) Integer limit,
        @QueryParam( "orderBy" ) @DefaultValue( "id" ) List<String> orderBy,
        @QueryParam( "order" ) @DefaultValue( "asc" ) String order )
        throws ArchivaRestServiceException;

    @GET
    @Path( "{id}" )
    @Produces( {APPLICATION_JSON} )
    @RedbackAuthorization( permissions = {OPERATION_MANAGE_CONFIGURATION} )
    @Operation( summary = "Returns a managed RPM repository by id.",
        security = {@SecurityRequirement( name = OPERATION_MANAGE_CONFIGURATION )},
        responses = {
            @ApiResponse( responseCode = "200", description = "The RPM repository" ),
            @ApiResponse( responseCode = "403", description = "Authenticated user does not have permission" ),
            @ApiResponse( responseCode = "404", description = "Repository not found" )
        }
    )
    RpmManagedRepository getManagedRepository( @PathParam( "id" ) String repositoryId )
        throws ArchivaRestServiceException;

    @POST
    @Consumes( {APPLICATION_JSON} )
    @Produces( {APPLICATION_JSON} )
    @RedbackAuthorization( permissions = {OPERATION_MANAGE_CONFIGURATION} )
    @Operation( summary = "Creates a new managed RPM repository.",
        security = {@SecurityRequirement( name = OPERATION_MANAGE_CONFIGURATION )},
        responses = {
            @ApiResponse( responseCode = "201", description = "Repository created" ),
            @ApiResponse( responseCode = "303", description = "Repository with this id already exists" ),
            @ApiResponse( responseCode = "403", description = "Authenticated user does not have permission" ),
            @ApiResponse( responseCode = "422", description = "Validation error" )
        }
    )
    RpmManagedRepository addManagedRepository( RpmManagedRepository managedRepository )
        throws ArchivaRestServiceException;

    @PUT
    @Path( "{id}" )
    @Consumes( {APPLICATION_JSON} )
    @Produces( {APPLICATION_JSON} )
    @RedbackAuthorization( permissions = {OPERATION_MANAGE_CONFIGURATION} )
    @Operation( summary = "Updates an existing managed RPM repository.",
        security = {@SecurityRequirement( name = OPERATION_MANAGE_CONFIGURATION )},
        responses = {
            @ApiResponse( responseCode = "200", description = "Repository updated" ),
            @ApiResponse( responseCode = "403", description = "Authenticated user does not have permission" ),
            @ApiResponse( responseCode = "404", description = "Repository not found" ),
            @ApiResponse( responseCode = "422", description = "Validation error" )
        }
    )
    RpmManagedRepository updateManagedRepository( @PathParam( "id" ) String repositoryId,
                                                  RpmManagedRepository managedRepository )
        throws ArchivaRestServiceException;

    @DELETE
    @Path( "{id}" )
    @Produces( {APPLICATION_JSON} )
    @RedbackAuthorization( permissions = {OPERATION_MANAGE_CONFIGURATION} )
    @Operation( summary = "Deletes a managed RPM repository.",
        security = {@SecurityRequirement( name = OPERATION_MANAGE_CONFIGURATION )},
        responses = {
            @ApiResponse( responseCode = "200", description = "Repository deleted" ),
            @ApiResponse( responseCode = "403", description = "Authenticated user does not have permission" ),
            @ApiResponse( responseCode = "404", description = "Repository not found" )
        }
    )
    Response deleteManagedRepository( @PathParam( "id" ) String repositoryId,
                                      @QueryParam( "deleteContent" ) @DefaultValue( "false" ) Boolean deleteContent )
        throws ArchivaRestServiceException;
}
