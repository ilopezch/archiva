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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RpmRepodataCache} — JSON serialisation round-trip,
 * cache hit/miss semantics, and persistence via {@link Path}.
 */
class RpmRepodataCacheTest
{
    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Hit / miss semantics
    // -------------------------------------------------------------------------

    @Test
    void cacheMissOnUnknownFile()
    {
        RpmRepodataCache cache = new RpmRepodataCache();
        assertNull( cache.get( "RPMS/x86_64/foo-1.0-1.x86_64.rpm", 12345L ) );
    }

    @Test
    void cacheMissWhenMtimeDiffers()
    {
        RpmRepodataCache cache = new RpmRepodataCache();
        RpmPackageInfo info = makeInfo( "foo" );
        cache.put( "foo.rpm", 100L, info );

        assertNull( cache.get( "foo.rpm", 200L ) );  // stale mtime
    }

    @Test
    void cacheHitWhenMtimeMatches()
    {
        RpmRepodataCache cache = new RpmRepodataCache();
        RpmPackageInfo info = makeInfo( "foo" );
        cache.put( "foo.rpm", 100L, info );

        RpmPackageInfo hit = cache.get( "foo.rpm", 100L );
        assertNotNull( hit );
        assertEquals( "foo", hit.name );
    }

    // -------------------------------------------------------------------------
    // JSON round-trip
    // -------------------------------------------------------------------------

    @Test
    void roundTripPreservesScalarFields()
    {
        RpmRepodataCache cache = new RpmRepodataCache();
        RpmPackageInfo orig = makeInfo( "bash" );
        orig.epoch       = "2";
        orig.version     = "5.1.8";
        orig.release     = "6.el9";
        orig.arch        = "x86_64";
        orig.summary     = "The GNU Bourne Again shell";
        orig.description = "Multi-line\ndescription with \"quotes\" & <tags>";
        orig.license     = "GPLv3+";
        orig.group       = "System";
        orig.url         = "https://www.gnu.org/software/bash";
        orig.sourceRpm   = "bash-5.1.8-6.el9.src.rpm";
        orig.sha256      = "abcdef0123456789";
        orig.md5         = null;
        orig.location    = "RPMS/x86_64/bash-5.1.8-6.el9.x86_64.rpm";
        orig.fileSize    = 7654321L;
        orig.installedSize = 2048000L;
        orig.archiveSize   = 1024000L;
        orig.buildTime     = 1700000000L;

        cache.put( "bash.rpm", 999L, orig );

        String json = cache.encodeAll();
        RpmRepodataCache loaded = new RpmRepodataCache();
        loaded.decodeAllForTest( json );

        RpmPackageInfo restored = loaded.get( "bash.rpm", 999L );
        assertNotNull( restored );

        assertEquals( orig.name,          restored.name );
        assertEquals( orig.version,       restored.version );
        assertEquals( orig.release,       restored.release );
        assertEquals( orig.epoch,         restored.epoch );
        assertEquals( orig.arch,          restored.arch );
        assertEquals( orig.summary,       restored.summary );
        assertEquals( orig.description,   restored.description );
        assertEquals( orig.license,       restored.license );
        assertEquals( orig.url,           restored.url );
        assertEquals( orig.sha256,        restored.sha256 );
        assertNull(                        restored.md5 );
        assertEquals( orig.location,      restored.location );
        assertEquals( orig.fileSize,      restored.fileSize );
        assertEquals( orig.installedSize, restored.installedSize );
        assertEquals( orig.archiveSize,   restored.archiveSize );
        assertEquals( orig.buildTime,     restored.buildTime );
    }

    @Test
    void roundTripPreservesListFields()
    {
        RpmRepodataCache cache = new RpmRepodataCache();
        RpmPackageInfo orig = makeInfo( "libfoo" );
        orig.requireNames    = List.of( "libc.so.6", "libssl.so.3" );
        orig.requireVersions = List.of( "2.17",       "3.0" );
        orig.requireFlags    = List.of( 8, 12 );
        orig.provideNames    = List.of( "libfoo", "libfoo.so.1" );
        orig.provideVersions = List.of( "1.0",    "" );
        orig.provideFlags    = List.of( 8, 0 );
        orig.files           = List.of( "libfoo.so.1", "" );
        orig.dirNames        = List.of( "/usr/lib/" );
        orig.dirIndexes      = List.of( 0, 0 );
        orig.changelogTimes  = List.of( 1699920000L, 1667040000L );
        orig.changelogNames  = List.of( "Alice <a@x.com>", "Bob <b@x.com>" );
        orig.changelogTexts  = List.of( "- First", "- Second" );

        cache.put( "libfoo.rpm", 42L, orig );

        String json = cache.encodeAll();
        RpmRepodataCache loaded = new RpmRepodataCache();
        loaded.decodeAllForTest( json );

        RpmPackageInfo r = loaded.get( "libfoo.rpm", 42L );
        assertNotNull( r );

        assertEquals( orig.requireNames,    r.requireNames );
        assertEquals( orig.requireVersions, r.requireVersions );
        assertEquals( orig.requireFlags,    r.requireFlags );
        assertEquals( orig.provideNames,    r.provideNames );
        assertEquals( orig.provideVersions, r.provideVersions );
        assertEquals( orig.provideFlags,    r.provideFlags );
        assertEquals( orig.files,           r.files );
        assertEquals( orig.dirNames,        r.dirNames );
        assertEquals( orig.dirIndexes,      r.dirIndexes );
        assertEquals( orig.changelogTimes,  r.changelogTimes );
        assertEquals( orig.changelogNames,  r.changelogNames );
        assertEquals( orig.changelogTexts,  r.changelogTexts );
    }

    @Test
    void emptyListsRoundTrip()
    {
        RpmRepodataCache cache = new RpmRepodataCache();
        cache.put( "empty.rpm", 1L, makeInfo( "empty" ) );

        String json = cache.encodeAll();
        RpmRepodataCache loaded = new RpmRepodataCache();
        loaded.decodeAllForTest( json );

        RpmPackageInfo r = loaded.get( "empty.rpm", 1L );
        assertNotNull( r );
        assertTrue( r.requireNames.isEmpty() );
        assertTrue( r.files.isEmpty() );
        assertTrue( r.changelogTimes.isEmpty() );
    }

    // -------------------------------------------------------------------------
    // Persistence (save / load)
    // -------------------------------------------------------------------------

    @Test
    void saveAndLoadRoundTrip() throws Exception
    {
        Path cacheFile = tempDir.resolve( "cache.json" );

        RpmRepodataCache cache = new RpmRepodataCache();
        RpmPackageInfo info = makeInfo( "gzip" );
        info.sha256 = "feedcafe";
        cache.put( "RPMS/x86_64/gzip-1.12-1.x86_64.rpm", 1234567890L, info );
        cache.save( cacheFile );

        RpmRepodataCache loaded = RpmRepodataCache.load( cacheFile );
        RpmPackageInfo restored = loaded.get( "RPMS/x86_64/gzip-1.12-1.x86_64.rpm", 1234567890L );
        assertNotNull( restored );
        assertEquals( "gzip",     restored.name );
        assertEquals( "feedcafe", restored.sha256 );
    }

    @Test
    void loadFromNonexistentFileReturnsEmptyCache()
    {
        RpmRepodataCache cache = RpmRepodataCache.load( tempDir.resolve( "no-such-file.json" ) );
        assertNull( cache.get( "any.rpm", 0L ) );
    }

    @Test
    void loadFromCorruptJsonReturnsEmptyCache() throws Exception
    {
        Path cacheFile = tempDir.resolve( "broken.json" );
        java.nio.file.Files.write( cacheFile, "not-json".getBytes() );
        RpmRepodataCache cache = RpmRepodataCache.load( cacheFile );
        assertNotNull( cache );  // must not throw
    }

    // -------------------------------------------------------------------------
    // JSON string escaping
    // -------------------------------------------------------------------------

    @Test
    void jsonStringHandlesSpecialCharacters()
    {
        assertEquals( "\"hello\"",  RpmRepodataCache.jsonStr( "hello" ) );
        assertEquals( "\"a\\\"b\"", RpmRepodataCache.jsonStr( "a\"b" ) );
        assertEquals( "\"a\\\\b\"", RpmRepodataCache.jsonStr( "a\\b" ) );
        assertEquals( "\"a\\nb\"",  RpmRepodataCache.jsonStr( "a\nb" ) );
        assertEquals( "null",       RpmRepodataCache.jsonStr( null ) );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static RpmPackageInfo makeInfo( String name )
    {
        RpmPackageInfo p = new RpmPackageInfo();
        p.name    = name;
        p.version = "1.0";
        p.release = "1";
        p.arch    = "x86_64";
        return p;
    }

    // Expose decode for testing (same as the real method via package access)
    static void decodeAllForTestHelper( RpmRepodataCache cache, String json )
    {
        cache.decodeAllForTest( json );
    }
}
