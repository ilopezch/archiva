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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Persisted store for server-issued NPM API tokens.
 *
 * Tokens are opaque, cryptographically random strings. Only their SHA-256 hash is ever
 * persisted or kept in memory — the plaintext value is returned to the caller exactly once,
 * at generation time, and cannot be recovered afterwards (matching how GitHub/npm personal
 * access tokens behave).
 *
 * One token authenticates its owning user against any repository they are otherwise
 * authorized to access — it is a personal access token, not scoped to a single repository.
 */
@Service( "npmApiTokenStore" )
public class NpmApiTokenStore
{
    private static final Logger log = LoggerFactory.getLogger( NpmApiTokenStore.class );

    private static final String TOKEN_PREFIX = "npm_";

    // Avoid rewriting the store file on every authenticated request — only persist
    // updated "last used" timestamps when they have moved by at least this much.
    private static final long LAST_USED_PERSIST_THRESHOLD_MS = 60L * 1000;

    private final ObjectMapper mapper = new ObjectMapper();

    private final SecureRandom secureRandom = new SecureRandom();

    private final ConcurrentHashMap<String, NpmApiToken> tokensByHash = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> persistedLastUsedAt = new ConcurrentHashMap<>();

    private final Path storeFile;

    public NpmApiTokenStore()
    {
        this( resolveDefaultStoreFile() );
    }

    NpmApiTokenStore( Path storeFile )
    {
        this.storeFile = storeFile;
        load();
    }

    private static Path resolveDefaultStoreFile()
    {
        String base = System.getProperty( "appserver.base", "." );
        return Paths.get( base, "data", "npm-api-tokens.json" );
    }

    /**
     * Generates a new personal access token for the given user.
     *
     * @return the plaintext token value — the only time it is ever available; callers must
     * display it to the user immediately, it cannot be retrieved again.
     */
    public synchronized GeneratedToken generateToken( String username, String label )
    {
        String plaintext = TOKEN_PREFIX + randomTokenValue();
        String hash = hash( plaintext );
        long now = System.currentTimeMillis();
        NpmApiToken token = new NpmApiToken( UUID.randomUUID().toString(), hash, username,
                                             label == null ? "" : label, now, 0L );
        tokensByHash.put( hash, token );
        persistedLastUsedAt.put( hash, 0L );
        save();
        return new GeneratedToken( token, plaintext );
    }

    public List<NpmApiToken> listTokens( String username )
    {
        return tokensByHash.values().stream()
            .filter( t -> t.getUsername().equals( username ) )
            .sorted( ( a, b ) -> Long.compare( b.getCreatedAt(), a.getCreatedAt() ) )
            .collect( Collectors.toList() );
    }

    /**
     * Revokes (deletes) a token, but only if it belongs to the given user.
     *
     * @return true if a token was found and removed
     */
    public synchronized boolean revokeToken( String username, String tokenId )
    {
        for ( NpmApiToken token : tokensByHash.values() )
        {
            if ( token.getId().equals( tokenId ) && token.getUsername().equals( username ) )
            {
                tokensByHash.remove( token.getTokenHash() );
                persistedLastUsedAt.remove( token.getTokenHash() );
                save();
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a presented token value to the username that owns it, updating its
     * "last used" timestamp. Returns {@code null} when the token is unknown — callers
     * should then fall through to regular credential-based authentication.
     */
    public String resolveUsername( String plaintextToken )
    {
        if ( plaintextToken == null || !plaintextToken.startsWith( TOKEN_PREFIX ) )
        {
            return null;
        }
        NpmApiToken token = tokensByHash.get( hash( plaintextToken ) );
        if ( token == null )
        {
            return null;
        }
        touchLastUsed( token );
        return token.getUsername();
    }

    private void touchLastUsed( NpmApiToken token )
    {
        long now = System.currentTimeMillis();
        token.setLastUsedAt( now );
        Long lastPersisted = persistedLastUsedAt.getOrDefault( token.getTokenHash(), 0L );
        if ( now - lastPersisted >= LAST_USED_PERSIST_THRESHOLD_MS )
        {
            persistedLastUsedAt.put( token.getTokenHash(), now );
            save();
        }
    }

    private String randomTokenValue()
    {
        byte[] buf = new byte[32];
        secureRandom.nextBytes( buf );
        return Base64.getUrlEncoder().withoutPadding().encodeToString( buf );
    }

    private static String hash( String value )
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance( "SHA-256" );
            byte[] bytes = digest.digest( value.getBytes( StandardCharsets.UTF_8 ) );
            StringBuilder sb = new StringBuilder( bytes.length * 2 );
            for ( byte b : bytes )
            {
                sb.append( String.format( "%02x", b ) );
            }
            return sb.toString();
        }
        catch ( NoSuchAlgorithmException e )
        {
            // SHA-256 is guaranteed to be available on every JVM
            throw new IllegalStateException( e );
        }
    }

    private synchronized void load()
    {
        if ( !Files.exists( storeFile ) )
        {
            return;
        }
        try
        {
            String json = new String( Files.readAllBytes( storeFile ), StandardCharsets.UTF_8 );
            NpmApiToken[] tokens = mapper.readValue( json, NpmApiToken[].class );
            for ( NpmApiToken token : tokens )
            {
                tokensByHash.put( token.getTokenHash(), token );
                persistedLastUsedAt.put( token.getTokenHash(), token.getLastUsedAt() );
            }
            log.debug( "Loaded {} NPM API token(s) from {}", tokens.length, storeFile );
        }
        catch ( Exception e )
        {
            log.warn( "Could not read NPM API token store {} — starting fresh: {}", storeFile, e.getMessage() );
        }
    }

    private synchronized void save()
    {
        try
        {
            Files.createDirectories( storeFile.getParent() );
            List<NpmApiToken> tokens = new ArrayList<>( tokensByHash.values() );
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes( tokens );
            Files.write( storeFile, bytes );
        }
        catch ( Exception e )
        {
            log.warn( "Could not write NPM API token store {}: {}", storeFile, e.getMessage() );
        }
    }

    /**
     * Result of a token-generation request: the persisted record plus the plaintext
     * value, which is only available at this single point in time.
     */
    public static final class GeneratedToken
    {
        private final NpmApiToken token;
        private final String plaintextValue;

        GeneratedToken( NpmApiToken token, String plaintextValue )
        {
            this.token = token;
            this.plaintextValue = plaintextValue;
        }

        public NpmApiToken getToken()
        {
            return token;
        }

        public String getPlaintextValue()
        {
            return plaintextValue;
        }
    }
}
