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

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds the {@code primary.xml} content for a set of RPM packages.
 * The output is the uncompressed XML bytes; compression is handled by
 * {@link RepomdGenerator}.
 *
 * <p>The generated XML conforms to the createrepo primary.xml schema
 * understood by yum/dnf/zypper clients.
 */
public final class PrimaryXmlBuilder
{
    private PrimaryXmlBuilder()
    {
    }

    public static byte[] build( List<RpmPackageInfo> packages )
    {
        StringBuilder sb = new StringBuilder( 4096 );
        sb.append( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" );
        sb.append( "<metadata xmlns=\"http://linux.duke.edu/metadata/common\"" );
        sb.append( " xmlns:rpm=\"http://linux.duke.edu/metadata/rpm\"" );
        sb.append( " packages=\"" ).append( packages.size() ).append( "\">\n" );

        for ( RpmPackageInfo p : packages )
        {
            appendPackage( sb, p );
        }

        sb.append( "</metadata>\n" );
        return sb.toString().getBytes( StandardCharsets.UTF_8 );
    }

    private static void appendPackage( StringBuilder sb, RpmPackageInfo p )
    {
        sb.append( "  <package type=\"rpm\">\n" );
        sb.append( "    <name>" ).append( esc( p.name ) ).append( "</name>\n" );
        sb.append( "    <arch>" ).append( esc( p.arch ) ).append( "</arch>\n" );

        // Version element: epoch defaults to "0"
        String epoch = ( p.epoch == null || p.epoch.isEmpty() ) ? "0" : p.epoch;
        sb.append( "    <version epoch=\"" ).append( epoch )
          .append( "\" ver=\"" ).append( esc( p.version ) )
          .append( "\" rel=\"" ).append( esc( p.release ) ).append( "\"/>\n" );

        // Checksum — use SHA-256 as pkgid (dnf prefers it; yum also accepts it)
        sb.append( "    <checksum type=\"sha256\" pkgid=\"YES\">" )
          .append( nvl( p.sha256 ) ).append( "</checksum>\n" );

        sb.append( "    <summary>" ).append( esc( p.summary ) ).append( "</summary>\n" );
        sb.append( "    <description>" ).append( esc( p.description ) ).append( "</description>\n" );
        sb.append( "    <packager>" ).append( esc( p.packager ) ).append( "</packager>\n" );
        sb.append( "    <url>" ).append( esc( p.url ) ).append( "</url>\n" );

        sb.append( "    <time file=\"" ).append( p.buildTime )
          .append( "\" build=\"" ).append( p.buildTime ).append( "\"/>\n" );

        sb.append( "    <size package=\"" ).append( p.fileSize )
          .append( "\" installed=\"" ).append( p.installedSize )
          .append( "\" archive=\"" ).append( p.archiveSize ).append( "\"/>\n" );

        sb.append( "    <location href=\"" ).append( esc( p.location ) ).append( "\"/>\n" );

        sb.append( "    <format>\n" );
        sb.append( "      <rpm:license>" ).append( esc( p.license ) ).append( "</rpm:license>\n" );
        sb.append( "      <rpm:vendor>" ).append( esc( p.vendor ) ).append( "</rpm:vendor>\n" );
        sb.append( "      <rpm:group>" ).append( esc( p.group ) ).append( "</rpm:group>\n" );
        sb.append( "      <rpm:buildhost/>\n" );
        sb.append( "      <rpm:sourcerpm>" ).append( esc( p.sourceRpm ) ).append( "</rpm:sourcerpm>\n" );
        sb.append( "      <rpm:header-range start=\"0\" end=\"0\"/>\n" );

        // Provides
        if ( !p.provideNames.isEmpty() )
        {
            sb.append( "      <rpm:provides>\n" );
            appendDeps( sb, p.provideNames, p.provideVersions, p.provideFlags );
            sb.append( "      </rpm:provides>\n" );
        }

        // Requires (filter out pre-install deps starting with rpmlib( for brevity)
        if ( !p.requireNames.isEmpty() )
        {
            sb.append( "      <rpm:requires>\n" );
            appendDeps( sb, p.requireNames, p.requireVersions, p.requireFlags );
            sb.append( "      </rpm:requires>\n" );
        }

        sb.append( "    </format>\n" );
        sb.append( "  </package>\n" );
    }

    private static void appendDeps( StringBuilder sb,
                                    List<String> names,
                                    List<String> versions,
                                    List<Integer> flags )
    {
        for ( int i = 0; i < names.size(); i++ )
        {
            String name    = names.get( i );
            String version = ( versions != null && i < versions.size() ) ? versions.get( i ) : "";
            int    flag    = ( flags != null && i < flags.size() ) ? flags.get( i ) : 0;

            sb.append( "        <rpm:entry name=\"" ).append( esc( name ) ).append( "\"" );
            if ( version != null && !version.isEmpty() )
            {
                sb.append( " flags=\"" ).append( flagsToString( flag ) ).append( "\"" );
                // Version string may be "epoch:ver-rel" or just "ver" or "ver-rel"
                String[] parts = parseEvr( version );
                sb.append( " epoch=\"" ).append( parts[0] ).append( "\"" );
                sb.append( " ver=\"" ).append( esc( parts[1] ) ).append( "\"" );
                sb.append( " rel=\"" ).append( esc( parts[2] ) ).append( "\"" );
            }
            sb.append( "/>\n" );
        }
    }

    /** Decomposes an EVR string into [epoch, version, release]. */
    private static String[] parseEvr( String evr )
    {
        String epoch = "0", version = evr, release = "";
        if ( evr.contains( ":" ) )
        {
            int colon = evr.indexOf( ':' );
            epoch   = evr.substring( 0, colon );
            version = evr.substring( colon + 1 );
        }
        int dash = version.lastIndexOf( '-' );
        if ( dash >= 0 )
        {
            release = version.substring( dash + 1 );
            version = version.substring( 0, dash );
        }
        return new String[]{ epoch, version, release };
    }

    private static String flagsToString( int flags )
    {
        // RPM sense flags: EQ=8, LT=2, GT=4, LE=10, GE=12
        switch ( flags & 0x0f )
        {
            case 2:  return "LT";
            case 4:  return "GT";
            case 8:  return "EQ";
            case 10: return "LE";
            case 12: return "GE";
            default: return "EQ";
        }
    }

    private static String esc( String s )
    {
        if ( s == null ) return "";
        return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" )
                .replace( "\"", "&quot;" );
    }

    private static String nvl( String s )
    {
        return s == null ? "" : s;
    }
}
