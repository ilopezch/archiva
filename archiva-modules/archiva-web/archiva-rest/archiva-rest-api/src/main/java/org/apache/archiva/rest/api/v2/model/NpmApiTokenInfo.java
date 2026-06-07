package org.apache.archiva.rest.api.v2.model;
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

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

/**
 * REST DTO describing a previously generated NPM API token, without revealing its value.
 */
@Schema( name = "NpmApiTokenInfo", description = "Metadata about a generated NPM personal access token" )
public class NpmApiTokenInfo implements Serializable
{
    private static final long serialVersionUID = 1L;

    @Schema( description = "Unique identifier of the token (used to revoke it)" )
    private String id;

    @Schema( description = "User-supplied label describing what the token is used for" )
    private String label;

    @Schema( description = "Creation timestamp, milliseconds since epoch" )
    private long createdAt;

    @Schema( description = "Timestamp of the last successful use, milliseconds since epoch, or 0 if never used" )
    private long lastUsedAt;

    public NpmApiTokenInfo()
    {
    }

    public NpmApiTokenInfo( String id, String label, long createdAt, long lastUsedAt )
    {
        this.id = id;
        this.label = label;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
    }

    public String getId() { return id; }
    public void setId( String id ) { this.id = id; }

    public String getLabel() { return label; }
    public void setLabel( String label ) { this.label = label; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt( long createdAt ) { this.createdAt = createdAt; }

    public long getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt( long lastUsedAt ) { this.lastUsedAt = lastUsedAt; }
}
