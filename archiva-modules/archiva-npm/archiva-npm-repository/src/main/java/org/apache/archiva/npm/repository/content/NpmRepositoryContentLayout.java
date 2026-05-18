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

import org.apache.archiva.repository.content.ContentAccessException;
import org.apache.archiva.repository.content.ContentItem;
import org.apache.archiva.repository.content.ItemNotFoundException;
import org.apache.archiva.repository.content.ItemSelector;
import org.apache.archiva.repository.content.ManagedRepositoryContentLayout;

import java.util.List;

/**
 * Layout interface for NPM managed repositories. Provides NPM-specific access to
 * packages, tarballs and package metadata files within the repository.
 *
 * <p>NPM storage layout:
 * <ul>
 *   <li>Unscoped package dir: {@code {name}/}</li>
 *   <li>Unscoped tarball:     {@code {name}/-/{name}-{version}.tgz}</li>
 *   <li>Unscoped metadata:    {@code {name}/package.json}</li>
 *   <li>Scoped package dir:   {@code @{scope}/{name}/}</li>
 *   <li>Scoped tarball:       {@code @{scope}/{name}/-/{name}-{version}.tgz}</li>
 *   <li>Scoped metadata:      {@code @{scope}/{name}/package.json}</li>
 * </ul>
 *
 * <p>ItemSelector field mapping:
 * <ul>
 *   <li>{@code namespace} → scope (e.g. {@code @myorg}), empty string for unscoped</li>
 *   <li>{@code projectId} → package name</li>
 *   <li>{@code version}   → semver version string</li>
 * </ul>
 */
public interface NpmRepositoryContentLayout extends ManagedRepositoryContentLayout
{
    /**
     * Returns the package directory item for the given selector.
     * The selector must specify {@code projectId}; {@code namespace} is optional for scoped packages.
     */
    ContentItem getPackage( ItemSelector selector ) throws ContentAccessException, ItemNotFoundException;

    /**
     * Returns the tarball item for the given selector.
     * The selector must specify both {@code projectId} and {@code version}.
     */
    ContentItem getTarball( ItemSelector selector ) throws ContentAccessException, ItemNotFoundException;

    /**
     * Returns the {@code package.json} metadata file item for the given selector.
     * The selector must specify {@code projectId}.
     */
    ContentItem getMetadataFile( ItemSelector selector ) throws ContentAccessException, ItemNotFoundException;

    /**
     * Returns all package directory items in the repository (both scoped and unscoped).
     */
    List<ContentItem> getPackages() throws ContentAccessException;

    /**
     * Returns all tarball items that belong to the package specified by the selector.
     * The selector must specify {@code projectId}.
     */
    List<ContentItem> getTarballs( ItemSelector selector ) throws ContentAccessException;
}
