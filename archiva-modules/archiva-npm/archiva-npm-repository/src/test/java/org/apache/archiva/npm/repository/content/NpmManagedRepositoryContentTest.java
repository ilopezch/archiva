package org.apache.archiva.npm.repository.content;

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
 * Unit tests for {@link NpmManagedRepositoryContent#toPath(ItemSelector)}
 * and {@link NpmManagedRepositoryContent#toItemSelector(String)}.
 *
 * Both methods are pure string transformations; the repository reference is left null.
 */
class NpmManagedRepositoryContentTest
{
    private NpmManagedRepositoryContent content;

    @BeforeEach
    void setUp()
    {
        content = new NpmManagedRepositoryContent( null );
    }

    // -------------------------------------------------------------------------
    // toPath — unscoped packages
    // -------------------------------------------------------------------------

    @Test
    void toPath_unscopedWithVersion()
    {
        ItemSelector sel = ArchivaItemSelector.builder()
            .withProjectId( "react" )
            .withVersion( "18.2.0" )
            .build();
        assertEquals( "react/-/react-18.2.0.tgz", content.toPath( sel ) );
    }

    @Test
    void toPath_unscopedWithoutVersion()
    {
        ItemSelector sel = ArchivaItemSelector.builder()
            .withProjectId( "react" )
            .build();
        assertEquals( "react/package.json", content.toPath( sel ) );
    }

    @Test
    void toPath_unscopedEmptyNamespaceWithVersion()
    {
        ItemSelector sel = ArchivaItemSelector.builder()
            .withNamespace( "" )
            .withProjectId( "express" )
            .withVersion( "4.18.2" )
            .build();
        assertEquals( "express/-/express-4.18.2.tgz", content.toPath( sel ) );
    }

    // -------------------------------------------------------------------------
    // toPath — scoped packages
    // -------------------------------------------------------------------------

    @Test
    void toPath_scopedWithVersion()
    {
        ItemSelector sel = ArchivaItemSelector.builder()
            .withNamespace( "@types" )
            .withProjectId( "node" )
            .withVersion( "18.0.0" )
            .build();
        assertEquals( "@types/node/-/node-18.0.0.tgz", content.toPath( sel ) );
    }

    @Test
    void toPath_scopedWithoutVersion()
    {
        ItemSelector sel = ArchivaItemSelector.builder()
            .withNamespace( "@types" )
            .withProjectId( "node" )
            .build();
        assertEquals( "@types/node/package.json", content.toPath( sel ) );
    }

    @Test
    void toPath_scopedOrgScope()
    {
        ItemSelector sel = ArchivaItemSelector.builder()
            .withNamespace( "@myorg" )
            .withProjectId( "my-lib" )
            .withVersion( "1.2.3" )
            .build();
        assertEquals( "@myorg/my-lib/-/my-lib-1.2.3.tgz", content.toPath( sel ) );
    }

    // -------------------------------------------------------------------------
    // toItemSelector — unscoped packages
    // -------------------------------------------------------------------------

    @Test
    void toItemSelector_unscopedTarball() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector( "react/-/react-18.2.0.tgz" );
        assertTrue( sel.getNamespace().isEmpty() );
        assertEquals( "react", sel.getProjectId() );
        assertEquals( "18.2.0", sel.getVersion() );
    }

    @Test
    void toItemSelector_unscopedMetadata() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector( "express/package.json" );
        assertTrue( sel.getNamespace().isEmpty() );
        assertEquals( "express", sel.getProjectId() );
        assertTrue( sel.getVersion().isEmpty() );
    }

    @Test
    void toItemSelector_unscopedPackageDir() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector( "lodash" );
        assertTrue( sel.getNamespace().isEmpty() );
        assertEquals( "lodash", sel.getProjectId() );
        assertTrue( sel.getVersion().isEmpty() );
    }

    @Test
    void toItemSelector_leadingSlashStripped() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector( "/react/-/react-18.2.0.tgz" );
        assertEquals( "react", sel.getProjectId() );
        assertEquals( "18.2.0", sel.getVersion() );
    }

    // -------------------------------------------------------------------------
    // toItemSelector — scoped packages
    // -------------------------------------------------------------------------

    @Test
    void toItemSelector_scopedTarball() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector( "@types/node/-/node-18.0.0.tgz" );
        assertEquals( "@types", sel.getNamespace() );
        assertEquals( "node", sel.getProjectId() );
        assertEquals( "18.0.0", sel.getVersion() );
    }

    @Test
    void toItemSelector_scopedMetadata() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector( "@myorg/my-lib/package.json" );
        assertEquals( "@myorg", sel.getNamespace() );
        assertEquals( "my-lib", sel.getProjectId() );
        assertTrue( sel.getVersion().isEmpty() );
    }

    @Test
    void toItemSelector_scopedPackageDir() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector( "@types/node" );
        assertEquals( "@types", sel.getNamespace() );
        assertEquals( "node", sel.getProjectId() );
        assertTrue( sel.getVersion().isEmpty() );
    }

    @Test
    void toItemSelector_scopeOnly() throws LayoutException
    {
        ItemSelector sel = content.toItemSelector( "@scope" );
        assertEquals( "@scope", sel.getNamespace() );
        assertTrue( sel.getProjectId().isEmpty() );
        assertTrue( sel.getVersion().isEmpty() );
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

    // -------------------------------------------------------------------------
    // Round-trip: selector → path → selector
    // -------------------------------------------------------------------------

    @Test
    void roundTrip_unscopedPackage() throws LayoutException
    {
        ItemSelector original = ArchivaItemSelector.builder()
            .withProjectId( "react" )
            .withVersion( "18.2.0" )
            .build();
        String path = content.toPath( original );
        ItemSelector recovered = content.toItemSelector( path );
        assertEquals( original.getProjectId(), recovered.getProjectId() );
        assertEquals( original.getVersion(), recovered.getVersion() );
    }

    @Test
    void roundTrip_scopedPackage() throws LayoutException
    {
        ItemSelector original = ArchivaItemSelector.builder()
            .withNamespace( "@types" )
            .withProjectId( "node" )
            .withVersion( "18.0.0" )
            .build();
        String path = content.toPath( original );
        ItemSelector recovered = content.toItemSelector( path );
        assertEquals( original.getNamespace(), recovered.getNamespace() );
        assertEquals( original.getProjectId(), recovered.getProjectId() );
        assertEquals( original.getVersion(), recovered.getVersion() );
    }

    @Test
    void roundTrip_packageWithHyphensInName() throws LayoutException
    {
        ItemSelector original = ArchivaItemSelector.builder()
            .withNamespace( "@myorg" )
            .withProjectId( "my-lib" )
            .withVersion( "1.2.3" )
            .build();
        String path = content.toPath( original );
        ItemSelector recovered = content.toItemSelector( path );
        assertEquals( original.getNamespace(), recovered.getNamespace() );
        assertEquals( original.getProjectId(), recovered.getProjectId() );
        assertEquals( original.getVersion(), recovered.getVersion() );
    }
}
