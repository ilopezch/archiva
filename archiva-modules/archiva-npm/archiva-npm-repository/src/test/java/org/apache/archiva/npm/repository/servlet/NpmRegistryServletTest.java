package org.apache.archiva.npm.repository.servlet;

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

import org.apache.archiva.npm.repository.servlet.NpmRegistryServlet.NpmRequest;
import org.apache.archiva.repository.ManagedRepository;
import org.apache.archiva.repository.RepositoryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NpmRegistryServlet}.
 *
 * <p>The servlet's business-logic handler methods are private; we reach the servlet
 * via {@code doGet}/{@code doPut}/{@code doDelete} with Spring's
 * {@link MockHttpServletRequest}/{@link MockHttpServletResponse}.  The
 * {@code repositoryRegistry} field is injected via reflection so that no
 * servlet container or Spring context is required.
 */
class NpmRegistryServletTest
{
    private NpmRegistryServlet servlet;
    private RepositoryRegistry mockRegistry;

    @BeforeEach
    void setUp() throws Exception
    {
        servlet = new NpmRegistryServlet();
        mockRegistry = Mockito.mock( RepositoryRegistry.class );
        injectField( "repositoryRegistry", mockRegistry );
    }

    private void injectField( String fieldName, Object value ) throws Exception
    {
        Field f = NpmRegistryServlet.class.getDeclaredField( fieldName );
        f.setAccessible( true );
        f.set( servlet, value );
    }

    // =========================================================================
    // NpmRequest.parse() — static parsing logic
    // =========================================================================

    @Test
    void parse_unscopedPackage()
    {
        NpmRequest req = NpmRequest.parse( new String[]{ "my-repo", "react" } );
        assertNotNull( req );
        assertEquals( "my-repo", req.repoId );
        assertNull( req.scope );
        assertEquals( "react", req.name );
        assertNull( req.version );
        assertNull( req.tarball );
    }

    @Test
    void parse_unscopedWithVersion()
    {
        NpmRequest req = NpmRequest.parse( new String[]{ "my-repo", "react", "18.2.0" } );
        assertNotNull( req );
        assertEquals( "react", req.name );
        assertEquals( "18.2.0", req.version );
        assertNull( req.tarball );
    }

    @Test
    void parse_unscopedWithTarball()
    {
        NpmRequest req = NpmRequest.parse( new String[]{ "my-repo", "react", "-", "react-18.2.0.tgz" } );
        assertNotNull( req );
        assertEquals( "react", req.name );
        assertNull( req.version );
        assertEquals( "react-18.2.0.tgz", req.tarball );
    }

    @Test
    void parse_scopedPackage()
    {
        NpmRequest req = NpmRequest.parse( new String[]{ "my-repo", "@types", "node" } );
        assertNotNull( req );
        assertEquals( "@types", req.scope );
        assertEquals( "node", req.name );
        assertNull( req.version );
        assertNull( req.tarball );
    }

    @Test
    void parse_scopedWithVersion()
    {
        NpmRequest req = NpmRequest.parse( new String[]{ "my-repo", "@types", "node", "18.0.0" } );
        assertNotNull( req );
        assertEquals( "@types", req.scope );
        assertEquals( "node", req.name );
        assertEquals( "18.0.0", req.version );
        assertNull( req.tarball );
    }

    @Test
    void parse_scopedWithTarball()
    {
        NpmRequest req = NpmRequest.parse( new String[]{ "my-repo", "@types", "node", "-", "node-18.0.0.tgz" } );
        assertNotNull( req );
        assertEquals( "@types", req.scope );
        assertEquals( "node", req.name );
        assertNull( req.version );
        assertEquals( "node-18.0.0.tgz", req.tarball );
    }

    @Test
    void parse_deleteWithRevSuffix()
    {
        NpmRequest req = NpmRequest.parse( new String[]{ "my-repo", "react", "-rev", "1-abc123" } );
        assertNotNull( req );
        assertEquals( "react", req.name );
        assertNull( req.version );
        assertNull( req.tarball );
    }

    @Test
    void parse_scopedDeleteWithRevSuffix()
    {
        NpmRequest req = NpmRequest.parse( new String[]{ "my-repo", "@myorg", "pkg", "-rev", "1-abc" } );
        assertNotNull( req );
        assertEquals( "@myorg", req.scope );
        assertEquals( "pkg", req.name );
        assertNull( req.version );
        assertNull( req.tarball );
    }

    @Test
    void parse_nullReturnsNull()
    {
        assertNull( NpmRequest.parse( null ) );
    }

    @Test
    void parse_tooShortReturnsNull()
    {
        assertNull( NpmRequest.parse( new String[]{ "only-repo-id" } ) );
    }

    @Test
    void parse_emptyScopeNameReturnsNull()
    {
        // scope present but no name following
        assertNull( NpmRequest.parse( new String[]{ "my-repo", "@types" } ) );
    }

    @Test
    void packagePath_unscoped()
    {
        NpmRequest req = new NpmRequest( "r", null, "react", null, null );
        assertEquals( "react", req.packagePath() );
    }

    @Test
    void packagePath_scoped()
    {
        NpmRequest req = new NpmRequest( "r", "@types", "node", null, null );
        assertEquals( "@types/node", req.packagePath() );
    }

    // =========================================================================
    // doGet — routing
    // =========================================================================

    @Test
    void doGet_nullPathInfoReturnsBadRequest() throws Exception
    {
        MockHttpServletRequest req = new MockHttpServletRequest( "GET", "/npm" );
        // pathInfo is null by default
        MockHttpServletResponse resp = new MockHttpServletResponse();

        servlet.doGet( req, resp );

        assertEquals( HttpServletResponse.SC_BAD_REQUEST, resp.getStatus() );
    }

    @Test
    void doGet_onlyRepoIdReturnsBadRequest() throws Exception
    {
        MockHttpServletRequest req = new MockHttpServletRequest( "GET", "/npm/my-repo" );
        req.setPathInfo( "/my-repo" );
        MockHttpServletResponse resp = new MockHttpServletResponse();

        servlet.doGet( req, resp );

        assertEquals( HttpServletResponse.SC_BAD_REQUEST, resp.getStatus() );
    }

    @Test
    void doGet_unknownRepositoryReturnsNotFound() throws Exception
    {
        when( mockRegistry.getManagedRepository( "unknown-repo" ) ).thenReturn( null );

        MockHttpServletRequest req = new MockHttpServletRequest( "GET", "/npm/unknown-repo/react" );
        req.setPathInfo( "/unknown-repo/react" );
        MockHttpServletResponse resp = new MockHttpServletResponse();

        servlet.doGet( req, resp );

        assertEquals( HttpServletResponse.SC_NOT_FOUND, resp.getStatus() );
    }

    @Test
    void doGet_knownRepositoryButMissingPackageReturnsNotFound() throws Exception
    {
        ManagedRepository mockRepo = Mockito.mock( ManagedRepository.class );
        when( mockRegistry.getManagedRepository( "my-repo" ) ).thenReturn( mockRepo );

        org.apache.archiva.repository.storage.StorageAsset mockAsset =
            Mockito.mock( org.apache.archiva.repository.storage.StorageAsset.class );
        when( mockRepo.getAsset( anyString() ) ).thenReturn( mockAsset );
        when( mockAsset.exists() ).thenReturn( false );

        MockHttpServletRequest req = new MockHttpServletRequest( "GET", "/npm/my-repo/no-such-pkg" );
        req.setPathInfo( "/my-repo/no-such-pkg" );
        MockHttpServletResponse resp = new MockHttpServletResponse();

        servlet.doGet( req, resp );

        assertEquals( HttpServletResponse.SC_NOT_FOUND, resp.getStatus() );
    }

    // =========================================================================
    // doPut — routing
    // =========================================================================

    @Test
    void doPut_unknownRepositoryReturnsNotFound() throws Exception
    {
        when( mockRegistry.getManagedRepository( "unknown-repo" ) ).thenReturn( null );

        MockHttpServletRequest req = new MockHttpServletRequest( "PUT", "/npm/unknown-repo/my-pkg" );
        req.setPathInfo( "/unknown-repo/my-pkg" );
        req.setContent( "{}".getBytes() );
        MockHttpServletResponse resp = new MockHttpServletResponse();

        servlet.doPut( req, resp );

        assertEquals( HttpServletResponse.SC_NOT_FOUND, resp.getStatus() );
    }

    @Test
    void doPut_nullPathInfoReturnsBadRequest() throws Exception
    {
        MockHttpServletRequest req = new MockHttpServletRequest( "PUT", "/npm" );
        MockHttpServletResponse resp = new MockHttpServletResponse();

        servlet.doPut( req, resp );

        assertEquals( HttpServletResponse.SC_BAD_REQUEST, resp.getStatus() );
    }

    // =========================================================================
    // doDelete — routing
    // =========================================================================

    @Test
    void doDelete_unknownRepositoryReturnsNotFound() throws Exception
    {
        when( mockRegistry.getManagedRepository( "unknown-repo" ) ).thenReturn( null );

        MockHttpServletRequest req = new MockHttpServletRequest( "DELETE", "/npm/unknown-repo/my-pkg" );
        req.setPathInfo( "/unknown-repo/my-pkg" );
        MockHttpServletResponse resp = new MockHttpServletResponse();

        servlet.doDelete( req, resp );

        assertEquals( HttpServletResponse.SC_NOT_FOUND, resp.getStatus() );
    }

    @Test
    void doDelete_nullPathInfoReturnsBadRequest() throws Exception
    {
        MockHttpServletRequest req = new MockHttpServletRequest( "DELETE", "/npm" );
        MockHttpServletResponse resp = new MockHttpServletResponse();

        servlet.doDelete( req, resp );

        assertEquals( HttpServletResponse.SC_BAD_REQUEST, resp.getStatus() );
    }
}
