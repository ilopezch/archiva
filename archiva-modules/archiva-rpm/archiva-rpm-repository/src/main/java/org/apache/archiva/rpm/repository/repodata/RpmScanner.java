package org.apache.archiva.rpm.repository.repodata;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Walks an RPM repository root and parses every {@code .rpm} file found under
 * {@code RPMS/} and {@code SRPMS/} sub-directories.
 *
 * <p>For each RPM:
 * <ol>
 *   <li>The binary header is parsed by {@link RpmHeaderParser}</li>
 *   <li>The SHA-256 checksum and file size are calculated from the full file bytes</li>
 *   <li>The repository-relative location path is recorded</li>
 * </ol>
 */
public final class RpmScanner
{
    private static final Logger log = LoggerFactory.getLogger( RpmScanner.class );

    private RpmScanner()
    {
    }

    /**
     * Scans all RPMs under {@code repoRoot} and returns their parsed metadata.
     *
     * @param repoRoot absolute path to the repository root
     */
    public static List<RpmPackageInfo> scan( Path repoRoot ) throws IOException
    {
        return scan( repoRoot, null );
    }

    /**
     * Scans all RPMs under {@code repoRoot}, serving unchanged files from {@code cache}
     * when the cached mtime matches.  Updated and new entries are written back into
     * {@code cache} so the caller can persist it afterward.
     *
     * @param repoRoot absolute path to the repository root
     * @param cache    repodata cache to consult/update, or {@code null} to disable caching
     */
    public static List<RpmPackageInfo> scan( Path repoRoot, RpmRepodataCache cache ) throws IOException
    {
        List<RpmPackageInfo> packages = new ArrayList<>();
        AtomicInteger hits   = new AtomicInteger();
        AtomicInteger misses = new AtomicInteger();
        scanDir( repoRoot, repoRoot.resolve( "RPMS" ),  packages, cache, hits, misses );
        scanDir( repoRoot, repoRoot.resolve( "SRPMS" ), packages, cache, hits, misses );
        if ( cache != null )
        {
            log.debug( "Cache hits={} misses={} in {}", hits.get(), misses.get(), repoRoot );
        }
        return packages;
    }

    private static void scanDir( Path repoRoot, Path dir, List<RpmPackageInfo> packages,
                                  RpmRepodataCache cache, AtomicInteger hits, AtomicInteger misses )
        throws IOException
    {
        if ( !Files.isDirectory( dir ) )
        {
            return;
        }
        Files.walkFileTree( dir, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) throws IOException
            {
                if ( !file.getFileName().toString().endsWith( ".rpm" ) )
                {
                    return FileVisitResult.CONTINUE;
                }
                try
                {
                    String relPath = repoRoot.relativize( file ).toString().replace( '\\', '/' );
                    long   mtime   = attrs.lastModifiedTime().toMillis();

                    if ( cache != null )
                    {
                        RpmPackageInfo cached = cache.get( relPath, mtime );
                        if ( cached != null )
                        {
                            packages.add( cached );
                            hits.incrementAndGet();
                            return FileVisitResult.CONTINUE;
                        }
                        misses.incrementAndGet();
                    }

                    RpmPackageInfo info = parseRpm( repoRoot, file, relPath, attrs );
                    if ( info != null )
                    {
                        packages.add( info );
                        if ( cache != null ) cache.put( relPath, mtime, info );
                    }
                }
                catch ( Exception e )
                {
                    log.warn( "Skipping unreadable RPM {}: {}", file, e.getMessage() );
                }
                return FileVisitResult.CONTINUE;
            }
        } );
    }

    private static RpmPackageInfo parseRpm( Path repoRoot, Path rpmFile,
                                             String relPath, BasicFileAttributes attrs ) throws IOException
    {
        RpmPackageInfo info;
        try ( InputStream in = Files.newInputStream( rpmFile ) )
        {
            info = RpmHeaderParser.parseHeader( in );
        }
        info.fileSize = attrs.size();
        info.sha256   = sha256( rpmFile );
        info.location = relPath;
        return info;
    }

    static String sha256( Path file ) throws IOException
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance( "SHA-256" );
            try ( InputStream in = new DigestInputStream( Files.newInputStream( file ), md ) )
            {
                byte[] buf = new byte[65536];
                while ( in.read( buf ) != -1 )
                {
                    // digest is updated by DigestInputStream
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for ( byte b : digest ) sb.append( String.format( "%02x", b ) );
            return sb.toString();
        }
        catch ( NoSuchAlgorithmException e )
        {
            throw new RuntimeException( "SHA-256 not available", e );
        }
    }
}
