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
 * RPM managed repository content. Maps RPM package coordinates to filesystem paths
 * and implements all content management operations.
 *
 * <p>Storage layout:
 * <ul>
 *   <li>Binary RPM: {@code RPMS/{arch}/{name}-{version}-{release}.{arch}.rpm}</li>
 *   <li>Source RPM: {@code SRPMS/{name}-{version}-{release}.src.rpm}</li>
 *   <li>Repodata:   {@code repodata/repomd.xml} (generated, not stored as content items)</li>
 * </ul>
 *
 * <p>ItemSelector field mapping:
 * <ul>
 *   <li>{@code namespace} → architecture ({@code x86_64}, {@code noarch}, {@code src}, …)</li>
 *   <li>{@code projectId} → package name</li>
 *   <li>{@code version}   → {@code version-release} string (e.g. {@code 1.0-1})</li>
 * </ul>
 */
public class RpmManagedRepositoryContent implements ManagedRepositoryContent, RpmRepositoryContentLayout
{
    /** Directory holding binary RPMs, partitioned by arch sub-directory. */
    public static final String RPMS_DIR = "RPMS";
    /** Directory holding source RPMs. */
    public static final String SRPMS_DIR = "SRPMS";
    /** Directory holding generated yum repodata. */
    public static final String REPODATA_DIR = "repodata";

    private ManagedRepository repository;

    public RpmManagedRepositoryContent( ManagedRepository repository )
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
                return SRPMS_DIR + "/" + name + "-" + version + ".src.rpm";
            }
            return SRPMS_DIR;
        }

        if ( arch != null && !arch.isEmpty() )
        {
            if ( name != null && !name.isEmpty() && version != null && !version.isEmpty() )
            {
                return RPMS_DIR + "/" + arch + "/" + name + "-" + version + "." + arch + ".rpm";
            }
            if ( name != null && !name.isEmpty() )
            {
                return RPMS_DIR + "/" + arch;
            }
            return RPMS_DIR + "/" + arch;
        }

        return RPMS_DIR;
    }

    @Override
    public ItemSelector toItemSelector( String path ) throws LayoutException
    {
        if ( path == null || path.isEmpty() )
        {
            throw new LayoutException( "Empty path" );
        }
        String p = path.startsWith( "/" ) ? path.substring( 1 ) : path;

        if ( p.startsWith( SRPMS_DIR + "/" ) )
        {
            String filename = p.substring( SRPMS_DIR.length() + 1 );
            return ArchivaItemSelector.builder()
                .withNamespace( "src" )
                .withProjectId( extractName( filename, ".src.rpm" ) )
                .withVersion( extractVersionRelease( filename, ".src.rpm" ) )
                .build();
        }

        if ( p.startsWith( RPMS_DIR + "/" ) )
        {
            String rest = p.substring( RPMS_DIR.length() + 1 );
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

        throw new LayoutException( "Unrecognised RPM storage path: " + path );
    }

    @Override
    public ContentItem toItem( String path ) throws LayoutException
    {
        StorageAsset asset = repository.getAsset( path );
        return new RpmContentItem( asset, this );
    }

    @Override
    public ContentItem toItem( StorageAsset asset ) throws LayoutException
    {
        return new RpmContentItem( asset, this );
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
        StorageAsset asset = repository.getAsset( toPath( selector ) );
        return new RpmContentItem( asset, this );
    }

    // -------------------------------------------------------------------------
    // ManagedRepositoryContent — streaming
    // -------------------------------------------------------------------------

    @Override
    public Stream<? extends ContentItem> newItemStream( ItemSelector selector, boolean parallel )
        throws ContentAccessException, IllegalArgumentException
    {
        String startPath = toPath( selector );
        StorageAsset start = repository.getAsset( startPath );
        if ( !start.exists() )
        {
            return Stream.empty();
        }
        return StorageUtil.newAssetStream( start, parallel )
            .filter( a -> a.isLeaf() && a.getName().endsWith( ".rpm" ) )
            .map( a -> (ContentItem) new RpmContentItem( a, this ) );
    }

    // -------------------------------------------------------------------------
    // ManagedRepositoryContent — delete
    // -------------------------------------------------------------------------

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

    @Override
    public void deleteItem( ContentItem item ) throws ItemNotFoundException, ContentAccessException
    {
        if ( !item.getAsset().exists() )
        {
            throw new ItemNotFoundException(
                "Item does not exist in repository " + getId() + ": " + item.getAsset().getPath() );
        }
        final Path baseDirectory = repository.getRoot().getFilePath();
        final Path itemPath = item.getAsset().getFilePath();
        if ( !itemPath.toAbsolutePath().startsWith( baseDirectory.toAbsolutePath() ) )
        {
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
            throw new ContentAccessException(
                "Error deleting item " + item.getAsset().getPath() + ": " + e.getMessage(), e );
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
            throw new ContentAccessException(
                "Error copying item " + item.getAsset().getPath() + ": " + e.getMessage(), e );
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
        return new RpmContentItem( parentAsset, this );
    }

    @Override
    public List<? extends ContentItem> getChildren( ContentItem item )
    {
        if ( item.getAsset().isLeaf() )
        {
            return Collections.emptyList();
        }
        return item.getAsset().list().stream()
            .map( a -> new RpmContentItem( a, this ) )
            .collect( Collectors.toList() );
    }

    // -------------------------------------------------------------------------
    // ManagedRepositoryContent — layout
    // -------------------------------------------------------------------------

    @Override
    @SuppressWarnings( "unchecked" )
    public <T extends ContentItem> T applyCharacteristic( Class<T> clazz, ContentItem item ) throws LayoutException
    {
        if ( clazz.isAssignableFrom( item.getClass() ) )
        {
            item.setCharacteristic( clazz, clazz.cast( item ) );
            return clazz.cast( item );
        }
        throw new LayoutException( "Cannot apply characteristic " + clazz.getName() + " to RPM content item" );
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public <T extends ManagedRepositoryContentLayout> T getLayout( Class<T> clazz ) throws LayoutException
    {
        if ( clazz.isAssignableFrom( this.getClass() ) )
        {
            return (T) this;
        }
        throw new LayoutException( "Layout " + clazz.getName() + " is not supported by RPM repository" );
    }

    @Override
    public <T extends ManagedRepositoryContentLayout> boolean supportsLayout( Class<T> clazz )
    {
        return clazz.isAssignableFrom( this.getClass() );
    }

    @Override
    public List<Class<? extends ManagedRepositoryContentLayout>> getSupportedLayouts()
    {
        return Collections.singletonList( RpmRepositoryContentLayout.class );
    }

    // -------------------------------------------------------------------------
    // ManagedRepositoryContentLayout (RpmRepositoryContentLayout base)
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
    // RpmRepositoryContentLayout — RPM-specific access
    // -------------------------------------------------------------------------

    @Override
    public ContentItem getRpmPackage( ItemSelector selector ) throws ContentAccessException, ItemNotFoundException
    {
        String path = toPath( selector );
        StorageAsset asset = repository.getAsset( path );
        if ( !asset.exists() )
        {
            throw new ItemNotFoundException( "RPM package not found: " + path );
        }
        return new RpmContentItem( asset, this );
    }

    @Override
    public List<ContentItem> getAllRpms() throws ContentAccessException
    {
        List<ContentItem> result = new ArrayList<>();
        collectRpms( repository.getAsset( RPMS_DIR ), result );
        collectRpms( repository.getAsset( SRPMS_DIR ), result );
        return result;
    }

    @Override
    public List<ContentItem> getRpmsForArch( ItemSelector selector ) throws ContentAccessException
    {
        String arch = selector.getNamespace();
        String dirPath = "src".equals( arch ) ? SRPMS_DIR : RPMS_DIR + "/" + arch;
        StorageAsset dir = repository.getAsset( dirPath );
        if ( !dir.exists() || !dir.isContainer() )
        {
            return Collections.emptyList();
        }
        return dir.list().stream()
            .filter( a -> a.isLeaf() && a.getName().endsWith( ".rpm" ) )
            .map( a -> (ContentItem) new RpmContentItem( a, this ) )
            .collect( Collectors.toList() );
    }

    @Override
    public ContentItem getRepodataDir() throws ContentAccessException
    {
        StorageAsset asset = repository.getAsset( REPODATA_DIR );
        return new RpmContentItem( asset, this );
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void collectRpms( StorageAsset dir, List<ContentItem> result )
    {
        if ( !dir.exists() || !dir.isContainer() )
        {
            return;
        }
        for ( StorageAsset child : dir.list() )
        {
            if ( child.isLeaf() && child.getName().endsWith( ".rpm" ) )
            {
                result.add( new RpmContentItem( child, this ) );
            }
            else if ( child.isContainer() )
            {
                collectRpms( child, result );
            }
        }
    }

    /**
     * Extracts the package name from an RPM filename by stripping the suffix and the
     * trailing {@code -{version}-{release}} portion.
     * E.g. {@code bash-5.1.8-6.el9.x86_64.rpm} with suffix {@code .x86_64.rpm} → {@code bash}.
     */
    private String extractName( String filename, String suffix )
    {
        String base = filename.endsWith( suffix )
            ? filename.substring( 0, filename.length() - suffix.length() )
            : filename;
        // base is now: name-version-release
        int last = base.lastIndexOf( '-' );
        if ( last > 0 )
        {
            base = base.substring( 0, last );
        }
        int second = base.lastIndexOf( '-' );
        return second > 0 ? base.substring( 0, second ) : base;
    }

    /**
     * Extracts the {@code version-release} portion from an RPM filename.
     * E.g. {@code bash-5.1.8-6.el9.x86_64.rpm} with suffix {@code .x86_64.rpm} → {@code 5.1.8-6.el9}.
     */
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
        String version = nameVer.substring( second + 1 );
        return version + "-" + release;
    }

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
