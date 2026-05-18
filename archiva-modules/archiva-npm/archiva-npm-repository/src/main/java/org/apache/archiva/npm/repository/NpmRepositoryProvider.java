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

import org.apache.archiva.common.filelock.FileLockManager;
import org.apache.archiva.configuration.model.ManagedRepositoryConfiguration;
import org.apache.archiva.configuration.model.RemoteRepositoryConfiguration;
import org.apache.archiva.configuration.model.RepositoryGroupConfiguration;
import org.apache.archiva.configuration.provider.ArchivaConfiguration;
import org.apache.archiva.event.Event;
import org.apache.archiva.event.EventHandler;
import org.apache.archiva.repository.EditableManagedRepository;
import org.apache.archiva.repository.EditableRemoteRepository;
import org.apache.archiva.repository.EditableRepositoryGroup;
import org.apache.archiva.repository.ManagedRepository;
import org.apache.archiva.repository.RemoteRepository;
import org.apache.archiva.repository.Repository;
import org.apache.archiva.repository.RepositoryException;
import org.apache.archiva.repository.RepositoryGroup;
import org.apache.archiva.repository.RepositoryProvider;
import org.apache.archiva.repository.RepositoryType;
import org.apache.archiva.repository.UnsupportedURIException;
import org.apache.archiva.repository.base.PasswordCredentials;
import org.apache.archiva.repository.event.RepositoryEvent;
import org.apache.archiva.repository.storage.fs.FilesystemStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring-registered provider that creates and configures NPM repository instances.
 */
@Service( "npmRepositoryProvider" )
public class NpmRepositoryProvider implements RepositoryProvider
{
    private static final Logger log = LoggerFactory.getLogger( NpmRepositoryProvider.class );

    private static final Set<RepositoryType> TYPES = new HashSet<>();

    static
    {
        TYPES.add( RepositoryType.NPM );
    }

    @Inject
    private ArchivaConfiguration archivaConfiguration;

    @Inject
    private FileLockManager fileLockManager;

    private final List<EventHandler<? super RepositoryEvent>> repositoryEventHandlers = new ArrayList<>();

    @Override
    public Set<RepositoryType> provides()
    {
        return TYPES;
    }

    @Override
    public EditableManagedRepository createManagedInstance( String id, String name ) throws IOException
    {
        Path baseDir = archivaConfiguration.getRepositoryBaseDir();
        FilesystemStorage storage = new FilesystemStorage( baseDir.resolve( id ), fileLockManager );
        NpmManagedRepository repo = new NpmManagedRepository( id, name, storage );
        registerEventHandlers( repo );
        return repo;
    }

    @Override
    public EditableRemoteRepository createRemoteInstance( String id, String name )
    {
        Path baseDir = archivaConfiguration.getRemoteRepositoryBaseDir();
        FilesystemStorage storage;
        try
        {
            storage = new FilesystemStorage( baseDir.resolve( id ), fileLockManager );
        }
        catch ( IOException e )
        {
            log.error( "Could not initialize filesystem for remote npm repository {}", id );
            throw new RuntimeException( e );
        }
        NpmRemoteRepository repo = new NpmRemoteRepository( id, name, storage );
        registerEventHandlers( repo );
        return repo;
    }

    @Override
    public EditableRepositoryGroup createRepositoryGroup( String id, String name )
    {
        Path baseDir = archivaConfiguration.getRepositoryGroupBaseDir().resolve( id );
        FilesystemStorage storage;
        try
        {
            storage = new FilesystemStorage( baseDir, fileLockManager );
        }
        catch ( IOException e )
        {
            log.error( "Could not initialize filesystem for npm repository group {}", id );
            throw new RuntimeException( e );
        }
        NpmRepositoryGroup group = new NpmRepositoryGroup( id, name, storage );
        registerEventHandlers( group );
        return group;
    }

    @Override
    public ManagedRepository createManagedInstance( ManagedRepositoryConfiguration cfg ) throws RepositoryException
    {
        Path baseDir = resolveBaseDir( cfg.getLocation() );
        FilesystemStorage storage;
        try
        {
            storage = new FilesystemStorage( baseDir.resolve( cfg.getId() ), fileLockManager );
        }
        catch ( IOException e )
        {
            throw new RepositoryException( "Cannot initialize storage for " + cfg.getId(), e );
        }
        NpmManagedRepository repo = new NpmManagedRepository( cfg.getId(), cfg.getName(), storage );
        updateManagedInstance( repo, cfg );
        registerEventHandlers( repo );
        return repo;
    }

    @Override
    public void updateManagedInstance( EditableManagedRepository repo, ManagedRepositoryConfiguration cfg )
        throws RepositoryException
    {
        repo.setName( repo.getPrimaryLocale(), cfg.getName() );
        repo.setDescription( repo.getPrimaryLocale(), cfg.getDescription() );
        repo.setLayout( cfg.getLayout() );
        try
        {
            repo.setLocation( toFileUri( cfg.getLocation() ) );
        }
        catch ( UnsupportedURIException e )
        {
            throw new RepositoryException( "Invalid location for npm repository: " + cfg.getLocation() );
        }
        Path dir = repo.getRoot().getFilePath();
        if ( !Files.exists( dir ) )
        {
            try
            {
                Files.createDirectories( dir );
            }
            catch ( IOException e )
            {
                throw new RepositoryException( "Could not create directory " + dir );
            }
        }
        repo.setSchedulingDefinition( cfg.getRefreshCronExpression() );
        repo.setScanned( cfg.isScanned() );
    }

    @Override
    public ManagedRepository createStagingInstance( ManagedRepositoryConfiguration cfg ) throws RepositoryException
    {
        return createManagedInstance( cfg );
    }

    @Override
    public RemoteRepository createRemoteInstance( RemoteRepositoryConfiguration cfg ) throws RepositoryException
    {
        Path baseDir = archivaConfiguration.getRemoteRepositoryBaseDir();
        FilesystemStorage storage;
        try
        {
            storage = new FilesystemStorage( baseDir.resolve( cfg.getId() ), fileLockManager );
        }
        catch ( IOException e )
        {
            throw new RepositoryException( "Cannot initialize storage for remote " + cfg.getId(), e );
        }
        NpmRemoteRepository repo = new NpmRemoteRepository( cfg.getId(), cfg.getName(), storage );
        updateRemoteInstance( repo, cfg );
        registerEventHandlers( repo );
        return repo;
    }

    @Override
    public void updateRemoteInstance( EditableRemoteRepository repo, RemoteRepositoryConfiguration cfg )
        throws RepositoryException
    {
        repo.setName( repo.getPrimaryLocale(), cfg.getName() );
        repo.setDescription( repo.getPrimaryLocale(), cfg.getDescription() );
        repo.setLayout( cfg.getLayout() );
        repo.setCheckPath( cfg.getCheckPath() );
        repo.setSchedulingDefinition( cfg.getRefreshCronExpression() );
        try
        {
            repo.setLocation( new URI( cfg.getUrl() ) );
        }
        catch ( UnsupportedURIException | URISyntaxException e )
        {
            throw new RepositoryException( "Invalid url for npm remote repository: " + cfg.getUrl() );
        }
        repo.setTimeout( Duration.ofSeconds( cfg.getTimeout() ) );
        if ( cfg.getUsername() != null && cfg.getPassword() != null )
        {
            PasswordCredentials creds = new PasswordCredentials( cfg.getUsername(), cfg.getPassword().toCharArray() );
            repo.setCredentials( creds );
        }
        if ( repo instanceof NpmRemoteRepository )
        {
            ( (NpmRemoteRepository) repo ).syncRegistryUrl( repo.getLocation() );
        }
    }

    @Override
    public RepositoryGroup createRepositoryGroup( RepositoryGroupConfiguration cfg ) throws RepositoryException
    {
        Path baseDir = archivaConfiguration.getRepositoryGroupBaseDir().resolve( cfg.getId() );
        FilesystemStorage storage;
        try
        {
            storage = new FilesystemStorage( baseDir, fileLockManager );
        }
        catch ( IOException e )
        {
            throw new RepositoryException( "Cannot initialize storage for group " + cfg.getId(), e );
        }
        NpmRepositoryGroup group = new NpmRepositoryGroup( cfg.getId(), cfg.getName(), storage );
        updateRepositoryGroupInstance( group, cfg );
        registerEventHandlers( group );
        return group;
    }

    @Override
    public void updateRepositoryGroupInstance( EditableRepositoryGroup group, RepositoryGroupConfiguration cfg )
        throws RepositoryException
    {
        group.setName( group.getPrimaryLocale(), cfg.getName() );
        group.setMergedIndexTTL( cfg.getMergedIndexTtl() );
        group.setSchedulingDefinition( cfg.getCronExpression() );
    }

    @Override
    public RemoteRepositoryConfiguration getRemoteConfiguration( RemoteRepository repo ) throws RepositoryException
    {
        RemoteRepositoryConfiguration cfg = new RemoteRepositoryConfiguration();
        cfg.setType( RepositoryType.NPM.name() );
        cfg.setId( repo.getId() );
        cfg.setName( repo.getName() );
        cfg.setDescription( repo.getDescription() );
        cfg.setUrl( repo.getLocation().toString() );
        cfg.setTimeout( (int) repo.getTimeout().toMillis() / 1000 );
        cfg.setLayout( repo.getLayout() );
        return cfg;
    }

    @Override
    public ManagedRepositoryConfiguration getManagedConfiguration( ManagedRepository repo ) throws RepositoryException
    {
        ManagedRepositoryConfiguration cfg = new ManagedRepositoryConfiguration();
        cfg.setType( RepositoryType.NPM.name() );
        cfg.setId( repo.getId() );
        cfg.setName( repo.getName() );
        cfg.setDescription( repo.getDescription() );
        cfg.setLocation( repo.getLocation().toString() );
        cfg.setLayout( repo.getLayout() );
        cfg.setRefreshCronExpression( repo.getSchedulingDefinition() );
        cfg.setScanned( repo.isScanned() );
        return cfg;
    }

    @Override
    public RepositoryGroupConfiguration getRepositoryGroupConfiguration( RepositoryGroup group )
        throws RepositoryException
    {
        RepositoryGroupConfiguration cfg = new RepositoryGroupConfiguration();
        cfg.setId( group.getId() );
        cfg.setName( group.getName() );
        cfg.setRepositories( group.getRepositories().stream().map( Repository::getId ).collect( Collectors.toList() ) );
        cfg.setCronExpression( group.getSchedulingDefinition() );
        return cfg;
    }

    @Override
    public void addRepositoryEventHandler( EventHandler<? super RepositoryEvent> eventHandler )
    {
        this.repositoryEventHandlers.add( eventHandler );
    }

    @Override
    public void handle( Event event )
    {
    }

    private void registerEventHandlers( Repository repo )
    {
        for ( EventHandler<? super RepositoryEvent> handler : repositoryEventHandlers )
        {
            repo.registerEventHandler( RepositoryEvent.ANY, handler );
        }
    }

    private Path resolveBaseDir( String location )
    {
        if ( location == null || location.isEmpty() )
        {
            return archivaConfiguration.getRepositoryBaseDir();
        }
        Path p = Path.of( location );
        return p.isAbsolute() ? p.getParent() : archivaConfiguration.getRepositoryBaseDir().resolve( p );
    }

    private URI toFileUri( String location ) throws UnsupportedURIException
    {
        if ( location == null || location.isEmpty() )
        {
            return URI.create( "" );
        }
        if ( location.startsWith( "/" ) )
        {
            return URI.create( "file://" + location );
        }
        try
        {
            URI uri = new URI( location );
            if ( uri.getScheme() != null && !"file".equals( uri.getScheme() ) )
            {
                throw new UnsupportedURIException( "Only file:// URIs are supported for npm managed repositories" );
            }
            return uri;
        }
        catch ( URISyntaxException e )
        {
            return URI.create( "file://" + location );
        }
    }
}
