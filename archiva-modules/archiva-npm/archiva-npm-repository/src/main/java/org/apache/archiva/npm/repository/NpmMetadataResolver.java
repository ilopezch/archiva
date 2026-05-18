package org.apache.archiva.npm.repository;

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

import org.apache.archiva.metadata.model.ArtifactMetadata;
import org.apache.archiva.metadata.model.ProjectVersionMetadata;
import org.apache.archiva.metadata.model.ProjectVersionReference;
import org.apache.archiva.metadata.repository.MetadataResolutionException;
import org.apache.archiva.metadata.repository.MetadataResolver;
import org.apache.archiva.metadata.repository.RepositorySession;
import org.apache.archiva.repository.RepositoryType;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * MetadataResolver for NPM repositories. All data is sourced directly from the
 * MetadataRepository (populated by {@link org.apache.archiva.npm.repository.consumer.NpmMetadataConsumer}).
 */
@Service( "metadataResolver#npm" )
public class NpmMetadataResolver implements MetadataResolver
{
    @Override
    public List<RepositoryType> supportsRepositoryTypes()
    {
        return Collections.singletonList( RepositoryType.NPM );
    }

    @Override
    public ProjectVersionMetadata resolveProjectVersion( RepositorySession session, String repoId, String namespace,
                                                         String projectId, String projectVersion )
        throws MetadataResolutionException
    {
        return session.getRepository().getProjectVersion( session, repoId, namespace, projectId, projectVersion );
    }

    @Override
    public Collection<ProjectVersionReference> resolveProjectReferences( RepositorySession session, String repoId,
                                                                         String namespace, String projectId,
                                                                         String projectVersion )
        throws MetadataResolutionException
    {
        return session.getRepository().getProjectReferences( session, repoId, namespace, projectId, projectVersion );
    }

    @Override
    public Collection<String> resolveRootNamespaces( RepositorySession session, String repoId )
        throws MetadataResolutionException
    {
        return session.getRepository().getRootNamespaces( session, repoId );
    }

    @Override
    public Collection<String> resolveNamespaces( RepositorySession session, String repoId, String namespace )
        throws MetadataResolutionException
    {
        return session.getRepository().getChildNamespaces( session, repoId, namespace );
    }

    @Override
    public Collection<String> resolveProjects( RepositorySession session, String repoId, String namespace )
        throws MetadataResolutionException
    {
        return session.getRepository().getProjects( session, repoId, namespace );
    }

    @Override
    public Collection<String> resolveProjectVersions( RepositorySession session, String repoId, String namespace,
                                                      String projectId )
        throws MetadataResolutionException
    {
        return session.getRepository().getProjectVersions( session, repoId, namespace, projectId );
    }

    @Override
    public Collection<ArtifactMetadata> resolveArtifacts( RepositorySession session, String repoId, String namespace,
                                                          String projectId, String projectVersion )
        throws MetadataResolutionException
    {
        return session.getRepository().getArtifacts( session, repoId, namespace, projectId, projectVersion );
    }
}
