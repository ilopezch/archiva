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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Programmatically constructs minimal valid RPM binary data for use in unit tests.
 *
 * <p>RPM binary layout produced:
 * <pre>
 *   96-byte lead (RPM magic + zeros)
 *   Signature header section (minimal — 0 index entries)
 *   0-byte padding (signature section is exactly 16 bytes → 16 % 8 == 0)
 *   Main header section (tags supplied by caller)
 * </pre>
 *
 * <p>Only the tag types needed for our parser are supported:
 * INT32 (type 4), STRING (type 6), STRING_ARRAY (type 8).
 * INT32 entries in the store are 4-byte aligned automatically.
 */
public final class RpmTestFixtureBuilder
{
    // RPM tag type constants (subset used by our parser)
    public static final int TYPE_INT32        = 4;
    public static final int TYPE_STRING       = 6;
    public static final int TYPE_STRING_ARRAY = 8;

    // Selected header tag numbers (must match RpmHeaderParser constants)
    public static final int TAG_NAME           = 1000;
    public static final int TAG_VERSION        = 1001;
    public static final int TAG_RELEASE        = 1002;
    public static final int TAG_EPOCH          = 1003;
    public static final int TAG_SUMMARY        = 1004;
    public static final int TAG_DESCRIPTION    = 1005;
    public static final int TAG_BUILDTIME      = 1006;
    public static final int TAG_SIZE           = 1009;
    public static final int TAG_LICENSE        = 1014;
    public static final int TAG_GROUP          = 1016;
    public static final int TAG_URL            = 1020;
    public static final int TAG_ARCH           = 1022;
    public static final int TAG_SOURCERPM      = 1044;
    public static final int TAG_ARCHIVESIZE    = 1046;
    public static final int TAG_PROVIDES       = 1047;
    public static final int TAG_REQUIREFLAGS   = 1048;
    public static final int TAG_REQUIRES       = 1049;
    public static final int TAG_REQUIREVERSION = 1050;
    public static final int TAG_CHANGELOGTIME  = 1080;
    public static final int TAG_CHANGELOGNAME  = 1081;
    public static final int TAG_CHANGELOGTEXT  = 1082;
    public static final int TAG_PROVIDEFLAGS   = 1112;
    public static final int TAG_PROVIDEVERSION = 1113;
    public static final int TAG_BASENAMES      = 1117;
    public static final int TAG_DIRNAMES       = 1118;
    public static final int TAG_DIRINDEXES     = 1119;

    private final List<IndexEntry> entries = new ArrayList<>();
    private final ByteArrayOutputStream store = new ByteArrayOutputStream();

    private RpmTestFixtureBuilder()
    {
    }

    public static RpmTestFixtureBuilder builder()
    {
        return new RpmTestFixtureBuilder();
    }

    // -------------------------------------------------------------------------
    // Convenience setters
    // -------------------------------------------------------------------------

    public RpmTestFixtureBuilder name( String v )        { return addString( TAG_NAME,        v ); }
    public RpmTestFixtureBuilder version( String v )     { return addString( TAG_VERSION,     v ); }
    public RpmTestFixtureBuilder release( String v )     { return addString( TAG_RELEASE,     v ); }
    public RpmTestFixtureBuilder arch( String v )        { return addString( TAG_ARCH,        v ); }
    public RpmTestFixtureBuilder summary( String v )     { return addString( TAG_SUMMARY,     v ); }
    public RpmTestFixtureBuilder description( String v ) { return addString( TAG_DESCRIPTION, v ); }
    public RpmTestFixtureBuilder license( String v )     { return addString( TAG_LICENSE,     v ); }
    public RpmTestFixtureBuilder group( String v )       { return addString( TAG_GROUP,       v ); }
    public RpmTestFixtureBuilder url( String v )         { return addString( TAG_URL,         v ); }
    public RpmTestFixtureBuilder sourceRpm( String v )   { return addString( TAG_SOURCERPM,   v ); }
    public RpmTestFixtureBuilder epoch( int v )          { return addInt32(  TAG_EPOCH,       v ); }
    public RpmTestFixtureBuilder buildTime( int v )      { return addInt32(  TAG_BUILDTIME,   v ); }
    public RpmTestFixtureBuilder size( int v )           { return addInt32(  TAG_SIZE,        v ); }
    public RpmTestFixtureBuilder archiveSize( int v )    { return addInt32(  TAG_ARCHIVESIZE, v ); }

    public RpmTestFixtureBuilder files( String... basenames )
    {
        return addStringArray( TAG_BASENAMES, basenames );
    }

    public RpmTestFixtureBuilder dirNames( String... dirs )
    {
        return addStringArray( TAG_DIRNAMES, dirs );
    }

    public RpmTestFixtureBuilder dirIndexes( int... indexes )
    {
        return addInt32Array( TAG_DIRINDEXES, indexes );
    }

    public RpmTestFixtureBuilder requires( String[] names, String[] versions, int[] flags )
    {
        addStringArray( TAG_REQUIRES,       names    );
        addStringArray( TAG_REQUIREVERSION, versions );
        addInt32Array(  TAG_REQUIREFLAGS,   flags    );
        return this;
    }

    public RpmTestFixtureBuilder provides( String[] names, String[] versions, int[] flags )
    {
        addStringArray( TAG_PROVIDES,       names    );
        addStringArray( TAG_PROVIDEVERSION, versions );
        addInt32Array(  TAG_PROVIDEFLAGS,   flags    );
        return this;
    }

    public RpmTestFixtureBuilder changelog( long[] times, String[] authors, String[] texts )
    {
        addInt32Array(  TAG_CHANGELOGTIME, toLongAsInt( times ) );
        addStringArray( TAG_CHANGELOGNAME, authors );
        addStringArray( TAG_CHANGELOGTEXT, texts   );
        return this;
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    /**
     * Constructs the RPM binary and returns it as a byte array.
     * The result is a valid RPM that {@link RpmHeaderParser#parseHeader} can read.
     */
    public byte[] build()
    {
        byte[] mainSection  = buildSection( entries, store.toByteArray() );
        byte[] sigSection   = buildMinimalSignatureSection();
        byte[] lead         = buildLead();

        // sigSize = sigSection.length; padding so that (sigSize % 8) == 0
        int sigSize = sigSection.length;
        int pad = ( 8 - ( sigSize % 8 ) ) % 8;

        ByteArrayOutputStream rpm = new ByteArrayOutputStream();
        write( rpm, lead );
        write( rpm, sigSection );
        for ( int i = 0; i < pad; i++ ) rpm.write( 0 );
        write( rpm, mainSection );
        return rpm.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Low-level tag adders
    // -------------------------------------------------------------------------

    public RpmTestFixtureBuilder addString( int tag, String value )
    {
        int offset = store.size();
        writeString( store, value );
        entries.add( new IndexEntry( tag, TYPE_STRING, offset, 1 ) );
        return this;
    }

    public RpmTestFixtureBuilder addInt32( int tag, int value )
    {
        int offset = align4();
        writeInt32( store, value );
        entries.add( new IndexEntry( tag, TYPE_INT32, offset, 1 ) );
        return this;
    }

    public RpmTestFixtureBuilder addInt32Array( int tag, int[] values )
    {
        int offset = align4();
        for ( int v : values ) writeInt32( store, v );
        entries.add( new IndexEntry( tag, TYPE_INT32, offset, values.length ) );
        return this;
    }

    public RpmTestFixtureBuilder addStringArray( int tag, String[] values )
    {
        int offset = store.size();
        for ( String s : values ) writeString( store, s );
        entries.add( new IndexEntry( tag, TYPE_STRING_ARRAY, offset, values.length ) );
        return this;
    }

    // -------------------------------------------------------------------------
    // Binary assembly helpers
    // -------------------------------------------------------------------------

    private static final byte[] HEADER_MAGIC = { (byte) 0x8e, (byte) 0xad, (byte) 0xe8, 0x01 };

    private static byte[] buildSection( List<IndexEntry> idx, byte[] storeBytes )
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write( out, HEADER_MAGIC );
        writeInt32( out, 0 );               // reserved
        writeInt32( out, idx.size() );      // nindex
        writeInt32( out, storeBytes.length ); // hsize
        for ( IndexEntry e : idx )
        {
            writeInt32( out, e.tag );
            writeInt32( out, e.type );
            writeInt32( out, e.offset );
            writeInt32( out, e.count );
        }
        write( out, storeBytes );
        return out.toByteArray();
    }

    private static byte[] buildMinimalSignatureSection()
    {
        // 0 index entries, 0 store bytes → total = 4+4+4+4 = 16 bytes
        return buildSection( new ArrayList<>(), new byte[0] );
    }

    private static byte[] buildLead()
    {
        byte[] lead = new byte[96];
        lead[0] = (byte) 0xed;  // RPM lead magic
        lead[1] = (byte) 0xab;
        lead[2] = (byte) 0xee;
        lead[3] = (byte) 0xdb;
        lead[4] = 3;            // major version
        return lead;
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private int align4()
    {
        int pos = store.size();
        int aligned = ( pos + 3 ) & ~3;
        while ( store.size() < aligned ) store.write( 0 );
        return aligned;
    }

    private static void writeString( ByteArrayOutputStream out, String s )
    {
        byte[] bytes = ( s == null ? "" : s ).getBytes( StandardCharsets.UTF_8 );
        out.write( bytes, 0, bytes.length );
        out.write( 0 ); // null terminator
    }

    static void writeInt32( ByteArrayOutputStream out, int value )
    {
        out.write( ( value >> 24 ) & 0xff );
        out.write( ( value >> 16 ) & 0xff );
        out.write( ( value >> 8  ) & 0xff );
        out.write(   value         & 0xff );
    }

    private static void write( ByteArrayOutputStream out, byte[] bytes )
    {
        out.write( bytes, 0, bytes.length );
    }

    private static int[] toLongAsInt( long[] values )
    {
        int[] result = new int[values.length];
        for ( int i = 0; i < values.length; i++ ) result[i] = (int) values[i];
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal DTO
    // -------------------------------------------------------------------------

    private static final class IndexEntry
    {
        final int tag, type, offset, count;

        IndexEntry( int tag, int type, int offset, int count )
        {
            this.tag    = tag;
            this.type   = type;
            this.offset = offset;
            this.count  = count;
        }
    }
}
