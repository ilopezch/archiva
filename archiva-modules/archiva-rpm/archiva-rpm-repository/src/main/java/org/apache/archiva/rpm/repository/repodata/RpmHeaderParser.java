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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-Java parser for the binary RPM header section.
 *
 * <p>RPM binary format (in order):
 * <ol>
 *   <li>96-byte Lead (magic 0xed 0xab 0xee 0xdb + metadata)</li>
 *   <li>Signature header section (magic 0x8e 0xad 0xe8 0x01)</li>
 *   <li>Padding to 8-byte alignment (from the start of the signature)</li>
 *   <li>Main header section (magic 0x8e 0xad 0xe8 0x01)</li>
 * </ol>
 *
 * <p>Each header section layout:
 * <pre>
 *   3 bytes magic: 0x8e 0xad 0xe8
 *   1 byte version: 0x01
 *   4 bytes reserved (zeros)
 *   4 bytes nindex  (big-endian — number of index entries)
 *   4 bytes hsize   (big-endian — size of the store area in bytes)
 *   nindex × 16 bytes index: tag(4) type(4) offset(4) count(4)
 *   hsize bytes store area
 * </pre>
 */
public class RpmHeaderParser
{
    private static final byte[] HEADER_MAGIC = { (byte) 0x8e, (byte) 0xad, (byte) 0xe8, 0x01 };
    private static final int LEAD_SIZE = 96;

    // Selected RPM header tag constants
    private static final int TAG_NAME           = 1000;
    private static final int TAG_VERSION        = 1001;
    private static final int TAG_RELEASE        = 1002;
    private static final int TAG_EPOCH          = 1003;
    private static final int TAG_SUMMARY        = 1004;
    private static final int TAG_DESCRIPTION    = 1005;
    private static final int TAG_BUILDTIME      = 1006;
    private static final int TAG_SIZE           = 1009;
    private static final int TAG_LICENSE        = 1014;
    private static final int TAG_GROUP          = 1016;
    private static final int TAG_URL            = 1020;
    private static final int TAG_ARCH           = 1022;
    private static final int TAG_SOURCERPM      = 1044;
    private static final int TAG_ARCHIVESIZE    = 1046;
    private static final int TAG_PROVIDES       = 1047;
    private static final int TAG_REQUIREFLAGS   = 1048;
    private static final int TAG_REQUIRES       = 1049;
    private static final int TAG_REQUIREVERSION = 1050;
    private static final int TAG_PROVIDEFLAGS   = 1112;
    private static final int TAG_PROVIDEVERSION = 1113;
    private static final int TAG_BASENAMES      = 1117;

    /**
     * Parses the main RPM header and returns populated {@link RpmPackageInfo}.
     * File-level fields (sha256, md5, fileSize, location) are NOT set here;
     * the caller must populate those after reading the whole file.
     *
     * @param in stream positioned at byte 0 of the RPM file
     */
    public static RpmPackageInfo parseHeader( InputStream in ) throws IOException
    {
        DataInputStream dis = new DataInputStream( in );

        // 1. Skip lead
        skipFully( dis, LEAD_SIZE );

        // 2. Read + discard signature section; track its total byte size for alignment
        int sigSize = readAndDiscardSection( dis );

        // 3. Align to 8-byte boundary from the start of the signature section.
        //    Total bytes consumed by sig section: sigSize. Padding to next multiple of 8.
        int pad = ( 8 - ( sigSize % 8 ) ) % 8;
        skipFully( dis, pad );

        // 4. Read main header
        IndexedSection main = readSection( dis );
        return extractInfo( main );
    }

    // -------------------------------------------------------------------------
    // Section reading
    // -------------------------------------------------------------------------

    /**
     * Reads and discards a header section. Returns the total bytes consumed
     * (including the 16-byte preamble, index, and store).
     */
    private static int readAndDiscardSection( DataInputStream dis ) throws IOException
    {
        byte[] magic = new byte[4];
        dis.readFully( magic );
        if ( !Arrays.equals( magic, HEADER_MAGIC ) )
        {
            throw new IOException( "Expected RPM header magic at signature section, got: " + hex( magic ) );
        }
        skipFully( dis, 4 ); // reserved
        int nindex = dis.readInt();
        int hsize  = dis.readInt();
        int dataSize = nindex * 16 + hsize;
        skipFully( dis, dataSize );
        return 4 + 4 + 4 + 4 + dataSize; // magic + reserved + nindex + hsize + data
    }

    /**
     * Reads a full header section and returns its index entries + store.
     */
    private static IndexedSection readSection( DataInputStream dis ) throws IOException
    {
        byte[] magic = new byte[4];
        dis.readFully( magic );
        if ( !Arrays.equals( magic, HEADER_MAGIC ) )
        {
            throw new IOException( "Expected RPM header magic at main section, got: " + hex( magic ) );
        }
        skipFully( dis, 4 ); // reserved
        int nindex = dis.readInt();
        int hsize  = dis.readInt();

        IndexEntry[] entries = new IndexEntry[nindex];
        for ( int i = 0; i < nindex; i++ )
        {
            entries[i] = new IndexEntry( dis.readInt(), dis.readInt(), dis.readInt(), dis.readInt() );
        }
        byte[] store = new byte[hsize];
        dis.readFully( store );

        return new IndexedSection( entries, store );
    }

    // -------------------------------------------------------------------------
    // Info extraction
    // -------------------------------------------------------------------------

    private static RpmPackageInfo extractInfo( IndexedSection sec )
    {
        RpmPackageInfo info = new RpmPackageInfo();
        ByteBuffer store = ByteBuffer.wrap( sec.store ).order( ByteOrder.BIG_ENDIAN );

        for ( IndexEntry e : sec.entries )
        {
            switch ( e.tag )
            {
                case TAG_NAME:          info.name        = str( store, e.offset ); break;
                case TAG_VERSION:       info.version     = str( store, e.offset ); break;
                case TAG_RELEASE:       info.release     = str( store, e.offset ); break;
                case TAG_EPOCH:         info.epoch       = String.valueOf( i32( store, e.offset ) ); break;
                case TAG_SUMMARY:       info.summary     = str( store, e.offset ); break;
                case TAG_DESCRIPTION:   info.description = str( store, e.offset ); break;
                case TAG_BUILDTIME:     info.buildTime   = ui32( store, e.offset ); break;
                case TAG_SIZE:          info.installedSize = ui32( store, e.offset ); break;
                case TAG_ARCHIVESIZE:   info.archiveSize = ui32( store, e.offset ); break;
                case TAG_LICENSE:       info.license     = str( store, e.offset ); break;
                case TAG_GROUP:         info.group       = str( store, e.offset ); break;
                case TAG_URL:           info.url         = str( store, e.offset ); break;
                case TAG_ARCH:          info.arch        = str( store, e.offset ); break;
                case TAG_SOURCERPM:     info.sourceRpm   = str( store, e.offset ); break;
                case TAG_REQUIRES:      info.requireNames    = strArr( store, e.offset, e.count ); break;
                case TAG_REQUIREVERSION:info.requireVersions = strArr( store, e.offset, e.count ); break;
                case TAG_REQUIREFLAGS:  info.requireFlags    = i32Arr( store, e.offset, e.count ); break;
                case TAG_PROVIDES:      info.provideNames    = strArr( store, e.offset, e.count ); break;
                case TAG_PROVIDEVERSION:info.provideVersions = strArr( store, e.offset, e.count ); break;
                case TAG_PROVIDEFLAGS:  info.provideFlags    = i32Arr( store, e.offset, e.count ); break;
                case TAG_BASENAMES:     info.files           = strArr( store, e.offset, e.count ); break;
                default: break;
            }
        }
        return info;
    }

    // -------------------------------------------------------------------------
    // Store value readers
    // -------------------------------------------------------------------------

    private static String str( ByteBuffer store, int offset )
    {
        int end = offset;
        while ( end < store.capacity() && store.get( end ) != 0 ) end++;
        byte[] bytes = new byte[end - offset];
        store.position( offset );
        store.get( bytes );
        return new String( bytes, StandardCharsets.UTF_8 );
    }

    private static int i32( ByteBuffer store, int offset )
    {
        return store.getInt( offset );
    }

    private static long ui32( ByteBuffer store, int offset )
    {
        return Integer.toUnsignedLong( store.getInt( offset ) );
    }

    private static List<String> strArr( ByteBuffer store, int offset, int count )
    {
        List<String> result = new ArrayList<>( count );
        int pos = offset;
        for ( int i = 0; i < count; i++ )
        {
            int start = pos;
            while ( pos < store.capacity() && store.get( pos ) != 0 ) pos++;
            byte[] bytes = new byte[pos - start];
            store.position( start );
            store.get( bytes );
            result.add( new String( bytes, StandardCharsets.UTF_8 ) );
            pos++; // skip null terminator
        }
        return result;
    }

    private static List<Integer> i32Arr( ByteBuffer store, int offset, int count )
    {
        List<Integer> result = new ArrayList<>( count );
        for ( int i = 0; i < count; i++ ) result.add( store.getInt( offset + i * 4 ) );
        return result;
    }

    // -------------------------------------------------------------------------
    // Stream helpers
    // -------------------------------------------------------------------------

    private static void skipFully( DataInputStream dis, long n ) throws IOException
    {
        long remaining = n;
        while ( remaining > 0 )
        {
            long skipped = dis.skip( remaining );
            if ( skipped <= 0 )
            {
                // skip() may return 0 on some streams; fall back to read
                if ( dis.read() == -1 )
                {
                    throw new IOException( "Unexpected EOF while skipping in RPM stream" );
                }
                remaining--;
            }
            else
            {
                remaining -= skipped;
            }
        }
    }

    private static String hex( byte[] bytes )
    {
        StringBuilder sb = new StringBuilder();
        for ( byte b : bytes ) sb.append( String.format( "%02x", b ) );
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Internal DTOs
    // -------------------------------------------------------------------------

    private static final class IndexEntry
    {
        final int tag, type, offset, count;
        IndexEntry( int tag, int type, int offset, int count )
        {
            this.tag = tag; this.type = type; this.offset = offset; this.count = count;
        }
    }

    private static final class IndexedSection
    {
        final IndexEntry[] entries;
        final byte[]       store;
        IndexedSection( IndexEntry[] entries, byte[] store )
        {
            this.entries = entries; this.store = store;
        }
    }
}
