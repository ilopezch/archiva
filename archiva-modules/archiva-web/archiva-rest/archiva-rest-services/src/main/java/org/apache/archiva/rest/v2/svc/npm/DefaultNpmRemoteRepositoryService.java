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

import org.apache.archiva.admin.model.RepositoryAdminException;
import org.apache.archiva.admin.model.remote.RemoteRepositoryAdmin;
import org.apache.archiva.components.rest.model.PagedResult;
import org.apache.archiva.components.rest.util.QueryHelper;
import org.apache.archiva.configuration.model.RemoteRepositoryConfiguration;
import org.apache.archiva.repository.RemoteRepository;
import org.apache.archiva.repository.Repository;
import org.apache.archiva.repository.RepositoryException;
import org.apache.archiva.repository.RepositoryRegistry;
import org.apache.archiva.repository.RepositoryType;
import org.apache.archiva.rest.api.v2.model.NpmRemoteRepository;
import org.apache.archiva.rest.api.v2.svc.ArchivaRestServiceException;
import org.apache.archiva.rest.api.v2.svc.ErrorKeys;
import org.apache.archiva.rest.api.v2.svc.ErrorMessage;
import org.apache.archiva.rest.api.v2.svc.npm.NpmRemoteRepositoryService;
import org.apache.archiva.rest.v2.svc.AbstractService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link NpmRemoteRepositoryService}.
 */
@Service( "v2.remoteNpmRepositoryService#rest" )
public class DefaultNpmRemoteRepositoryService extends AbstractService implements NpmRemoteRepositoryService
{
    private static final Logger log = LoggerFactory.getLogger( DefaultNpmRemoteRepositoryService.class );

    private static final QueryHelper<RemoteRepository> QUERY_HELPER =
        new QueryHelper<>( new String[]{"id", "name"} );

    static
    {
        QUERY_HELPER.addStringFilter( "id", RemoteRepository::getId );
        QUERY_HELPER.addStringFilter( "name", RemoteRepository::getName );
        QUERY_HELPER.addStringFilter( "location", r -> r.getLocation() != null ? r.getLocation().toString() : "" );
        QUERY_HELPER.addNullsafeFieldComparator( "id", RemoteRepository::getId );
        QUERY_HELPER.addNullsafeFieldComparator( "name", RemoteRepository::getName );
    }

    @Context
    HttpServletResponse httpServletResponse;

    @Context
    UriInfo uriInfo;

    private final RepositoryRegistry repositoryRegistry;
    private final RemoteRepositoryAdmin remoteRepositoryAdmin;

    public DefaultNpmRemoteRepositoryService( RepositoryRegistry repositoryRegistry,
                                              RemoteRepositoryAdmin remoteRepositoryAdmin )
    {
        this.repositoryRegistry = repositoryRegistry;
        this.remoteRepositoryAdmin = remoteRepositoryAdmin;
    }

    @Override
    public PagedResult<NpmRemoteRepository> getRemoteRepositories( String searchTerm, Integer offset,
                                                                   Integer limit, List<String> orderBy,
                                                                   String order )
        throws ArchivaRestServiceException
    {
        try
        {
            Collection<RemoteRepository> repos = repositoryRegistry.getRemoteRepositories();
            final Predicate<RemoteRepository> queryFilter =
                QUERY_HELPER.getQueryFilter( searchTerm ).and( r -> r.getType() == RepositoryType.NPM );
            final Comparator<RemoteRepository> comparator = QUERY_HELPER.getComparator( orderBy, order );
            int totalCount = Math.toIntExact( repos.stream().filter( queryFilter ).count() );
            return PagedResult.of( totalCount, offset, limit,
                repos.stream().filter( queryFilter ).sorted( comparator )
                    .map( NpmRemoteRepository::of ).skip( offset ).limit( limit )
                    .collect( Collectors.toList() ) );
        }
        catch ( ArithmeticException e )
        {
            log.error( "Invalid number of repositories detected." );
            throw new ArchivaRestServiceException( ErrorMessage.of( ErrorKeys.INVALID_RESULT_SET_ERROR ) );
        }
    }

    @Override
    public NpmRemoteRepository getRemoteRepository( String repositoryId )
        throws ArchivaRestServiceException
    {
        RemoteRepository repo = repositoryRegistry.getRemoteRepository( repositoryId );
        if ( repo == null )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_NOT_FOUND, repositoryId ), 404 );
        }
        if ( repo.getType() != RepositoryType.NPM )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_WRONG_TYPE, repositoryId, repo.getType().name() ), 404 );
        }
        return NpmRemoteRepository.of( repo );
    }

    @Override
    public NpmRemoteRepository addRemoteRepository( NpmRemoteRepository remoteRepository )
        throws ArchivaRestServiceException
    {
        final String repoId = remoteRepository.getId();
        if ( StringUtils.isEmpty( repoId ) )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_INVALID_ID, repoId ), 422 );
        }
        Repository existing = repositoryRegistry.getRepository( repoId );
        if ( existing != null )
        {
            httpServletResponse.setHeader( "Location",
                uriInfo.getAbsolutePathBuilder().path( repoId ).build().toString() );
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_ID_EXISTS, repoId ), 303 );
        }
        try
        {
            RemoteRepositoryConfiguration config = toConfig( remoteRepository );
            repositoryRegistry.putRepository( config );
            httpServletResponse.setStatus( 201 );
            return NpmRemoteRepository.of( repositoryRegistry.getRemoteRepository( repoId ) );
        }
        catch ( RepositoryException e )
        {
            log.error( "Could not create NPM remote repository {}: {}", repoId, e.getMessage(), e );
            throw new ArchivaRestServiceException( ErrorMessage.of( ErrorKeys.REPOSITORY_ADD_FAILED, repoId ) );
        }
    }

    @Override
    public NpmRemoteRepository updateRemoteRepository( String repositoryId, NpmRemoteRepository remoteRepository )
        throws ArchivaRestServiceException
    {
        RemoteRepository existing = repositoryRegistry.getRemoteRepository( repositoryId );
        if ( existing == null || existing.getType() != RepositoryType.NPM )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_NOT_FOUND, repositoryId ), 404 );
        }
        try
        {
            remoteRepository.setId( repositoryId );
            org.apache.archiva.admin.model.beans.RemoteRepository bean = toAdminBean( remoteRepository );
            remoteRepositoryAdmin.updateRemoteRepository( bean, getAuditInformation() );
            RemoteRepository updated = repositoryRegistry.getRemoteRepository( repositoryId );
            if ( updated == null )
            {
                throw new ArchivaRestServiceException(
                    ErrorMessage.of( ErrorKeys.REPOSITORY_UPDATE_FAILED, repositoryId ) );
            }
            return NpmRemoteRepository.of( updated );
        }
        catch ( RepositoryAdminException e )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_ADMIN_ERROR, e.getMessage() ) );
        }
    }

    @Override
    public Response deleteRemoteRepository( String repositoryId )
        throws ArchivaRestServiceException
    {
        RemoteRepository repo = repositoryRegistry.getRemoteRepository( repositoryId );
        if ( repo == null || repo.getType() != RepositoryType.NPM )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_NOT_FOUND, repositoryId ), 404 );
        }
        try
        {
            remoteRepositoryAdmin.deleteRemoteRepository( repositoryId, getAuditInformation() );
            return Response.ok().build();
        }
        catch ( RepositoryAdminException e )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_DELETE_FAILED, e.getMessage() ) );
        }
    }

    private RemoteRepositoryConfiguration toConfig( NpmRemoteRepository dto )
    {
        RemoteRepositoryConfiguration cfg = new RemoteRepositoryConfiguration();
        cfg.setId( dto.getId() );
        cfg.setName( dto.getName() );
        cfg.setDescription( dto.getDescription() );
        cfg.setUrl( dto.getLocation() );
        cfg.setUsername( dto.getLoginUser() );
        cfg.setPassword( dto.getLoginPassword() );
        cfg.setCheckPath( dto.getCheckPath() );
        cfg.setTimeout( dto.getTimeoutMs() > 0 ? (int) ( dto.getTimeoutMs() / 1000 ) : 60 );
        cfg.setLayout( "npm-default" );
        cfg.setType( RepositoryType.NPM.name() );
        return cfg;
    }

    private org.apache.archiva.admin.model.beans.RemoteRepository toAdminBean( NpmRemoteRepository dto )
    {
        org.apache.archiva.admin.model.beans.RemoteRepository bean =
            new org.apache.archiva.admin.model.beans.RemoteRepository();
        bean.setId( dto.getId() );
        bean.setName( dto.getName() );
        bean.setDescription( dto.getDescription() );
        bean.setUrl( dto.getLocation() );
        bean.setUserName( dto.getLoginUser() );
        bean.setPassword( dto.getLoginPassword() );
        bean.setCheckPath( dto.getCheckPath() );
        bean.setTimeout( dto.getTimeoutMs() > 0 ? (int) ( dto.getTimeoutMs() / 1000 ) : 60 );
        bean.setLayout( "npm-default" );
        bean.setType( RepositoryType.NPM.name() );
        return bean;
    }
}
