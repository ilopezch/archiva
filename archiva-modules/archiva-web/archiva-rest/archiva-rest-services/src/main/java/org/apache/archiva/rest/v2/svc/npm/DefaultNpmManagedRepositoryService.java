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
import org.apache.archiva.admin.model.managed.ManagedRepositoryAdmin;
import org.apache.archiva.components.rest.model.PagedResult;
import org.apache.archiva.components.rest.util.QueryHelper;
import org.apache.archiva.configuration.model.ManagedRepositoryConfiguration;
import org.apache.archiva.repository.ManagedRepository;
import org.apache.archiva.repository.Repository;
import org.apache.archiva.repository.RepositoryException;
import org.apache.archiva.repository.RepositoryRegistry;
import org.apache.archiva.repository.RepositoryType;
import org.apache.archiva.rest.api.v2.model.NpmManagedRepository;
import org.apache.archiva.rest.api.v2.svc.ArchivaRestServiceException;
import org.apache.archiva.rest.api.v2.svc.ErrorKeys;
import org.apache.archiva.rest.api.v2.svc.ErrorMessage;
import org.apache.archiva.rest.api.v2.svc.npm.NpmManagedRepositoryService;
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
 * Default implementation of {@link NpmManagedRepositoryService}.
 */
@Service( "v2.managedNpmRepositoryService#rest" )
public class DefaultNpmManagedRepositoryService extends AbstractService implements NpmManagedRepositoryService
{
    private static final Logger log = LoggerFactory.getLogger( DefaultNpmManagedRepositoryService.class );

    private static final QueryHelper<ManagedRepository> QUERY_HELPER =
        new QueryHelper<>( new String[]{"id", "name"} );

    static
    {
        QUERY_HELPER.addStringFilter( "id", ManagedRepository::getId );
        QUERY_HELPER.addStringFilter( "name", ManagedRepository::getName );
        QUERY_HELPER.addStringFilter( "location", r -> r.getLocation().toString() );
        QUERY_HELPER.addNullsafeFieldComparator( "id", ManagedRepository::getId );
        QUERY_HELPER.addNullsafeFieldComparator( "name", ManagedRepository::getName );
    }

    @Context
    HttpServletResponse httpServletResponse;

    @Context
    UriInfo uriInfo;

    private final RepositoryRegistry repositoryRegistry;
    private final ManagedRepositoryAdmin managedRepositoryAdmin;

    public DefaultNpmManagedRepositoryService( RepositoryRegistry repositoryRegistry,
                                               ManagedRepositoryAdmin managedRepositoryAdmin )
    {
        this.repositoryRegistry = repositoryRegistry;
        this.managedRepositoryAdmin = managedRepositoryAdmin;
    }

    @Override
    public PagedResult<NpmManagedRepository> getManagedRepositories( String searchTerm, Integer offset,
                                                                     Integer limit, List<String> orderBy,
                                                                     String order )
        throws ArchivaRestServiceException
    {
        try
        {
            Collection<ManagedRepository> repos = repositoryRegistry.getManagedRepositories();
            final Predicate<ManagedRepository> queryFilter =
                QUERY_HELPER.getQueryFilter( searchTerm ).and( r -> r.getType() == RepositoryType.NPM );
            final Comparator<ManagedRepository> comparator = QUERY_HELPER.getComparator( orderBy, order );
            int totalCount = Math.toIntExact( repos.stream().filter( queryFilter ).count() );
            return PagedResult.of( totalCount, offset, limit,
                repos.stream().filter( queryFilter ).sorted( comparator )
                    .map( NpmManagedRepository::of ).skip( offset ).limit( limit )
                    .collect( Collectors.toList() ) );
        }
        catch ( ArithmeticException e )
        {
            log.error( "Invalid number of repositories detected." );
            throw new ArchivaRestServiceException( ErrorMessage.of( ErrorKeys.INVALID_RESULT_SET_ERROR ) );
        }
    }

    @Override
    public NpmManagedRepository getManagedRepository( String repositoryId )
        throws ArchivaRestServiceException
    {
        ManagedRepository repo = repositoryRegistry.getManagedRepository( repositoryId );
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
        return NpmManagedRepository.of( repo );
    }

    @Override
    public NpmManagedRepository addManagedRepository( NpmManagedRepository managedRepository )
        throws ArchivaRestServiceException
    {
        final String repoId = managedRepository.getId();
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
            ManagedRepositoryConfiguration config = toConfig( managedRepository );
            repositoryRegistry.putRepository( config );
            httpServletResponse.setStatus( 201 );
            return NpmManagedRepository.of( repositoryRegistry.getManagedRepository( repoId ) );
        }
        catch ( RepositoryException e )
        {
            log.error( "Could not create NPM repository {}: {}", repoId, e.getMessage(), e );
            throw new ArchivaRestServiceException( ErrorMessage.of( ErrorKeys.REPOSITORY_ADD_FAILED, repoId ) );
        }
    }

    @Override
    public NpmManagedRepository updateManagedRepository( String repositoryId, NpmManagedRepository managedRepository )
        throws ArchivaRestServiceException
    {
        ManagedRepository existing = repositoryRegistry.getManagedRepository( repositoryId );
        if ( existing == null || existing.getType() != RepositoryType.NPM )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_NOT_FOUND, repositoryId ), 404 );
        }
        try
        {
            managedRepository.setId( repositoryId );
            org.apache.archiva.admin.model.beans.ManagedRepository bean = toAdminBean( managedRepository );
            managedRepositoryAdmin.updateManagedRepository( bean, false, getAuditInformation(), false );
            ManagedRepository updated = repositoryRegistry.getManagedRepository( repositoryId );
            if ( updated == null )
            {
                throw new ArchivaRestServiceException(
                    ErrorMessage.of( ErrorKeys.REPOSITORY_UPDATE_FAILED, repositoryId ) );
            }
            return NpmManagedRepository.of( updated );
        }
        catch ( RepositoryAdminException e )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_ADMIN_ERROR, e.getMessage() ) );
        }
    }

    @Override
    public Response deleteManagedRepository( String repositoryId, Boolean deleteContent )
        throws ArchivaRestServiceException
    {
        ManagedRepository repo = repositoryRegistry.getManagedRepository( repositoryId );
        if ( repo == null || repo.getType() != RepositoryType.NPM )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_NOT_FOUND, repositoryId ), 404 );
        }
        try
        {
            managedRepositoryAdmin.deleteManagedRepository( repositoryId, getAuditInformation(),
                deleteContent != null && deleteContent );
            return Response.ok().build();
        }
        catch ( RepositoryAdminException e )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_DELETE_FAILED, e.getMessage() ) );
        }
    }

    private ManagedRepositoryConfiguration toConfig( NpmManagedRepository dto )
    {
        ManagedRepositoryConfiguration cfg = new ManagedRepositoryConfiguration();
        cfg.setId( dto.getId() );
        cfg.setName( dto.getName() );
        cfg.setDescription( dto.getDescription() );
        cfg.setLocation( dto.getLocation() );
        cfg.setScanned( dto.isScanned() );
        cfg.setCronExpression( dto.getSchedulingDefinition() );
        cfg.setLayout( "npm-default" );
        cfg.setType( RepositoryType.NPM.name() );
        cfg.setReleases( true );
        cfg.setSnapshots( false );
        return cfg;
    }

    private org.apache.archiva.admin.model.beans.ManagedRepository toAdminBean( NpmManagedRepository dto )
    {
        org.apache.archiva.admin.model.beans.ManagedRepository bean =
            new org.apache.archiva.admin.model.beans.ManagedRepository();
        bean.setId( dto.getId() );
        bean.setName( dto.getName() );
        bean.setDescription( dto.getDescription() );
        bean.setLocation( dto.getLocation() );
        bean.setScanned( dto.isScanned() );
        bean.setCronExpression( dto.getSchedulingDefinition() );
        bean.setLayout( "npm-default" );
        bean.setType( RepositoryType.NPM.name() );
        bean.setReleases( true );
        bean.setSnapshots( false );
        return bean;
    }
}
