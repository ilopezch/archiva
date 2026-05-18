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

import org.apache.archiva.common.utils.FileUtils;
import org.apache.archiva.repository.ItemDeleteStatus;
import org.apache.archiva.repository.ManagedRepository;
import org.apache.archiva.repository.ManagedRepositoryContent;
import org.apache.archiva.repository.content.ContentAccessException;
import org.apache.archiva.repository.content.ContentItem;
import org.apache.archiva.repository.content.ItemNotFoundException;
import org.apache.archiva.repository.content.ItemSelector;
import org.apache.archiva.repository.content.LayoutException;
import org.apache.archiva.repository.content.ManagedRepositoryContentLayout;
import org.apache.archiva.repository.content.base.ArchivaItemSelector;
import org.apache.archiva.repository.storage.StorageAsset;
import org.apache.archiva.repository.storage.util.StorageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * NPM managed repository content. Maps npm package coordinates to filesystem paths
 * and implements all content management operations (browse, delete, copy, stream).
 *
 * <p>Storage layout:
 * <ul>
 *   <li>Tarball (unscoped): {@code {name}/-/{name}-{version}.tgz}</li>
 *   <li>Tarball (scoped):   {@code @{scope}/{name}/-/{name}-{version}.tgz}</li>
 *   <li>Metadata:           {@code {name}/package.json} or {@code @{scope}/{name}/package.json}</li>
 * </ul>
 *
 * <p>ItemSelector field mapping:
 * <ul>
 *   <li>{@code namespace} → scope (e.g. {@code @myorg}), empty string for unscoped</li>
 *   <li>{@code projectId} → package name</li>
 *   <li>{@code version}   → semver version string</li>
 * </ul>
 */
public class NpmManagedRepositoryContent implements ManagedRepositoryContent, NpmRepositoryContentLayout
{
    private static final Logger log = LoggerFactory.getLogger( NpmManagedRepositoryContent.class );

    private ManagedRepository repository;

    public NpmManagedRepositoryContent( ManagedRepository repository )
    {
        this.repository = repository;
    }

    // -------------------------------------------------------------------------
    // ManagedRepositoryContent — identity / navigation
    // -------------------------------------------------------------------------

    @Override
    public String getId()
    {
        return repository.getId();
    }

    @Override
    public ManagedRepository getRepository()
    {
        return repository;
    }

    @Override
    public void setRepository( ManagedRepository repo )
    {
        this.repository = repo;
    }

    // -------------------------------------------------------------------------
    // RepositoryContent — path / selector conversions
    // -------------------------------------------------------------------------

    /**
     * Converts an ItemSelector to an npm storage path.
     * namespace → scope, projectId → package name, version → tarball path.
     */
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
        return base + "/package.json";
    }

    /**
     * Parses an npm storage path back into an ItemSelector.
     *
     * <p>Supported patterns:
     * <ul>
     *   <li>{@code name/-/name-version.tgz}         → unscoped tarball</li>
     *   <li>{@code @scope/name/-/name-version.tgz}  → scoped tarball</li>
     *   <li>{@code name/package.json}               → unscoped metadata</li>
     *   <li>{@code @scope/name/package.json}        → scoped metadata</li>
     *   <li>{@code name}                            → unscoped package dir</li>
     *   <li>{@code @scope/name}                     → scoped package dir</li>
     *   <li>{@code @scope}                          → scope dir</li>
     * </ul>
     */
    @Override
    public ItemSelector toItemSelector( String path ) throws LayoutException
    {
        if ( path == null || path.isEmpty() )
        {
            throw new LayoutException( "Empty path" );
        }
        String p = path.startsWith( "/" ) ? path.substring( 1 ) : path;
        String[] parts = p.split( "/" );

        ArchivaItemSelector.Builder builder = ArchivaItemSelector.builder();

        if ( parts[0].startsWith( "@" ) )
        {
            // Scoped package
            builder.withNamespace( parts[0] );
            if ( parts.length >= 2 )
            {
                builder.withProjectId( parts[1] );
            }
            // @scope/name/-/name-version.tgz
            if ( parts.length == 4 && "-".equals( parts[2] ) )
            {
                builder.withVersion( extractVersion( parts[1], parts[3] ) );
            }
        }
        else
        {
            // Unscoped package
            builder.withProjectId( parts[0] );
            // name/-/name-version.tgz
            if ( parts.length == 3 && "-".equals( parts[1] ) )
            {
                builder.withVersion( extractVersion( parts[0], parts[2] ) );
            }
        }

        return builder.build();
    }

    @Override
    public ContentItem toItem( String path ) throws LayoutException
    {
        StorageAsset asset = repository.getAsset( path );
        return new NpmContentItem( path, asset, this );
    }

    @Override
    public ContentItem toItem( StorageAsset asset ) throws LayoutException
    {
        return new NpmContentItem( asset.getPath(), asset, this );
    }

    @Override
    public String toPath( ContentItem item )
    {
        return item.getAsset().getPath();
    }

    @Override
    public boolean hasContent( ItemSelector selector )
    {
        return repository.getAsset( toPath( selector ) ).exists();
    }

    @Override
    public ContentItem getItem( ItemSelector selector ) throws ContentAccessException, IllegalArgumentException
    {
        String path = toPath( selector );
        StorageAsset asset = repository.getAsset( path );
        return new NpmContentItem( path, asset, this );
    }

    // -------------------------------------------------------------------------
    // ManagedRepositoryContent — streaming
    // -------------------------------------------------------------------------

    /**
     * Returns a stream of content items matching the selector.
     *
     * <p>Start directory is chosen from the most specific selector coordinate:
     * namespace+projectId → package dir; namespace only → scope dir; otherwise → repo root.
     * Only leaf assets (files) are included. Version filtering narrows results to a specific
     * tarball when {@code selector.hasVersion()} is true.
     */
    @Override
    public Stream<? extends ContentItem> newItemStream( ItemSelector selector, boolean parallel )
        throws ContentAccessException, IllegalArgumentException
    {
        StorageAsset startAsset = resolveStartAsset( selector );
        if ( !startAsset.exists() )
        {
            return Stream.empty();
        }

        Stream<StorageAsset> assetStream = StorageUtil.newAssetStream( startAsset, parallel )
            .filter( StorageAsset::isLeaf );

        if ( selector.hasVersion() )
        {
            final String versionSuffix = "-" + selector.getVersion() + ".tgz";
            assetStream = assetStream.filter( a -> a.getName().endsWith( versionSuffix ) );
        }

        return assetStream.map( a -> new NpmContentItem( a.getPath(), a, this ) );
    }

    // -------------------------------------------------------------------------
    // ManagedRepositoryContent — delete
    // -------------------------------------------------------------------------

    /**
     * Deletes all items matching the selector. Each deletion result is reported via {@code consumer}.
     */
    @Override
    public void deleteAllItems( ItemSelector selector, Consumer<ItemDeleteStatus> consumer )
        throws ContentAccessException, IllegalArgumentException
    {
        try ( Stream<? extends ContentItem> stream = newItemStream( selector, false ) )
        {
            stream.forEach( item -> {
                try
                {
                    deleteItem( item );
                    consumer.accept( new ItemDeleteStatus( item ) );
                }
                catch ( ItemNotFoundException e )
                {
                    consumer.accept( new ItemDeleteStatus( item, ItemDeleteStatus.ITEM_NOT_FOUND, e ) );
                }
                catch ( Exception e )
                {
                    consumer.accept( new ItemDeleteStatus( item, ItemDeleteStatus.DELETION_FAILED, e ) );
                }
                catch ( Throwable e )
                {
                    consumer.accept( new ItemDeleteStatus( item, ItemDeleteStatus.UNKNOWN, e ) );
                }
            } );
        }
    }

    /**
     * Deletes the given item from the filesystem. Directories are removed recursively.
     * Throws if the item does not exist or its path escapes the repository root.
     */
    @Override
    public void deleteItem( ContentItem item ) throws ItemNotFoundException, ContentAccessException
    {
        if ( !item.getAsset().exists() )
        {
            throw new ItemNotFoundException( "Item does not exist in repository " + getId()
                + ": " + item.getAsset().getPath() );
        }
        final Path baseDirectory = repository.getRoot().getFilePath();
        final Path itemPath = item.getAsset().getFilePath();
        if ( !itemPath.toAbsolutePath().startsWith( baseDirectory.toAbsolutePath() ) )
        {
            log.error( "Item path {} is outside repository {} root {}", itemPath, getId(), baseDirectory );
            throw new ContentAccessException( "Item path is outside repository root. Cannot delete." );
        }
        try
        {
            if ( Files.isDirectory( itemPath ) )
            {
                FileUtils.deleteDirectory( itemPath );
            }
            else
            {
                Files.deleteIfExists( itemPath );
            }
        }
        catch ( IOException e )
        {
            log.error( "Could not delete item {}: {}", itemPath, e.getMessage(), e );
            throw new ContentAccessException( "Error deleting item " + item.getAsset().getPath()
                + ": " + e.getMessage(), e );
        }
    }

    // -------------------------------------------------------------------------
    // ManagedRepositoryContent — copy
    // -------------------------------------------------------------------------

    @Override
    public void copyItem( ContentItem item, ManagedRepository destinationRepository )
        throws ItemNotFoundException, ContentAccessException
    {
        copyItem( item, destinationRepository, false );
    }

    /**
     * Copies the item to {@code destinationRepository} by preserving the relative path under the
     * repository root. Directories are copied recursively. The {@code updateMetadata} flag is
     * currently unused (metadata updates are deferred to the scan consumer).
     */
    @Override
    public void copyItem( ContentItem item, ManagedRepository destinationRepository, boolean updateMetadata )
        throws ItemNotFoundException, ContentAccessException
    {
        if ( !item.getAsset().exists() )
        {
            throw new ItemNotFoundException( "Item does not exist: " + item.getAsset().getPath() );
        }
        final Path srcRoot = repository.getRoot().getFilePath();
        final Path srcPath = item.getAsset().getFilePath();
        final Path relative = srcRoot.relativize( srcPath );
        final Path dstPath = destinationRepository.getRoot().getFilePath().resolve( relative );
        try
        {
            if ( Files.isDirectory( srcPath ) )
            {
                copyDirectory( srcPath, dstPath );
            }
            else
            {
                Files.createDirectories( dstPath.getParent() );
                Files.copy( srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING );
            }
        }
        catch ( IOException e )
        {
            throw new ContentAccessException( "Error copying item " + item.getAsset().getPath()
                + ": " + e.getMessage(), e );
        }
    }

    // -------------------------------------------------------------------------
    // ManagedRepositoryContent — navigation
    // -------------------------------------------------------------------------

    @Override
    public ContentItem getParent( ContentItem item )
    {
        StorageAsset parentAsset = item.getAsset().getParent();
        if ( parentAsset == null )
        {
            return null;
        }
        return new NpmContentItem( parentAsset.getPath(), parentAsset, this );
    }

    @Override
    public List<? extends ContentItem> getChildren( ContentItem item )
    {
        if ( item.getAsset().isLeaf() )
        {
            return Collections.emptyList();
        }
        return item.getAsset().list().stream()
            .map( a -> new NpmContentItem( a.getPath(), a, this ) )
            .collect( Collectors.toList() );
    }

    // -------------------------------------------------------------------------
    // ManagedRepositoryContent — characteristics / layout
    // -------------------------------------------------------------------------

    /**
     * Applies a characteristic to a content item. For NPM the only supported target is
     * {@code ContentItem} itself (identity). More specific Maven-style characteristics
     * (Namespace, Project, Version, Artifact) are not part of the NPM layout.
     */
    @Override
    @SuppressWarnings( "unchecked" )
    public <T extends ContentItem> T applyCharacteristic( Class<T> clazz, ContentItem item ) throws LayoutException
    {
        if ( clazz.isAssignableFrom( item.getClass() ) )
        {
            item.setCharacteristic( clazz, clazz.cast( item ) );
            return clazz.cast( item );
        }
        throw new LayoutException( "Cannot apply characteristic " + clazz.getName()
            + " to NPM content item" );
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public <T extends ManagedRepositoryContentLayout> T getLayout( Class<T> clazz ) throws LayoutException
    {
        if ( clazz.isAssignableFrom( this.getClass() ) )
        {
            return (T) this;
        }
        throw new LayoutException( "Layout " + clazz.getName() + " is not supported by NPM repository" );
    }

    @Override
    public <T extends ManagedRepositoryContentLayout> boolean supportsLayout( Class<T> clazz )
    {
        return clazz.isAssignableFrom( this.getClass() );
    }

    @Override
    public List<Class<? extends ManagedRepositoryContentLayout>> getSupportedLayouts()
    {
        return Collections.singletonList( NpmRepositoryContentLayout.class );
    }

    // -------------------------------------------------------------------------
    // ManagedRepositoryContentLayout (NpmRepositoryContentLayout base)
    // -------------------------------------------------------------------------

    @Override
    public ManagedRepositoryContent getGenericContent()
    {
        return this;
    }

    @Override
    public <T extends ContentItem> T adaptItem( Class<T> clazz, ContentItem item ) throws LayoutException
    {
        return applyCharacteristic( clazz, item );
    }

    // -------------------------------------------------------------------------
    // NpmRepositoryContentLayout — NPM-specific access
    // -------------------------------------------------------------------------

    @Override
    public ContentItem getPackage( ItemSelector selector ) throws ContentAccessException, ItemNotFoundException
    {
        String path = buildPackagePath( selector );
        StorageAsset asset = repository.getAsset( path );
        if ( !asset.exists() )
        {
            throw new ItemNotFoundException( "Package not found: " + path );
        }
        return new NpmContentItem( path, asset, this );
    }

    @Override
    public ContentItem getTarball( ItemSelector selector ) throws ContentAccessException, ItemNotFoundException
    {
        if ( !selector.hasVersion() )
        {
            throw new ContentAccessException( "Version must be specified to retrieve a tarball" );
        }
        String path = toPath( selector );
        StorageAsset asset = repository.getAsset( path );
        if ( !asset.exists() )
        {
            throw new ItemNotFoundException( "Tarball not found: " + path );
        }
        return new NpmContentItem( path, asset, this );
    }

    @Override
    public ContentItem getMetadataFile( ItemSelector selector ) throws ContentAccessException, ItemNotFoundException
    {
        String metaPath = buildPackagePath( selector ) + "/package.json";
        StorageAsset asset = repository.getAsset( metaPath );
        if ( !asset.exists() )
        {
            throw new ItemNotFoundException( "Metadata file not found: " + metaPath );
        }
        return new NpmContentItem( metaPath, asset, this );
    }

    /**
     * Lists all package directories in the repository. Scope directories (starting with {@code @})
     * are descended into so that their child package directories are included.
     */
    @Override
    public List<ContentItem> getPackages() throws ContentAccessException
    {
        List<ContentItem> packages = new ArrayList<>();
        for ( StorageAsset child : repository.getRoot().list() )
        {
            if ( !child.isContainer() )
            {
                continue;
            }
            if ( child.getName().startsWith( "@" ) )
            {
                for ( StorageAsset pkg : child.list() )
                {
                    if ( pkg.isContainer() )
                    {
                        packages.add( new NpmContentItem( pkg.getPath(), pkg, this ) );
                    }
                }
            }
            else
            {
                packages.add( new NpmContentItem( child.getPath(), child, this ) );
            }
        }
        return packages;
    }

    @Override
    public List<ContentItem> getTarballs( ItemSelector selector ) throws ContentAccessException
    {
        String tarDir = buildPackagePath( selector ) + "/-";
        StorageAsset dirAsset = repository.getAsset( tarDir );
        if ( !dirAsset.exists() || !dirAsset.isContainer() )
        {
            return Collections.emptyList();
        }
        return dirAsset.list().stream()
            .filter( a -> a.isLeaf() && a.getName().endsWith( ".tgz" ) )
            .map( a -> (ContentItem) new NpmContentItem( a.getPath(), a, this ) )
            .collect( Collectors.toList() );
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Extracts the version from a tarball filename: {@code name-version.tgz} → {@code version}. */
    private String extractVersion( String packageName, String filename )
    {
        String withoutExt = filename.endsWith( ".tgz" )
            ? filename.substring( 0, filename.length() - 4 )
            : filename;
        String prefix = packageName + "-";
        return withoutExt.startsWith( prefix )
            ? withoutExt.substring( prefix.length() )
            : withoutExt;
    }

    /** Returns the package directory path for a selector (without trailing slash). */
    private String buildPackagePath( ItemSelector selector )
    {
        String scope = selector.getNamespace();
        String name = selector.getProjectId();
        if ( scope != null && !scope.isEmpty() )
        {
            return scope + "/" + name;
        }
        return name != null ? name : "";
    }

    /** Determines the storage tree starting point from the selector coordinates. */
    private StorageAsset resolveStartAsset( ItemSelector selector )
    {
        if ( selector.hasNamespace() && selector.hasProjectId() )
        {
            return repository.getAsset( selector.getNamespace() + "/" + selector.getProjectId() );
        }
        if ( selector.hasNamespace() )
        {
            return repository.getAsset( selector.getNamespace() );
        }
        if ( selector.hasProjectId() )
        {
            return repository.getAsset( selector.getProjectId() );
        }
        return repository.getRoot();
    }

    /** Recursively copies a directory tree from {@code src} to {@code dst}. */
    private void copyDirectory( Path src, Path dst ) throws IOException
    {
        Files.walkFileTree( src, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory( Path dir, BasicFileAttributes attrs ) throws IOException
            {
                Files.createDirectories( dst.resolve( src.relativize( dir ) ) );
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) throws IOException
            {
                Files.copy( file, dst.resolve( src.relativize( file ) ), StandardCopyOption.REPLACE_EXISTING );
                return FileVisitResult.CONTINUE;
            }
        } );
    }
}
