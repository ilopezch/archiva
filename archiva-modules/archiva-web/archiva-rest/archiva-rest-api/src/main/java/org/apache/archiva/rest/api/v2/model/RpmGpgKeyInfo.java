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
 * REST DTO that carries GPG key information for an RPM managed repository.
 */
@Schema( name = "RpmGpgKeyInfo", description = "GPG signing key information for an RPM managed repository" )
public class RpmGpgKeyInfo implements Serializable
{
    private static final long serialVersionUID = 1L;

    @Schema( description = "Hex fingerprint of the public key" )
    private String fingerprint;

    @Schema( description = "User-ID embedded in the key (name + email)" )
    private String userId;

    @Schema( description = "Key algorithm name (e.g. RSA, DSA, ECDSA)" )
    private String algorithm;

    @Schema( description = "Key bit strength (e.g. 4096 for RSA)" )
    private int bitStrength;

    @Schema( description = "Key creation date (ISO-8601 instant)" )
    private String created;

    @Schema( description = "Key expiry date (ISO-8601 instant), or null if the key does not expire" )
    private String expires;

    @Schema( description = "ASCII-armored PGP public key block" )
    private String armoredPublicKey;

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint( String fingerprint ) { this.fingerprint = fingerprint; }

    public String getUserId() { return userId; }
    public void setUserId( String userId ) { this.userId = userId; }

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm( String algorithm ) { this.algorithm = algorithm; }

    public int getBitStrength() { return bitStrength; }
    public void setBitStrength( int bitStrength ) { this.bitStrength = bitStrength; }

    public String getCreated() { return created; }
    public void setCreated( String created ) { this.created = created; }

    public String getExpires() { return expires; }
    public void setExpires( String expires ) { this.expires = expires; }

    public String getArmoredPublicKey() { return armoredPublicKey; }
    public void setArmoredPublicKey( String armoredPublicKey ) { this.armoredPublicKey = armoredPublicKey; }
}
