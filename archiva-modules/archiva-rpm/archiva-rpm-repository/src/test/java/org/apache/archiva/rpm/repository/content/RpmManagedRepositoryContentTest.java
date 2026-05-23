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

import org.apache.archiva.repository.content.ItemSelector;
import org.apache.archiva.repository.content.LayoutException;
import org.apache.archiva.repository.content.base.ArchivaItemSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RpmManagedRepositoryContent#toPath(ItemSelector)}
 * and {@link RpmManagedRepositoryContent#toItemSelector(String)}.
 *
 * Both methods are pure string transformations and require no repository
 * I/O, so the repository reference is left null.
 */
class RpmManagedRepositoryContentTest
{
    private RpmManagedRepositoryContent content;

    @BeforeEach
    void setUp()
    {
        // toPath / toItemSelector perform no repository I/O
        content = new RpmManagedRepositoryContent( null );
    }

    // -------------------------------------------------------------------------
    // toPath — binary RPMs
    // -------------------------------------------------------------------------

    @Test
    void toPath_binaryRpmWithAllCoordinates()
    {
        ItemSelector sel = ArchivaItemSelector.builder()
            .withNamespace( "x86_64" )
            .withProjectId( "bash" )
            .withVersion( "5.1.8-6.el9" )
            .build();
        assertEquals( "RPMS/x86_64/bash-5.1.8-6.el9.x86_64.rpm", content.toPath( sel ) );
    }

    @Test
    void toPath_noarchPackage()
    {
        ItemSelector sel = ArchivaItemSelector.builder()
            .withNamespace( "noarch" )
            .withProjectId( "python3-requests" )
            .withVersion( "2.28.0-1.el9" )
            .build();
        assertEquals( "RPMS/noarch/python3-requests-2.28.0-1.el9.noarch.rpm", content.toPath( sel ) );
    }

    @Test
    void toPath_sourceRpm()
    {
        ItemSelector sel = ArchivaItemSelector.builder()
            .withNamespace( "src" )
            .withProjectId( "bash" )
            .withVersion( "5.1.8-6.el9" )
            .build();
        assertEquals( "SRPMS/bash-5.1.8-6.el9.src.rpm", content.toPath( sel ) );
    }

    @Test
    void toPath_sourceRpmDirectoryOnly()
    {
        ItemSelector sel = ArchivaItemSelector.builder()
            .withNamespace( "src" )
            .build();
        assertEquals( "SRPMS", content.toPath( sel ) );
    }

    @Test
    void toPath_archDirectoryOnly()
    {
        ItemSelector sel = ArchivaItemSelector.builder()
            .withNamespace( "x86_64" )
            .build();
        assertEquals( "RPMS/x86_64", content.toPath( sel ) );
    }

    @Test
    void toPath_noCoordinatesReturnRpmsRoot()
    {
        ItemSelector sel = ArchivaItemSelector.builder().build();
        assertEquals( "RPMS", content.toPath( sel ) );
    }

    @Test
    void toPath_nameWithoutVersionReturnsArchDir()
    {
        ItemSelector sel = ArchivaItemSelector.builder()
            .withNamespace( "x86_64" )
            .withProjectId( "curl" )
            .build();
        // version is absent — falls through to arch-only path
        assertEquals( "RPMS/x86_64", content.toPath( sel ) );
    }

    // -------------------------------------------------------------------------
    // toItemSelector — binary RPMs
    // -------------------------------------------------------------------------

    @Test
    void toItemSelector_binaryRpm() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector( "RPMS/x86_64/bash-5.1.8-6.el9.x86_64.rpm" );
        assertEquals( "x86_64", sel.getNamespace() );
        assertEquals( "bash", sel.getProjectId() );
        assertEquals( "5.1.8-6.el9", sel.getVersion() );
    }

    @Test
    void toItemSelector_noarchRpm() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector( "RPMS/noarch/python3-requests-2.28.0-1.el9.noarch.rpm" );
        assertEquals( "noarch", sel.getNamespace() );
        assertEquals( "python3-requests", sel.getProjectId() );
        assertEquals( "2.28.0-1.el9", sel.getVersion() );
    }

    @Test
    void toItemSelector_sourceRpm() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector( "SRPMS/bash-5.1.8-6.el9.src.rpm" );
        assertEquals( "src", sel.getNamespace() );
        assertEquals( "bash", sel.getProjectId() );
        assertEquals( "5.1.8-6.el9", sel.getVersion() );
    }

    @Test
    void toItemSelector_leadingSlashIsStripped() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector( "/RPMS/x86_64/curl-7.76.1-26.el9.x86_64.rpm" );
        assertEquals( "x86_64", sel.getNamespace() );
        assertEquals( "curl", sel.getProjectId() );
        assertEquals( "7.76.1-26.el9", sel.getVersion() );
    }

    @Test
    void toItemSelector_archDirectoryOnly() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector( "RPMS/x86_64" );
        assertEquals( "x86_64", sel.getNamespace() );
    }

    @Test
    void toItemSelector_packageNameContainingHyphens() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector(
            "RPMS/x86_64/java-17-openjdk-headless-17.0.9-3.el9.x86_64.rpm" );
        assertEquals( "x86_64", sel.getNamespace() );
        assertEquals( "java-17-openjdk-headless", sel.getProjectId() );
        assertEquals( "17.0.9-3.el9", sel.getVersion() );
    }

    @Test
    void toItemSelector_ppc64leArch() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector(
            "RPMS/ppc64le/glibc-2.34-60.el9.ppc64le.rpm" );
        assertEquals( "ppc64le", sel.getNamespace() );
        assertEquals( "glibc", sel.getProjectId() );
        assertEquals( "2.34-60.el9", sel.getVersion() );
    }

    // -------------------------------------------------------------------------
    // toItemSelector — error cases
    // -------------------------------------------------------------------------

    @Test
    void toItemSelector_emptyPathThrows()
    {
        assertThrows( LayoutException.class, () -> content.toItemSelector( "" ) );
    }

    @Test
    void toItemSelector_nullPathThrows()
    {
        assertThrows( LayoutException.class, () -> content.toItemSelector( null ) );
    }

    @Test
    void toItemSelector_unrecognisedPathThrows()
    {
        assertThrows( LayoutException.class, () -> content.toItemSelector( "repodata/repomd.xml" ) );
    }

    @Test
    void toItemSelector_bareFilenameThrows()
    {
        assertThrows( LayoutException.class, () -> content.toItemSelector( "bash-5.1.8.rpm" ) );
    }

    // -------------------------------------------------------------------------
    // Round-trip: selector → path → selector
    // -------------------------------------------------------------------------

    @Test
    void roundTrip_binaryRpm() throws LayoutException
    {
        ItemSelector original = ArchivaItemSelector.builder()
            .withNamespace( "x86_64" )
            .withProjectId( "curl" )
            .withVersion( "7.76.1-26.el9" )
            .build();
        String path = content.toPath( original );
        ItemSelector recovered = content.toItemSelector( path );
        assertEquals( original.getNamespace(), recovered.getNamespace() );
        assertEquals( original.getProjectId(), recovered.getProjectId() );
        assertEquals( original.getVersion(), recovered.getVersion() );
    }

    @Test
    void roundTrip_sourceRpm() throws LayoutException
    {
        ItemSelector original = ArchivaItemSelector.builder()
            .withNamespace( "src" )
            .withProjectId( "openssl" )
            .withVersion( "3.0.7-25.el9" )
            .build();
        String path = content.toPath( original );
        ItemSelector recovered = content.toItemSelector( path );
        assertEquals( "src", recovered.getNamespace() );
        assertEquals( "openssl", recovered.getProjectId() );
        assertEquals( "3.0.7-25.el9", recovered.getVersion() );
    }

    @Test
    void roundTrip_packageWithHyphensInName() throws LayoutException
    {
        ItemSelector original = ArchivaItemSelector.builder()
            .withNamespace( "x86_64" )
            .withProjectId( "java-17-openjdk-headless" )
            .withVersion( "17.0.9-3.el9" )
            .build();
        String path = content.toPath( original );
        ItemSelector recovered = content.toItemSelector( path );
        assertEquals( original.getProjectId(), recovered.getProjectId() );
        assertEquals( original.getVersion(), recovered.getVersion() );
    }

    @Test
    void roundTrip_noarch() throws LayoutException
    {
        ItemSelector original = ArchivaItemSelector.builder()
            .withNamespace( "noarch" )
            .withProjectId( "bash-completion" )
            .withVersion( "2.11-4.el9" )
            .build();
        String path = content.toPath( original );
        ItemSelector recovered = content.toItemSelector( path );
        assertEquals( "noarch", recovered.getNamespace() );
        assertEquals( "bash-completion", recovered.getProjectId() );
        assertEquals( "2.11-4.el9", recovered.getVersion() );
    }
}
