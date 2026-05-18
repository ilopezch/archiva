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

import org.apache.archiva.repository.ItemConversionException;
import org.apache.archiva.repository.ManagedRepositoryContent;
import org.apache.archiva.repository.content.ContentItem;
import org.apache.archiva.repository.storage.StorageAsset;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal content item representing a single file or directory in an NPM repository.
 */
public class NpmContentItem implements ContentItem
{
    private final StorageAsset asset;
    private final ManagedRepositoryContent content;
    private final Map<String, String> attributes = new HashMap<>();
    private final Map<Class<?>, ContentItem> characteristics = new HashMap<>();

    public NpmContentItem( String path, StorageAsset asset, ManagedRepositoryContent content )
    {
        this.asset = asset;
        this.content = content;
    }

    @Override
    public StorageAsset getAsset()
    {
        return asset;
    }

    @Override
    public ManagedRepositoryContent getRepository()
    {
        return content;
    }

    @Override
    public boolean exists()
    {
        return asset.exists();
    }

    @Override
    public <T extends ContentItem> T adapt( Class<T> clazz ) throws ItemConversionException
    {
        if ( characteristics.containsKey( clazz ) )
        {
            return clazz.cast( characteristics.get( clazz ) );
        }
        throw new ItemConversionException( "No characteristic " + clazz.getName() + " registered for this item" );
    }

    @Override
    public <T extends ContentItem> boolean hasCharacteristic( Class<T> clazz )
    {
        return characteristics.containsKey( clazz );
    }

    @Override
    public <T extends ContentItem> void setCharacteristic( Class<T> clazz, T item )
    {
        characteristics.put( clazz, item );
    }

    @Override
    public Map<String, String> getAttributes()
    {
        return Collections.unmodifiableMap( attributes );
    }

    @Override
    public String getAttribute( String key )
    {
        return attributes.get( key );
    }
}
