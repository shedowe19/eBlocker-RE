/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be
 * approved by the European Commission - subsequent versions of the EUPL
 * (the "License"); You may not use this work except in compliance with
 * the License. You may obtain a copy of the License at:
 *
 *   https://joinup.ec.europa.eu/page/eupl-text-11-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.eblocker.server.http.security;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.restexpress.exception.ServiceException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Versioned password/PIN hashes; the old unversioned format remains read-only. */
public final class PasswordUtil {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int KEY_LENGTH = 32;
    private static final int SALT_LENGTH = 32;
    private static final int LEGACY_LENGTH = SALT_LENGTH + KEY_LENGTH;
    // OWASP Password Storage Cheat Sheet: Argon2id, 19 MiB, two iterations, one lane.
    private static final int MEMORY_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;
    private static final String PREFIX = "$argon2id$v=19$m=19456,t=2,p=1$";
    private static final int ENCODED_LENGTH = PREFIX.length() + 43 + 1 + 43;
    private static final Pattern ENCODED = Pattern.compile(Pattern.quote(PREFIX)
            + "([A-Za-z0-9+/]{43})\\$([A-Za-z0-9+/]{43})");
    // Bound concurrent working memory, including PIN hashing outside SecurityService.
    private static final Semaphore HASH_SLOTS = new Semaphore(2, true);

    private PasswordUtil() {
    }

    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return salt;
    }

    public static byte[] hashPassword(String password) {
        if (password == null) throw new IllegalArgumentException("Password is required");
        char[] characters = password.toCharArray();
        try {
            return hashPassword(characters, generateSalt());
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    /** Retains the deterministic-salt API for callers and tests, but always writes Argon2id. */
    public static byte[] hashPassword(char[] password, byte[] salt) {
        if (password == null || salt == null || salt.length != SALT_LENGTH) {
            throw new IllegalArgumentException("Invalid password hashing parameters");
        }
        byte[] hash = argon2(password, salt);
        try {
            Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
            return (PREFIX + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash))
                    .getBytes(StandardCharsets.US_ASCII);
        } finally {
            Arrays.fill(hash, (byte) 0);
        }
    }

    public static boolean verifyPassword(String password, byte[] stored) {
        if (password == null || stored == null) return false;
        // No cost supplied in a stored hash can increase CPU or memory: only the supported
        // version/profile is accepted. Check byte length before decoding or allocating a string.
        if (stored.length != LEGACY_LENGTH && stored.length != ENCODED_LENGTH) return false;
        char[] characters = password.toCharArray();
        byte[] actual = null;
        byte[] expected = null;
        try {
            if (stored.length == LEGACY_LENGTH) {
                byte[] salt = Arrays.copyOfRange(stored, 0, SALT_LENGTH);
                expected = Arrays.copyOfRange(stored, SALT_LENGTH, LEGACY_LENGTH);
                actual = legacyPbkdf2(characters, salt);
            } else {
                Matcher matcher = ENCODED.matcher(new String(stored, StandardCharsets.US_ASCII));
                if (!matcher.matches()) return false;
                byte[] salt = Base64.getDecoder().decode(matcher.group(1));
                expected = Base64.getDecoder().decode(matcher.group(2));
                if (salt.length != SALT_LENGTH || expected.length != KEY_LENGTH) return false;
                actual = argon2(characters, salt);
            }
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(characters, '\0');
            if (actual != null) Arrays.fill(actual, (byte) 0);
            if (expected != null) Arrays.fill(expected, (byte) 0);
        }
    }

    /** Call only after successful verification with the plaintext password. */
    public static boolean needsRehash(byte[] stored) {
        return stored != null && stored.length == LEGACY_LENGTH;
    }

    private static byte[] legacyPbkdf2(char[] password, byte[] salt) {
        PBEKeySpec keySpec = new PBEKeySpec(password, salt, 3, KEY_LENGTH * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(keySpec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new ServiceException("error.credentials.cannotHashPassword");
        } finally {
            keySpec.clearPassword();
        }
    }

    private static byte[] argon2(char[] password, byte[] salt) {
        boolean acquired = false;
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(MEMORY_KIB).withIterations(ITERATIONS).withParallelism(1)
                .withSalt(salt).build();
        try {
            HASH_SLOTS.acquire();
            acquired = true;
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(parameters);
            byte[] result = new byte[KEY_LENGTH];
            generator.generateBytes(password, result);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("error.credentials.hashingInterrupted");
        } finally {
            parameters.clear();
            if (acquired) HASH_SLOTS.release();
        }
    }
}
