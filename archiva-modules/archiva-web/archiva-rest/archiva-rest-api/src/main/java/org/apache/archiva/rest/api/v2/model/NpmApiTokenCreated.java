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
 * REST DTO returned right after generating a new NPM API token.
 * The plaintext {@code token} value is shown here exactly once — it is not recoverable
 * afterwards, only its metadata can be retrieved via the listing endpoint.
 */
@Schema( name = "NpmApiTokenCreated", description = "A freshly generated NPM personal access token, including its plaintext value" )
public class NpmApiTokenCreated implements Serializable
{
    private static final long serialVersionUID = 1L;

    @Schema( description = "Metadata about the generated token" )
    private NpmApiTokenInfo info;

    @Schema( description = "Plaintext token value — shown only this once, store it securely" )
    private String token;

    public NpmApiTokenCreated()
    {
    }

    public NpmApiTokenCreated( NpmApiTokenInfo info, String token )
    {
        this.info = info;
        this.token = token;
    }

    public NpmApiTokenInfo getInfo() { return info; }
    public void setInfo( NpmApiTokenInfo info ) { this.info = info; }

    public String getToken() { return token; }
    public void setToken( String token ) { this.token = token; }
}
