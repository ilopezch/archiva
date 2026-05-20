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
import org.apache.archiva.repository.ManagedRepository;
import org.apache.archiva.repository.RepositoryType;

/**
 * REST DTO for a managed RPM repository.
 */
@Schema( name = "RpmManagedRepository", description = "A managed RPM (yum/dnf) repository" )
public class RpmManagedRepository extends Repository
{
    private static final long serialVersionUID = 1L;

    @Schema( description = "Path to an operator-supplied GPG secret key file. Leave blank to use the auto-generated key." )
    private String gpgKeyPath;

    @Schema( description = "User-ID (name/email) for the GPG key. Used only when generating a new key." )
    private String gpgUserId;

    public RpmManagedRepository()
    {
        super.setCharacteristic( Repository.CHARACTERISTIC_MANAGED );
        super.setType( RepositoryType.RPM.name() );
        super.setLayout( "rpm-default" );
    }

    public static RpmManagedRepository of( ManagedRepository repo )
    {
        RpmManagedRepository dto = new RpmManagedRepository();
        dto.setId( repo.getId() );
        dto.setName( repo.getName() );
        dto.setDescription( repo.getDescription() );
        dto.setLocation( repo.getLocation().toASCIIString() );
        dto.setScanned( repo.isScanned() );
        dto.setSchedulingDefinition( repo.getSchedulingDefinition() );
        return dto;
    }

    public String getGpgKeyPath() { return gpgKeyPath; }
    public void setGpgKeyPath( String gpgKeyPath ) { this.gpgKeyPath = gpgKeyPath; }

    public String getGpgUserId() { return gpgUserId; }
    public void setGpgUserId( String gpgUserId ) { this.gpgUserId = gpgUserId; }
}
