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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PrimaryXmlBuilder}.  Verifies that the generated
 * {@code primary.xml} contains the correct elements, attributes, and values
 * for a given set of {@link RpmPackageInfo} objects.
 */
class PrimaryXmlBuilderTest
{
    // -------------------------------------------------------------------------
    // Well-formed XML
    // -------------------------------------------------------------------------

    @Test
    void emptyPackageListProducesValidXml() throws Exception
    {
        byte[] xml = PrimaryXmlBuilder.build( List.of() );
        Document doc = parse( xml );
        Element root = doc.getDocumentElement();
        assertEquals( "metadata", root.getLocalName() );
        assertEquals( "0", root.getAttribute( "packages" ) );
        assertEquals( 0, root.getElementsByTagName( "package" ).getLength() );
    }

    @Test
    void singlePackageAttributesArePresentAndCorrect() throws Exception
    {
        RpmPackageInfo p = makePackage();

        byte[] xml = PrimaryXmlBuilder.build( List.of( p ) );
        Document doc = parse( xml );

        NodeList pkgs = doc.getElementsByTagNameNS( "*", "package" );
        assertEquals( 1, pkgs.getLength() );

        Element pkg = (Element) pkgs.item( 0 );
        assertEquals( "rpm", pkg.getAttribute( "type" ) );

        assertText( doc, "name",    "libfoo" );
        assertText( doc, "arch",    "x86_64" );
        assertText( doc, "summary", "Foo library" );
    }

    @Test
    void versionElementHasEpochVerRel() throws Exception
    {
        RpmPackageInfo p = makePackage();
        p.epoch   = "2";
        p.version = "1.2.3";
        p.release = "4.el9";

        byte[] xml = PrimaryXmlBuilder.build( List.of( p ) );
        Document doc = parse( xml );

        Element version = (Element) doc.getElementsByTagName( "version" ).item( 0 );
        assertNotNull( version );
        assertEquals( "2",     version.getAttribute( "epoch" ) );
        assertEquals( "1.2.3", version.getAttribute( "ver" ) );
        assertEquals( "4.el9", version.getAttribute( "rel" ) );
    }

    @Test
    void missingEpochDefaultsToZero() throws Exception
    {
        RpmPackageInfo p = makePackage();
        p.epoch = null;

        byte[] xml = PrimaryXmlBuilder.build( List.of( p ) );
        Document doc = parse( xml );

        Element version = (Element) doc.getElementsByTagName( "version" ).item( 0 );
        assertEquals( "0", version.getAttribute( "epoch" ) );
    }

    @Test
    void checksumUsesPackageSha256() throws Exception
    {
        RpmPackageInfo p = makePackage();
        p.sha256 = "deadbeef1234";

        byte[] xml = PrimaryXmlBuilder.build( List.of( p ) );
        Document doc = parse( xml );

        Element checksum = (Element) doc.getElementsByTagName( "checksum" ).item( 0 );
        assertNotNull( checksum );
        assertEquals( "sha256", checksum.getAttribute( "type" ) );
        assertEquals( "YES",    checksum.getAttribute( "pkgid" ) );
        assertEquals( "deadbeef1234", checksum.getTextContent() );
    }

    @Test
    void locationHrefMatchesPackageLocation() throws Exception
    {
        RpmPackageInfo p = makePackage();
        p.location = "RPMS/x86_64/libfoo-1.0-1.x86_64.rpm";

        byte[] xml = PrimaryXmlBuilder.build( List.of( p ) );
        Document doc = parse( xml );

        Element location = (Element) doc.getElementsByTagName( "location" ).item( 0 );
        assertNotNull( location );
        assertEquals( "RPMS/x86_64/libfoo-1.0-1.x86_64.rpm", location.getAttribute( "href" ) );
    }

    @Test
    void sizesAreEmittedCorrectly() throws Exception
    {
        RpmPackageInfo p = makePackage();
        p.fileSize      = 123456L;
        p.installedSize = 456789L;
        p.archiveSize   = 234567L;

        byte[] xml = PrimaryXmlBuilder.build( List.of( p ) );
        Document doc = parse( xml );

        Element size = (Element) doc.getElementsByTagName( "size" ).item( 0 );
        assertNotNull( size );
        assertEquals( "123456", size.getAttribute( "package" ) );
        assertEquals( "456789", size.getAttribute( "installed" ) );
        assertEquals( "234567", size.getAttribute( "archive" ) );
    }

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    @Test
    void requiresAreEmittedUnderFormat() throws Exception
    {
        RpmPackageInfo p = makePackage();
        p.requireNames.add( "libc.so.6" );
        p.requireVersions.add( "2.17" );
        p.requireFlags.add( 8 );  // EQ

        byte[] xml = PrimaryXmlBuilder.build( List.of( p ) );
        Document doc = parse( xml );

        NodeList requires = doc.getElementsByTagNameNS( "*", "requires" );
        assertEquals( 1, requires.getLength() );

        NodeList entries = ( (Element) requires.item( 0 ) ).getElementsByTagNameNS( "*", "entry" );
        assertEquals( 1, entries.getLength() );

        Element entry = (Element) entries.item( 0 );
        assertEquals( "libc.so.6", entry.getAttribute( "name" ) );
        assertEquals( "EQ",        entry.getAttribute( "flags" ) );
        assertEquals( "2.17",      entry.getAttribute( "ver" ) );
    }

    @Test
    void providesAreEmittedUnderFormat() throws Exception
    {
        RpmPackageInfo p = makePackage();
        p.provideNames.add( "libfoo.so.1" );
        p.provideVersions.add( "" );
        p.provideFlags.add( 0 );

        byte[] xml = PrimaryXmlBuilder.build( List.of( p ) );
        Document doc = parse( xml );

        NodeList provides = doc.getElementsByTagNameNS( "*", "provides" );
        assertEquals( 1, provides.getLength() );

        NodeList entries = ( (Element) provides.item( 0 ) ).getElementsByTagNameNS( "*", "entry" );
        assertEquals( 1, entries.getLength() );
        assertEquals( "libfoo.so.1", ( (Element) entries.item( 0 ) ).getAttribute( "name" ) );
    }

    // -------------------------------------------------------------------------
    // XML character escaping
    // -------------------------------------------------------------------------

    @Test
    void specialCharactersAreEscapedInXml() throws Exception
    {
        RpmPackageInfo p = makePackage();
        p.summary = "A <library> with \"quotes\" & more";

        byte[] xml = PrimaryXmlBuilder.build( List.of( p ) );
        // If XML is well-formed the parser won't throw; the text content round-trips cleanly
        Document doc = parse( xml );

        Element summary = (Element) doc.getElementsByTagName( "summary" ).item( 0 );
        assertEquals( "A <library> with \"quotes\" & more", summary.getTextContent() );
    }

    @Test
    void multiplePackagesAreAllPresent() throws Exception
    {
        RpmPackageInfo p1 = makePackage();
        p1.name = "alpha";

        RpmPackageInfo p2 = makePackage();
        p2.name = "beta";

        byte[] xml = PrimaryXmlBuilder.build( List.of( p1, p2 ) );
        Document doc = parse( xml );

        assertEquals( "2", doc.getDocumentElement().getAttribute( "packages" ) );
        NodeList pkgs = doc.getElementsByTagNameNS( "*", "package" );
        assertEquals( 2, pkgs.getLength() );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static RpmPackageInfo makePackage()
    {
        RpmPackageInfo p = new RpmPackageInfo();
        p.name          = "libfoo";
        p.version       = "1.0";
        p.release       = "1";
        p.arch          = "x86_64";
        p.summary       = "Foo library";
        p.description   = "Foo description";
        p.license       = "MIT";
        p.group         = "System/Libraries";
        p.url           = "https://example.com";
        p.sourceRpm     = "libfoo-1.0-1.src.rpm";
        p.sha256        = "aabbccdd";
        p.location      = "RPMS/x86_64/libfoo-1.0-1.x86_64.rpm";
        p.fileSize      = 10000;
        p.installedSize = 30000;
        p.archiveSize   = 15000;
        p.buildTime     = 1700000000;
        return p;
    }

    private static Document parse( byte[] xml ) throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware( true );
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse( new ByteArrayInputStream( xml ) );
    }

    private static void assertText( Document doc, String tagName, String expected )
    {
        NodeList nodes = doc.getElementsByTagName( tagName );
        if ( nodes.getLength() == 0 )
        {
            // Also try namespace-qualified lookup
            nodes = doc.getElementsByTagNameNS( "*", tagName );
        }
        assertTrue( nodes.getLength() > 0, "No element found: " + tagName );
        assertEquals( expected, nodes.item( 0 ).getTextContent() );
    }
}
