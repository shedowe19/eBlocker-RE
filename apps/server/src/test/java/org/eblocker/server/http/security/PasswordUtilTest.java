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

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class PasswordUtilTest {

    @Test
    public void test() {

        String password = "Hello World";
        byte[] salt = PasswordUtil.generateSalt();

        byte[] hash1 = PasswordUtil.hashPassword(password.toCharArray(), salt);
        byte[] hash2 = PasswordUtil.hashPassword(password.toCharArray(), salt);

        assertArrayEquals(hash1, hash2);

        assertTrue(PasswordUtil.verifyPassword(password, hash1));
        assertTrue(PasswordUtil.verifyPassword(password, hash2));

        assertFalse(PasswordUtil.verifyPassword(password + "x", hash2));
        assertFalse(PasswordUtil.verifyPassword("x", hash2));
        assertFalse(PasswordUtil.verifyPassword("", hash2));
    }

    @Test
    public void verifiesTheExistingUnversionedFormatWithoutWritingItAgain() {
        // Independently generated with Python hashlib.pbkdf2_hmac('sha1', ..., iterations=3).
        byte[] legacy = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8k1GiJ4AOIjUd4kLv6UTmzFtDsEhelDkYpfJhOiy8hRg==");
        assertTrue(PasswordUtil.verifyPassword("legacy-admin-password", legacy));
        assertFalse(PasswordUtil.verifyPassword("wrong", legacy));
        assertTrue(PasswordUtil.needsRehash(legacy));
        byte[] modern = PasswordUtil.hashPassword("legacy-admin-password");
        assertFalse(PasswordUtil.needsRehash(modern));
        assertTrue(new String(modern, StandardCharsets.US_ASCII).startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
        assertTrue(PasswordUtil.verifyPassword("legacy-admin-password", modern));
    }

    @Test
    public void createsIndependentSaltsAndPreservesUnicodeAndLongPasswords() {
        String password = "ä🔒 password " + "x".repeat(100);
        byte[] first = PasswordUtil.hashPassword(password);
        byte[] second = PasswordUtil.hashPassword(password);
        assertNotEquals(new String(first, StandardCharsets.US_ASCII), new String(second, StandardCharsets.US_ASCII));
        assertTrue(PasswordUtil.verifyPassword(password, first));
        assertFalse(PasswordUtil.verifyPassword(password + "x", first));
    }

    @Test
    public void rejectsMalformedFormatsAndUntrustedWorkFactorsBeforeDerivation() {
        byte[] valid = PasswordUtil.hashPassword("test password");
        String encoded = new String(valid, StandardCharsets.US_ASCII);
        assertFalse(PasswordUtil.verifyPassword(null, valid));
        assertFalse(PasswordUtil.verifyPassword("test password", null));
        for (byte[] malformed : new byte[][] {new byte[0], new byte[1], new byte[63], new byte[65], new byte[10_000],
                Arrays.copyOf(valid, valid.length - 1),
                encoded.replace("m=19456", "m=99999").getBytes(StandardCharsets.US_ASCII),
                encoded.replace("t=2", "t=9").getBytes(StandardCharsets.US_ASCII),
                encoded.replace("p=1", "p=9").getBytes(StandardCharsets.US_ASCII),
                encoded.replace("v=19", "v=99").getBytes(StandardCharsets.US_ASCII),
                encoded.replace('$', '!').getBytes(StandardCharsets.US_ASCII)}) {
            assertFalse(PasswordUtil.verifyPassword("test password", malformed));
        }
        byte[] altered = valid.clone();
        altered[altered.length - 2] = altered[altered.length - 2] == 'A' ? (byte) 'B' : (byte) 'A';
        assertFalse(PasswordUtil.verifyPassword("test password", altered));
        assertEquals(encoded, new String(valid, StandardCharsets.US_ASCII));
    }
}
