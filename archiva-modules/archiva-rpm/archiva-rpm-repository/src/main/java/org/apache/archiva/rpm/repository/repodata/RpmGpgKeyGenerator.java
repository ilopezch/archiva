package org.apache.archiva.rpm.repository.repodata;

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

import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Security;
import java.util.Date;

/**
 * Generates a new RSA 4096-bit PGP key pair for signing RPM repodata.
 * The key is generated with no passphrase (empty char array) so it can be used
 * programmatically without human interaction.
 */
public final class RpmGpgKeyGenerator
{
    private RpmGpgKeyGenerator()
    {
    }

    static
    {
        if ( Security.getProvider( BouncyCastleProvider.PROVIDER_NAME ) == null )
        {
            Security.addProvider( new BouncyCastleProvider() );
        }
    }

    /**
     * Generates an RSA 4096-bit signing key with no passphrase.
     *
     * @param identity key identity string, e.g. {@code "Archiva RPM Repository <archiva@localhost>"}
     * @return the secret key (extract the public key with {@link PGPSecretKey#getPublicKey()})
     */
    public static PGPSecretKey generate( String identity ) throws PGPException
    {
        try
        {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance( "RSA", BouncyCastleProvider.PROVIDER_NAME );
            kpg.initialize( 4096 );
            KeyPair kp = kpg.generateKeyPair();

            PGPKeyPair pgpKp = new JcaPGPKeyPair( PGPPublicKey.RSA_SIGN, kp, new Date() );

            PGPKeyRingGenerator krg = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                pgpKp,
                identity,
                new JcaPGPDigestCalculatorProviderBuilder().build().get( HashAlgorithmTags.SHA256 ),
                null,
                null,
                new JcaPGPContentSignerBuilder( pgpKp.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256 ),
                new JcePBESecretKeyEncryptorBuilder( SymmetricKeyAlgorithmTags.NULL )
                    .setProvider( BouncyCastleProvider.PROVIDER_NAME ).build( new char[0] )
            );

            return krg.generateSecretKeyRing().getSecretKey();
        }
        catch ( NoSuchAlgorithmException | NoSuchProviderException e )
        {
            throw new PGPException( "Failed to generate RSA key pair", e );
        }
    }
}
