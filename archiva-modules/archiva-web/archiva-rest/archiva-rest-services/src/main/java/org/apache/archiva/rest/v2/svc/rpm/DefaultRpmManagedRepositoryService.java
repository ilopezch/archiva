package org.apache.archiva.rest.v2.svc.rpm;
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
import org.apache.archiva.rest.api.v2.model.RpmGpgKeyInfo;
import org.apache.archiva.rest.api.v2.model.RpmManagedRepository;
import org.apache.archiva.rest.api.v2.svc.ArchivaRestServiceException;
import org.apache.archiva.rest.api.v2.svc.ErrorKeys;
import org.apache.archiva.rest.api.v2.svc.ErrorMessage;
import org.apache.archiva.rest.api.v2.svc.rpm.RpmManagedRepositoryService;
import org.apache.archiva.rest.v2.svc.AbstractService;
import org.apache.archiva.rpm.repository.repodata.RepomdGenerator;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.openpgp.PGPException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link RpmManagedRepositoryService}.
 */
@Service( "v2.managedRpmRepositoryService#rest" )
public class DefaultRpmManagedRepositoryService extends AbstractService implements RpmManagedRepositoryService
{
    private static final Logger log = LoggerFactory.getLogger( DefaultRpmManagedRepositoryService.class );

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

    public DefaultRpmManagedRepositoryService( RepositoryRegistry repositoryRegistry,
                                               ManagedRepositoryAdmin managedRepositoryAdmin )
    {
        this.repositoryRegistry = repositoryRegistry;
        this.managedRepositoryAdmin = managedRepositoryAdmin;
    }

    @Override
    public PagedResult<RpmManagedRepository> getManagedRepositories( String searchTerm, Integer offset,
                                                                     Integer limit, List<String> orderBy,
                                                                     String order )
        throws ArchivaRestServiceException
    {
        try
        {
            Collection<ManagedRepository> repos = repositoryRegistry.getManagedRepositories();
            final Predicate<ManagedRepository> queryFilter =
                QUERY_HELPER.getQueryFilter( searchTerm ).and( r -> r.getType() == RepositoryType.RPM );
            final Comparator<ManagedRepository> comparator = QUERY_HELPER.getComparator( orderBy, order );
            int totalCount = Math.toIntExact( repos.stream().filter( queryFilter ).count() );
            return PagedResult.of( totalCount, offset, limit,
                repos.stream().filter( queryFilter ).sorted( comparator )
                    .map( RpmManagedRepository::of ).skip( offset ).limit( limit )
                    .collect( Collectors.toList() ) );
        }
        catch ( ArithmeticException e )
        {
            log.error( "Invalid number of repositories detected." );
            throw new ArchivaRestServiceException( ErrorMessage.of( ErrorKeys.INVALID_RESULT_SET_ERROR ) );
        }
    }

    @Override
    public RpmManagedRepository getManagedRepository( String repositoryId )
        throws ArchivaRestServiceException
    {
        ManagedRepository repo = repositoryRegistry.getManagedRepository( repositoryId );
        if ( repo == null )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_NOT_FOUND, repositoryId ), 404 );
        }
        if ( repo.getType() != RepositoryType.RPM )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_WRONG_TYPE, repositoryId, repo.getType().name() ), 404 );
        }
        return RpmManagedRepository.of( repo );
    }

    @Override
    public RpmManagedRepository addManagedRepository( RpmManagedRepository managedRepository )
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
            return RpmManagedRepository.of( repositoryRegistry.getManagedRepository( repoId ) );
        }
        catch ( RepositoryException e )
        {
            log.error( "Could not create RPM repository {}: {}", repoId, e.getMessage(), e );
            throw new ArchivaRestServiceException( ErrorMessage.of( ErrorKeys.REPOSITORY_ADD_FAILED, repoId ) );
        }
    }

    @Override
    public RpmManagedRepository updateManagedRepository( String repositoryId, RpmManagedRepository managedRepository )
        throws ArchivaRestServiceException
    {
        ManagedRepository existing = repositoryRegistry.getManagedRepository( repositoryId );
        if ( existing == null || existing.getType() != RepositoryType.RPM )
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
            return RpmManagedRepository.of( updated );
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
        if ( repo == null || repo.getType() != RepositoryType.RPM )
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

    @Override
    public RpmGpgKeyInfo getGpgKey( String repositoryId ) throws ArchivaRestServiceException
    {
        ManagedRepository repo = requireRpmRepository( repositoryId );
        Path repoRoot = repo.getRoot().getFilePath();
        String gpgKeyPath = null;
        String gpgUserId  = null;
        if ( repo instanceof org.apache.archiva.rpm.repository.RpmManagedRepository )
        {
            org.apache.archiva.rpm.repository.RpmManagedRepository r =
                (org.apache.archiva.rpm.repository.RpmManagedRepository) repo;
            gpgKeyPath = r.getGpgKeyPath();
            gpgUserId  = r.getGpgUserId();
        }
        try
        {
            RepomdGenerator.GpgKeyDetails details =
                new RepomdGenerator().getKeyDetails( repoRoot, gpgKeyPath, gpgUserId );
            return toKeyInfo( details );
        }
        catch ( IOException | PGPException e )
        {
            log.error( "Could not load GPG key for repository {}: {}", repositoryId, e.getMessage(), e );
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_ADMIN_ERROR, e.getMessage() ) );
        }
    }

    @Override
    public RpmGpgKeyInfo rotateGpgKey( String repositoryId ) throws ArchivaRestServiceException
    {
        ManagedRepository repo = requireRpmRepository( repositoryId );
        Path repoRoot = repo.getRoot().getFilePath();
        String gpgUserId = null;
        if ( repo instanceof org.apache.archiva.rpm.repository.RpmManagedRepository )
        {
            gpgUserId = ( (org.apache.archiva.rpm.repository.RpmManagedRepository) repo ).getGpgUserId();
        }
        try
        {
            RepomdGenerator gen = new RepomdGenerator();
            RepomdGenerator.GpgKeyDetails details = gen.rotateKey( repoRoot, gpgUserId );
            // Re-sign repomd.xml with the new key
            gen.rebuild( repoRoot );
            return toKeyInfo( details );
        }
        catch ( IOException | PGPException e )
        {
            log.error( "Could not rotate GPG key for repository {}: {}", repositoryId, e.getMessage(), e );
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_ADMIN_ERROR, e.getMessage() ) );
        }
    }

    private ManagedRepository requireRpmRepository( String repositoryId ) throws ArchivaRestServiceException
    {
        ManagedRepository repo = repositoryRegistry.getManagedRepository( repositoryId );
        if ( repo == null || repo.getType() != RepositoryType.RPM )
        {
            throw new ArchivaRestServiceException(
                ErrorMessage.of( ErrorKeys.REPOSITORY_NOT_FOUND, repositoryId ), 404 );
        }
        return repo;
    }

    private static RpmGpgKeyInfo toKeyInfo( RepomdGenerator.GpgKeyDetails d )
    {
        RpmGpgKeyInfo info = new RpmGpgKeyInfo();
        info.setFingerprint( d.fingerprint );
        info.setUserId( d.userId );
        info.setAlgorithm( d.algorithm );
        info.setBitStrength( d.bitStrength );
        info.setCreated( d.created != null ? d.created.toString() : null );
        info.setExpires( d.expires != null ? d.expires.toString() : null );
        info.setArmoredPublicKey( d.armoredPublicKey );
        return info;
    }

    private ManagedRepositoryConfiguration toConfig( RpmManagedRepository dto )
    {
        ManagedRepositoryConfiguration cfg = new ManagedRepositoryConfiguration();
        cfg.setId( dto.getId() );
        cfg.setName( dto.getName() );
        cfg.setDescription( dto.getDescription() );
        cfg.setLocation( dto.getLocation() );
        cfg.setScanned( dto.isScanned() );
        cfg.setCronExpression( dto.getSchedulingDefinition() );
        cfg.setLayout( "rpm-default" );
        cfg.setType( RepositoryType.RPM.name() );
        cfg.setReleases( true );
        cfg.setSnapshots( false );
        cfg.setGpgKeyPath( dto.getGpgKeyPath() );
        cfg.setGpgUserId( dto.getGpgUserId() );
        return cfg;
    }

    private org.apache.archiva.admin.model.beans.ManagedRepository toAdminBean( RpmManagedRepository dto )
    {
        org.apache.archiva.admin.model.beans.ManagedRepository bean =
            new org.apache.archiva.admin.model.beans.ManagedRepository();
        bean.setId( dto.getId() );
        bean.setName( dto.getName() );
        bean.setDescription( dto.getDescription() );
        bean.setLocation( dto.getLocation() );
        bean.setScanned( dto.isScanned() );
        bean.setCronExpression( dto.getSchedulingDefinition() );
        bean.setLayout( "rpm-default" );
        bean.setType( RepositoryType.RPM.name() );
        bean.setReleases( true );
        bean.setSnapshots( false );
        return bean;
    }
}
