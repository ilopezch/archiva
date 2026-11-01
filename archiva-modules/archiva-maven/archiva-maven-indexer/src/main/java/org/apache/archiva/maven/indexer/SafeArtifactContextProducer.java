/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.archiva.maven.indexer;

import org.apache.maven.index.ArtifactContext;
import org.apache.maven.index.ArtifactContextProducer;
import org.apache.maven.index.context.IndexingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Wraps an {@link ArtifactContextProducer} so that files the Maven indexer cannot parse
 * (e.g. stray files in a SNAPSHOT directory that make M2GavCalculator throw
 * IndexOutOfBoundsException) are skipped instead of aborting the whole repository scan.
 * Intentionally not a Spring bean, to avoid ambiguity with the default producer.
 */
public class SafeArtifactContextProducer implements ArtifactContextProducer
{
    private static final Logger log = LoggerFactory.getLogger( SafeArtifactContextProducer.class );

    private final ArtifactContextProducer delegate;

    public SafeArtifactContextProducer( ArtifactContextProducer delegate )
    {
        this.delegate = delegate;
    }

    @Override
    public ArtifactContext getArtifactContext( IndexingContext context, File file )
    {
        try
        {
            return delegate.getArtifactContext( context, file );
        }
        catch ( RuntimeException e )
        {
            log.warn( "Skipping file that cannot be indexed: {} ({}: {})", file, e.getClass().getSimpleName(),
                e.getMessage() );
            return null;
        }
    }
}
