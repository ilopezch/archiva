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

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RpmHeaderParser} using synthetic RPM fixtures built by
 * {@link RpmTestFixtureBuilder}.  Exercises all major header tag categories
 * including epoch-bearing packages, source RPMs, noarch packages, changelog
 * entries, and full file lists with directory reconstruction data.
 */
class RpmHeaderParserTest
{
    // -------------------------------------------------------------------------
    // Basic package
    // -------------------------------------------------------------------------

    @Test
    void parsesBasicPackageFields() throws IOException
    {
        byte[] rpm = RpmTestFixtureBuilder.builder()
            .name( "libfoo" )
            .version( "1.2.3" )
            .release( "4.el9" )
            .arch( "x86_64" )
            .summary( "The foo library" )
            .description( "Full description of foo." )
            .license( "MIT" )
            .group( "System/Libraries" )
            .url( "https://example.com/foo" )
            .sourceRpm( "libfoo-1.2.3-4.el9.src.rpm" )
            .buildTime( 1700000000 )
            .size( 512000 )
            .archiveSize( 200000 )
            .build();

        RpmPackageInfo info = parse( rpm );

        assertEquals( "libfoo",                    info.name );
        assertEquals( "1.2.3",                     info.version );
        assertEquals( "4.el9",                     info.release );
        assertEquals( "x86_64",                    info.arch );
        assertEquals( "The foo library",           info.summary );
        assertEquals( "Full description of foo.",  info.description );
        assertEquals( "MIT",                       info.license );
        assertEquals( "System/Libraries",          info.group );
        assertEquals( "https://example.com/foo",   info.url );
        assertEquals( "libfoo-1.2.3-4.el9.src.rpm", info.sourceRpm );
        assertEquals( 1700000000L,                 info.buildTime );
        assertEquals( 512000L,                     info.installedSize );
        assertEquals( 200000L,                     info.archiveSize );
        assertNull( info.epoch );
    }

    // -------------------------------------------------------------------------
    // Epoch-bearing package
    // -------------------------------------------------------------------------

    @Test
    void parsesEpochBearingPackage() throws IOException
    {
        byte[] rpm = RpmTestFixtureBuilder.builder()
            .name( "openssh" )
            .version( "8.7p1" )
            .release( "34.el9" )
            .arch( "x86_64" )
            .epoch( 3 )
            .build();

        RpmPackageInfo info = parse( rpm );

        assertEquals( "openssh", info.name );
        assertEquals( "8.7p1",   info.version );
        assertEquals( "3",       info.epoch );
    }

    @Test
    void epochZeroIsPreserved() throws IOException
    {
        byte[] rpm = RpmTestFixtureBuilder.builder()
            .name( "bash" )
            .version( "5.1.8" )
            .release( "6.el9" )
            .arch( "x86_64" )
            .epoch( 0 )
            .build();

        RpmPackageInfo info = parse( rpm );
        assertEquals( "0", info.epoch );
    }

    // -------------------------------------------------------------------------
    // Source RPM
    // -------------------------------------------------------------------------

    @Test
    void parsesSourceRpm() throws IOException
    {
        byte[] rpm = RpmTestFixtureBuilder.builder()
            .name( "kernel" )
            .version( "5.14.0" )
            .release( "362.8.1.el9" )
            .arch( "src" )
            // Source RPMs have no SOURCERPM tag (they ARE the source)
            .build();

        RpmPackageInfo info = parse( rpm );

        assertEquals( "kernel",         info.name );
        assertEquals( "5.14.0",         info.version );
        assertEquals( "src",            info.arch );
        assertNull( info.sourceRpm );
    }

    // -------------------------------------------------------------------------
    // Noarch package
    // -------------------------------------------------------------------------

    @Test
    void parsesNoarchPackage() throws IOException
    {
        byte[] rpm = RpmTestFixtureBuilder.builder()
            .name( "python3-requests" )
            .version( "2.28.2" )
            .release( "1.el9" )
            .arch( "noarch" )
            .summary( "HTTP library, written in Python, for human beings" )
            .build();

        RpmPackageInfo info = parse( rpm );

        assertEquals( "python3-requests", info.name );
        assertEquals( "noarch",           info.arch );
    }

    // -------------------------------------------------------------------------
    // Dependencies (requires / provides)
    // -------------------------------------------------------------------------

    @Test
    void parsesRequiresAndProvides() throws IOException
    {
        String[] reqNames    = { "libc.so.6", "libssl.so.3", "rpmlib(CompressedFileNames)" };
        String[] reqVersions = { "2.17",       "3.0",         "3.0.4-1" };
        int[]    reqFlags    = { 0x08, 0x0c, 0x08 };  // EQ, GE, EQ

        String[] provNames    = { "libfoo", "libfoo(x86-64)", "libfoo.so.1" };
        String[] provVersions = { "1.2.3",  "1.2.3",          "" };
        int[]    provFlags    = { 0x08, 0x08, 0 };

        byte[] rpm = RpmTestFixtureBuilder.builder()
            .name( "libfoo" ).version( "1.2.3" ).release( "1" ).arch( "x86_64" )
            .requires( reqNames, reqVersions, reqFlags )
            .provides( provNames, provVersions, provFlags )
            .build();

        RpmPackageInfo info = parse( rpm );

        assertEquals( 3, info.requireNames.size() );
        assertEquals( "libc.so.6",    info.requireNames.get( 0 ) );
        assertEquals( "2.17",         info.requireVersions.get( 0 ) );
        assertEquals( 0x08,           (int) info.requireFlags.get( 0 ) );
        assertEquals( "rpmlib(CompressedFileNames)", info.requireNames.get( 2 ) );

        assertEquals( 3, info.provideNames.size() );
        assertEquals( "libfoo",        info.provideNames.get( 0 ) );
        assertEquals( "libfoo.so.1",   info.provideNames.get( 2 ) );
    }

    // -------------------------------------------------------------------------
    // Full file list with directory reconstruction
    // -------------------------------------------------------------------------

    @Test
    void parsesFileListWithDirectories() throws IOException
    {
        // Package installs:
        //   /usr/bin/foo         (dirIdx=0 → "/usr/bin/", basename="foo")
        //   /usr/lib/libfoo.so.1 (dirIdx=1 → "/usr/lib/", basename="libfoo.so.1")
        //   /usr/lib/            (directory entry: dirIdx=1, basename="")
        byte[] rpm = RpmTestFixtureBuilder.builder()
            .name( "foo" ).version( "1.0" ).release( "1" ).arch( "x86_64" )
            .dirNames( "/usr/bin/", "/usr/lib/" )
            .dirIndexes( 0, 1, 1 )
            .files( "foo", "libfoo.so.1", "" )
            .build();

        RpmPackageInfo info = parse( rpm );

        assertEquals( 2, info.dirNames.size() );
        assertEquals( "/usr/bin/", info.dirNames.get( 0 ) );
        assertEquals( "/usr/lib/", info.dirNames.get( 1 ) );

        assertEquals( 3, info.files.size() );
        assertEquals( "foo",         info.files.get( 0 ) );
        assertEquals( "libfoo.so.1", info.files.get( 1 ) );
        assertEquals( "",            info.files.get( 2 ) );  // directory entry

        assertEquals( 3, info.dirIndexes.size() );
        assertEquals( 0, (int) info.dirIndexes.get( 0 ) );
        assertEquals( 1, (int) info.dirIndexes.get( 1 ) );
        assertEquals( 1, (int) info.dirIndexes.get( 2 ) );
    }

    // -------------------------------------------------------------------------
    // Changelog entries
    // -------------------------------------------------------------------------

    @Test
    void parsesChangelogEntries() throws IOException
    {
        long[]   times   = { 1699920000L, 1667040000L };
        String[] authors = { "Alice <alice@example.com>", "Bob <bob@example.com>" };
        String[] texts   = { "- Initial release", "- Upstream version 1.2" };

        byte[] rpm = RpmTestFixtureBuilder.builder()
            .name( "foo" ).version( "1.2" ).release( "1" ).arch( "x86_64" )
            .changelog( times, authors, texts )
            .build();

        RpmPackageInfo info = parse( rpm );

        assertEquals( 2, info.changelogTimes.size() );
        assertEquals( 2, info.changelogNames.size() );
        assertEquals( 2, info.changelogTexts.size() );

        assertEquals( 1699920000L,            info.changelogTimes.get( 0 ) );
        assertEquals( "Alice <alice@example.com>", info.changelogNames.get( 0 ) );
        assertEquals( "- Initial release",    info.changelogTexts.get( 0 ) );

        assertEquals( 1667040000L,            info.changelogTimes.get( 1 ) );
        assertEquals( "Bob <bob@example.com>",  info.changelogNames.get( 1 ) );
        assertEquals( "- Upstream version 1.2", info.changelogTexts.get( 1 ) );
    }

    // -------------------------------------------------------------------------
    // Multiline / special character content
    // -------------------------------------------------------------------------

    @Test
    void parsesMultilineDescription() throws IOException
    {
        String desc = "Line one.\nLine two.\nLine three with <xml> & \"quotes\".";
        byte[] rpm = RpmTestFixtureBuilder.builder()
            .name( "pkg" ).version( "1.0" ).release( "1" ).arch( "x86_64" )
            .description( desc )
            .build();

        RpmPackageInfo info = parse( rpm );
        assertEquals( desc, info.description );
    }

    // -------------------------------------------------------------------------
    // EVR helper
    // -------------------------------------------------------------------------

    @Test
    void evrWithoutEpoch()
    {
        RpmPackageInfo p = new RpmPackageInfo();
        p.version = "1.2.3";
        p.release = "4.el9";
        assertEquals( "1.2.3-4.el9", p.evr() );
    }

    @Test
    void evrWithEpoch()
    {
        RpmPackageInfo p = new RpmPackageInfo();
        p.epoch   = "2";
        p.version = "1.2.3";
        p.release = "4.el9";
        assertEquals( "2:1.2.3-4.el9", p.evr() );
    }

    @Test
    void evrWithEpochZeroOmitsEpoch()
    {
        RpmPackageInfo p = new RpmPackageInfo();
        p.epoch   = "0";
        p.version = "2.0";
        p.release = "1";
        // epoch "0" is conventionally omitted
        assertEquals( "2.0-1", p.evr() );
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static RpmPackageInfo parse( byte[] rpmBytes ) throws IOException
    {
        return RpmHeaderParser.parseHeader( new ByteArrayInputStream( rpmBytes ) );
    }
}
