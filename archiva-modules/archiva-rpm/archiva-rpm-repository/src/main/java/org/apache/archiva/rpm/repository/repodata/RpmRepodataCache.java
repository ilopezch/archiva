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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maintains a JSON cache of parsed RPM package metadata keyed by repository-relative
 * filename.  Each entry also stores the file's last-modified time (milliseconds since
 * epoch) so stale entries can be detected without re-parsing.
 *
 * <p>Persisted to {@code .repodata/cache.json} under the repository root.  On a
 * rebuild, files whose mtime matches the cached value are served directly from the
 * cache; only new or modified RPMs are re-parsed and rehashed.
 *
 * <p>The JSON serialization is hand-rolled to avoid adding a new runtime dependency.
 */
final class RpmRepodataCache
{
    private static final Logger log = LoggerFactory.getLogger( RpmRepodataCache.class );

    private final Map<String, CacheEntry> entries = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    static RpmRepodataCache load( Path cacheFile )
    {
        RpmRepodataCache cache = new RpmRepodataCache();
        if ( !Files.exists( cacheFile ) )
        {
            return cache;
        }
        try
        {
            String json = new String( Files.readAllBytes( cacheFile ), StandardCharsets.UTF_8 );
            cache.decodeAll( json );
            log.debug( "Loaded {} entries from repodata cache {}", cache.entries.size(), cacheFile );
        }
        catch ( Exception e )
        {
            log.warn( "Could not read repodata cache {} — starting fresh: {}", cacheFile, e.getMessage() );
        }
        return cache;
    }

    void save( Path cacheFile )
    {
        try
        {
            byte[] bytes = encodeAll().getBytes( StandardCharsets.UTF_8 );
            Files.write( cacheFile, bytes,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING );
        }
        catch ( IOException e )
        {
            log.warn( "Could not write repodata cache {}: {}", cacheFile, e.getMessage() );
        }
    }

    /**
     * Returns the cached {@link RpmPackageInfo} for {@code filename} if the cached
     * mtime matches {@code mtime}, otherwise returns {@code null} (cache miss).
     */
    RpmPackageInfo get( String filename, long mtime )
    {
        CacheEntry e = entries.get( filename );
        return ( e != null && e.mtime == mtime ) ? e.info : null;
    }

    void put( String filename, long mtime, RpmPackageInfo info )
    {
        CacheEntry e = new CacheEntry();
        e.mtime = mtime;
        e.info  = info;
        entries.put( filename, e );
    }

    // -------------------------------------------------------------------------
    // JSON encode
    // -------------------------------------------------------------------------

    String encodeAll()
    {
        StringBuilder sb = new StringBuilder( "{\n" );
        Iterator<Map.Entry<String, CacheEntry>> it = entries.entrySet().iterator();
        while ( it.hasNext() )
        {
            Map.Entry<String, CacheEntry> pair = it.next();
            sb.append( "  " ).append( jsonStr( pair.getKey() ) ).append( ": " );
            encodeEntry( sb, pair.getValue() );
            if ( it.hasNext() ) sb.append( "," );
            sb.append( "\n" );
        }
        sb.append( "}" );
        return sb.toString();
    }

    private static void encodeEntry( StringBuilder sb, CacheEntry e )
    {
        RpmPackageInfo p = e.info;
        sb.append( "{" );
        kv( sb, "mtime",          e.mtime );               sb.append( "," );
        kv( sb, "fileSize",       p.fileSize );             sb.append( "," );
        kv( sb, "sha256",         p.sha256 );               sb.append( "," );
        kv( sb, "md5",            p.md5 );                  sb.append( "," );
        kv( sb, "location",       p.location );             sb.append( "," );
        kv( sb, "name",           p.name );                 sb.append( "," );
        kv( sb, "version",        p.version );              sb.append( "," );
        kv( sb, "release",        p.release );              sb.append( "," );
        kv( sb, "epoch",          p.epoch );                sb.append( "," );
        kv( sb, "arch",           p.arch );                 sb.append( "," );
        kv( sb, "summary",        p.summary );              sb.append( "," );
        kv( sb, "description",    p.description );          sb.append( "," );
        kv( sb, "license",        p.license );              sb.append( "," );
        kv( sb, "group",          p.group );                sb.append( "," );
        kv( sb, "url",            p.url );                  sb.append( "," );
        kv( sb, "vendor",         p.vendor );               sb.append( "," );
        kv( sb, "packager",       p.packager );             sb.append( "," );
        kv( sb, "sourceRpm",      p.sourceRpm );            sb.append( "," );
        kv( sb, "installedSize",  p.installedSize );        sb.append( "," );
        kv( sb, "archiveSize",    p.archiveSize );          sb.append( "," );
        kv( sb, "buildTime",      p.buildTime );            sb.append( "," );
        kva( sb, "requireNames",    p.requireNames );       sb.append( "," );
        kva( sb, "requireVersions", p.requireVersions );   sb.append( "," );
        kvn( sb, "requireFlags",    p.requireFlags );       sb.append( "," );
        kva( sb, "provideNames",    p.provideNames );       sb.append( "," );
        kva( sb, "provideVersions", p.provideVersions );   sb.append( "," );
        kvn( sb, "provideFlags",    p.provideFlags );       sb.append( "," );
        kva( sb, "files",           p.files );              sb.append( "," );
        kva( sb, "dirNames",        p.dirNames );           sb.append( "," );
        kvn( sb, "dirIndexes",      p.dirIndexes );         sb.append( "," );
        kvl( sb, "changelogTimes",  p.changelogTimes );     sb.append( "," );
        kva( sb, "changelogNames",  p.changelogNames );     sb.append( "," );
        kva( sb, "changelogTexts",  p.changelogTexts );
        sb.append( "}" );
    }

    private static void kv( StringBuilder sb, String key, String value )
    {
        sb.append( jsonStr( key ) ).append( ":" ).append( jsonStr( value ) );
    }

    private static void kv( StringBuilder sb, String key, long value )
    {
        sb.append( jsonStr( key ) ).append( ":" ).append( value );
    }

    private static void kva( StringBuilder sb, String key, List<String> list )
    {
        sb.append( jsonStr( key ) ).append( ":[" );
        if ( list != null )
        {
            for ( int i = 0; i < list.size(); i++ )
            {
                if ( i > 0 ) sb.append( "," );
                sb.append( jsonStr( list.get( i ) ) );
            }
        }
        sb.append( "]" );
    }

    private static void kvn( StringBuilder sb, String key, List<Integer> list )
    {
        sb.append( jsonStr( key ) ).append( ":[" );
        if ( list != null )
        {
            for ( int i = 0; i < list.size(); i++ )
            {
                if ( i > 0 ) sb.append( "," );
                sb.append( list.get( i ) );
            }
        }
        sb.append( "]" );
    }

    private static void kvl( StringBuilder sb, String key, List<Long> list )
    {
        sb.append( jsonStr( key ) ).append( ":[" );
        if ( list != null )
        {
            for ( int i = 0; i < list.size(); i++ )
            {
                if ( i > 0 ) sb.append( "," );
                sb.append( list.get( i ) );
            }
        }
        sb.append( "]" );
    }

    static String jsonStr( String s )
    {
        if ( s == null ) return "null";
        StringBuilder sb = new StringBuilder( "\"" );
        for ( int i = 0; i < s.length(); i++ )
        {
            char c = s.charAt( i );
            if      ( c == '"'  ) sb.append( "\\\"" );
            else if ( c == '\\' ) sb.append( "\\\\" );
            else if ( c == '\n' ) sb.append( "\\n" );
            else if ( c == '\r' ) sb.append( "\\r" );
            else if ( c == '\t' ) sb.append( "\\t" );
            else if ( c < 0x20  ) sb.append( String.format( "\\u%04x", (int) c ) );
            else                  sb.append( c );
        }
        sb.append( "\"" );
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // JSON decode
    // -------------------------------------------------------------------------

    /** Package-private entry point used by tests to decode JSON into this cache. */
    void decodeAllForTest( String json )
    {
        decodeAll( json );
    }

    private void decodeAll( String json )
    {
        JsonReader r = new JsonReader( json );
        Map<String, Object> outer = r.readObject();
        for ( Map.Entry<String, Object> pair : outer.entrySet() )
        {
            if ( !( pair.getValue() instanceof Map ) ) continue;
            @SuppressWarnings( "unchecked" )
            Map<String, Object> fields = (Map<String, Object>) pair.getValue();
            try
            {
                entries.put( pair.getKey(), decodeEntry( fields ) );
            }
            catch ( Exception ex )
            {
                log.warn( "Skipping corrupt cache entry for {}: {}", pair.getKey(), ex.getMessage() );
            }
        }
    }

    private static CacheEntry decodeEntry( Map<String, Object> f )
    {
        CacheEntry e  = new CacheEntry();
        e.mtime       = asLong( f.get( "mtime" ) );
        RpmPackageInfo p = new RpmPackageInfo();
        p.fileSize      = asLong( f.get( "fileSize" ) );
        p.sha256        = asStr( f.get( "sha256" ) );
        p.md5           = asStr( f.get( "md5" ) );
        p.location      = asStr( f.get( "location" ) );
        p.name          = asStr( f.get( "name" ) );
        p.version       = asStr( f.get( "version" ) );
        p.release       = asStr( f.get( "release" ) );
        p.epoch         = asStr( f.get( "epoch" ) );
        p.arch          = asStr( f.get( "arch" ) );
        p.summary       = asStr( f.get( "summary" ) );
        p.description   = asStr( f.get( "description" ) );
        p.license       = asStr( f.get( "license" ) );
        p.group         = asStr( f.get( "group" ) );
        p.url           = asStr( f.get( "url" ) );
        p.vendor        = asStr( f.get( "vendor" ) );
        p.packager      = asStr( f.get( "packager" ) );
        p.sourceRpm     = asStr( f.get( "sourceRpm" ) );
        p.installedSize = asLong( f.get( "installedSize" ) );
        p.archiveSize   = asLong( f.get( "archiveSize" ) );
        p.buildTime     = asLong( f.get( "buildTime" ) );
        p.requireNames    = asStrList( f.get( "requireNames" ) );
        p.requireVersions = asStrList( f.get( "requireVersions" ) );
        p.requireFlags    = asIntList( f.get( "requireFlags" ) );
        p.provideNames    = asStrList( f.get( "provideNames" ) );
        p.provideVersions = asStrList( f.get( "provideVersions" ) );
        p.provideFlags    = asIntList( f.get( "provideFlags" ) );
        p.files           = asStrList( f.get( "files" ) );
        p.dirNames        = asStrList( f.get( "dirNames" ) );
        p.dirIndexes      = asIntList( f.get( "dirIndexes" ) );
        p.changelogTimes  = asLongList( f.get( "changelogTimes" ) );
        p.changelogNames  = asStrList( f.get( "changelogNames" ) );
        p.changelogTexts  = asStrList( f.get( "changelogTexts" ) );
        e.info = p;
        return e;
    }

    private static String asStr( Object v )
    {
        return ( v == null ) ? null : v.toString();
    }

    private static long asLong( Object v )
    {
        if ( v instanceof Long    ) return (Long) v;
        if ( v instanceof Integer ) return ( (Integer) v ).longValue();
        if ( v == null            ) return 0L;
        try { return Long.parseLong( v.toString() ); } catch ( NumberFormatException e ) { return 0L; }
    }

    private static List<String> asStrList( Object v )
    {
        List<String> out = new ArrayList<>();
        if ( !( v instanceof List ) ) return out;
        for ( Object item : (List<?>) v ) out.add( item == null ? null : item.toString() );
        return out;
    }

    private static List<Integer> asIntList( Object v )
    {
        List<Integer> out = new ArrayList<>();
        if ( !( v instanceof List ) ) return out;
        for ( Object item : (List<?>) v )
        {
            if      ( item instanceof Long    ) out.add( ( (Long) item ).intValue() );
            else if ( item instanceof Integer ) out.add( (Integer) item );
            else if ( item != null ) try { out.add( Integer.parseInt( item.toString() ) ); } catch ( NumberFormatException ignored ) { }
        }
        return out;
    }

    private static List<Long> asLongList( Object v )
    {
        List<Long> out = new ArrayList<>();
        if ( !( v instanceof List ) ) return out;
        for ( Object item : (List<?>) v )
        {
            if      ( item instanceof Long    ) out.add( (Long) item );
            else if ( item instanceof Integer ) out.add( ( (Integer) item ).longValue() );
            else if ( item != null ) try { out.add( Long.parseLong( item.toString() ) ); } catch ( NumberFormatException ignored ) { }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Internal DTOs
    // -------------------------------------------------------------------------

    private static final class CacheEntry
    {
        long           mtime;
        RpmPackageInfo info;
    }

    // -------------------------------------------------------------------------
    // Minimal JSON reader (handles the subset we write: objects, arrays,
    // strings, and non-negative longs — no floating point, no booleans).
    // -------------------------------------------------------------------------

    static final class JsonReader
    {
        private final String src;
        private int pos;

        JsonReader( String src )
        {
            this.src = src;
            this.pos = 0;
        }

        Map<String, Object> readObject()
        {
            expect( '{' );
            Map<String, Object> map = new LinkedHashMap<>();
            ws();
            while ( pos < src.length() && src.charAt( pos ) != '}' )
            {
                String key = readString();
                ws(); expect( ':' );
                Object val = readValue();
                map.put( key, val );
                ws();
                if ( pos < src.length() && src.charAt( pos ) == ',' ) { pos++; ws(); }
            }
            expect( '}' );
            return map;
        }

        private List<Object> readArray()
        {
            expect( '[' );
            List<Object> list = new ArrayList<>();
            ws();
            while ( pos < src.length() && src.charAt( pos ) != ']' )
            {
                list.add( readValue() );
                ws();
                if ( pos < src.length() && src.charAt( pos ) == ',' ) { pos++; ws(); }
            }
            expect( ']' );
            return list;
        }

        private Object readValue()
        {
            ws();
            if ( pos >= src.length() ) return null;
            char c = src.charAt( pos );
            if ( c == '"' ) return readString();
            if ( c == '[' ) return readArray();
            if ( c == '{' ) return readObject();
            if ( c == 'n' ) { pos += 4; return null; }  // null
            return readLong();
        }

        private String readString()
        {
            expect( '"' );
            StringBuilder sb = new StringBuilder();
            while ( pos < src.length() && src.charAt( pos ) != '"' )
            {
                char c = src.charAt( pos++ );
                if ( c == '\\' && pos < src.length() )
                {
                    char esc = src.charAt( pos++ );
                    switch ( esc )
                    {
                        case '"':  sb.append( '"' );  break;
                        case '\\': sb.append( '\\' ); break;
                        case 'n':  sb.append( '\n' ); break;
                        case 'r':  sb.append( '\r' ); break;
                        case 't':  sb.append( '\t' ); break;
                        case 'u':
                            if ( pos + 4 <= src.length() )
                            {
                                String hex = src.substring( pos, pos + 4 );
                                pos += 4;
                                try { sb.append( (char) Integer.parseInt( hex, 16 ) ); }
                                catch ( NumberFormatException e ) { sb.append( "\\u" ).append( hex ); }
                            }
                            break;
                        default: sb.append( '\\' ).append( esc );
                    }
                }
                else
                {
                    sb.append( c );
                }
            }
            expect( '"' );
            return sb.toString();
        }

        private Long readLong()
        {
            int start = pos;
            if ( pos < src.length() && src.charAt( pos ) == '-' ) pos++;
            while ( pos < src.length() && Character.isDigit( src.charAt( pos ) ) ) pos++;
            String s = src.substring( start, pos );
            try { return Long.parseLong( s ); } catch ( NumberFormatException e ) { return 0L; }
        }

        private void ws()
        {
            while ( pos < src.length() && Character.isWhitespace( src.charAt( pos ) ) ) pos++;
        }

        private void expect( char c )
        {
            ws();
            if ( pos >= src.length() || src.charAt( pos ) != c )
                throw new IllegalStateException(
                    "Expected '" + c + "' at pos " + pos + " in JSON, found '"
                    + ( pos < src.length() ? src.charAt( pos ) : "EOF" ) + "'" );
            pos++;
        }
    }
}
