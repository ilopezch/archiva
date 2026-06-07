package org.apache.archiva.npm.repository.token;

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

/**
 * Persisted record of a server-issued NPM API token. Only the SHA-256 hash of the token
 * value is stored — the plaintext token is shown to the user once, at creation time.
 */
public class NpmApiToken
{
    private String id;

    private String tokenHash;

    private String username;

    private String label;

    private long createdAt;

    private long lastUsedAt;

    public NpmApiToken()
    {
        // for Jackson
    }

    public NpmApiToken( String id, String tokenHash, String username, String label, long createdAt, long lastUsedAt )
    {
        this.id = id;
        this.tokenHash = tokenHash;
        this.username = username;
        this.label = label;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
    }

    public String getId()
    {
        return id;
    }

    public void setId( String id )
    {
        this.id = id;
    }

    public String getTokenHash()
    {
        return tokenHash;
    }

    public void setTokenHash( String tokenHash )
    {
        this.tokenHash = tokenHash;
    }

    public String getUsername()
    {
        return username;
    }

    public void setUsername( String username )
    {
        this.username = username;
    }

    public String getLabel()
    {
        return label;
    }

    public void setLabel( String label )
    {
        this.label = label;
    }

    public long getCreatedAt()
    {
        return createdAt;
    }

    public void setCreatedAt( long createdAt )
    {
        this.createdAt = createdAt;
    }

    public long getLastUsedAt()
    {
        return lastUsedAt;
    }

    public void setLastUsedAt( long lastUsedAt )
    {
        this.lastUsedAt = lastUsedAt;
    }
}
