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

import org.apache.archiva.repository.content.ContentAccessException;
import org.apache.archiva.repository.content.ContentItem;
import org.apache.archiva.repository.content.ItemNotFoundException;
import org.apache.archiva.repository.content.ItemSelector;
import org.apache.archiva.repository.content.ManagedRepositoryContentLayout;

import java.util.List;

/**
 * Layout interface for RPM managed repositories. Provides RPM-specific access
 * to packages and repodata files within the repository.
 *
 * <p>RPM storage layout:
 * <ul>
 *   <li>Binary RPM:  {@code RPMS/{arch}/{name}-{version}-{release}.{arch}.rpm}</li>
 *   <li>Source RPM:  {@code SRPMS/{name}-{version}-{release}.src.rpm}</li>
 *   <li>Repodata:    {@code repodata/repomd.xml}</li>
 *   <li>GPG sig:     {@code repodata/repomd.xml.asc}</li>
 *   <li>Public key:  {@code repokey.gpg}</li>
 * </ul>
 *
 * <p>ItemSelector field mapping:
 * <ul>
 *   <li>{@code namespace} → architecture (e.g. {@code x86_64}, {@code noarch}, {@code src})</li>
 *   <li>{@code projectId} → package name</li>
 *   <li>{@code version}   → EVR string: {@code [epoch:]version-release}</li>
 * </ul>
 */
public interface RpmRepositoryContentLayout extends ManagedRepositoryContentLayout
{
    /**
     * Returns the RPM file item for the given selector.
     * The selector must specify {@code projectId}, {@code version}, and {@code namespace} (arch).
     */
    ContentItem getRpmPackage( ItemSelector selector ) throws ContentAccessException, ItemNotFoundException;

    /**
     * Returns all RPM items in the repository across all architectures.
     */
    List<ContentItem> getAllRpms() throws ContentAccessException;

    /**
     * Returns all RPM items for the specified architecture.
     * The selector must specify {@code namespace} (arch).
     */
    List<ContentItem> getRpmsForArch( ItemSelector selector ) throws ContentAccessException;

    /**
     * Returns the repodata directory item.
     */
    ContentItem getRepodataDir() throws ContentAccessException;
}
