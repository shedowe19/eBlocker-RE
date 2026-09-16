// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.common.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JedisUserCompareAndSetTest {
    private Jedis jedis;
    private Transaction transaction;
    private JedisDataSource data;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        jedis = mock(Jedis.class);
        transaction = mock(Transaction.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.multi()).thenReturn(transaction);
        when(transaction.exec()).thenReturn(List.of("OK"));
        data = new JedisDataSource(pool, mapper);
    }

    private UserModule user(byte[] pin) {
        return new UserModule(100, 300, "Alice", null, null, UserRole.CHILD, false, pin, new HashMap<>(), null, null, null);
    }

    @Test
    void pinCasPreservesCurrentAndUnknownFieldsAndUsesWatchBeforeRead() throws Exception {
        when(jedis.get("UserModule:100")).thenReturn("{\"id\":100,\"pin\":\"AQID\",\"name\":\"Changed\",\"associatedProfileId\":999,\"futureField\":{\"keep\":true}}");
        assertTrue(data.compareAndSetUserPin(100, new byte[]{1, 2, 3}, new byte[]{4, 5, 6}));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(transaction).set(eq("UserModule:100"), json.capture());
        JsonNode actual = mapper.readTree(json.getValue());
        assertArrayEquals(new byte[]{4, 5, 6}, actual.get("pin").binaryValue());
        assertEquals("Changed", actual.get("name").textValue());
        assertEquals(999, actual.get("associatedProfileId").intValue());
        assertTrue(actual.get("futureField").get("keep").booleanValue());
        var order = inOrder(jedis, transaction);
        order.verify(jedis).watch("UserModule:100");
        order.verify(jedis).get("UserModule:100");
        order.verify(jedis).multi();
        order.verify(transaction).set(eq("UserModule:100"), anyString());
        order.verify(transaction).exec();
        verify(jedis).unwatch();
        verify(jedis).close();
    }

    @Test
    void pinMismatchAndMissingUsersDoNotWriteOrRecreate() {
        when(jedis.get("UserModule:100")).thenReturn("{\"pin\":\"AQID\"}", null);
        assertFalse(data.compareAndSetUserPin(100, new byte[]{9}, new byte[]{4}));
        assertFalse(data.compareAndSetUserPin(100, null, new byte[]{4}));
        verify(jedis, never()).multi();
    }

    @Test
    void removingPinUsesNullAndConcurrentWriteAborts() throws Exception {
        when(jedis.get("UserModule:100")).thenReturn("{\"pin\":\"AQID\"}");
        when(transaction.exec()).thenReturn(null);
        assertFalse(data.compareAndSetUserPin(100, new byte[]{1, 2, 3}, null));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(transaction).set(eq("UserModule:100"), json.capture());
        assertTrue(mapper.readTree(json.getValue()).get("pin").isNull());
    }

    @Test
    void fullUserCasNormalizesBase64RepresentationAndRejectsStalePin() throws Exception {
        UserModule expected = user(new byte[]{1, 2, 3});
        UserModule replacement = user(new byte[]{1, 2, 3});
        replacement.setName("Renamed");
        when(jedis.get("UserModule:100")).thenReturn(mapper.writeValueAsString(expected));
        assertTrue(data.compareAndSetUser(expected, replacement));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(transaction).set(eq("UserModule:100"), json.capture());
        assertEquals("Renamed", mapper.readTree(json.getValue()).get("name").textValue());
        when(jedis.get("UserModule:100")).thenReturn(mapper.writeValueAsString(user(new byte[]{8, 9})));
        assertFalse(data.compareAndSetUser(expected, replacement));
        verify(jedis, times(1)).multi();
    }

    @Test
    void fullUserCasRejectsStaleProfileAndChangedIdentity() throws Exception {
        UserModule expected = user(null);
        UserModule current = user(null);
        current.setName("Concurrent");
        when(jedis.get("UserModule:100")).thenReturn(mapper.writeValueAsString(current));
        assertFalse(data.compareAndSetUser(expected, user(null)));
        assertThrows(IllegalArgumentException.class, () -> data.compareAndSetUser(expected, null));
        verify(jedis, never()).multi();
    }

    @Test
    void oldRecordsWithMissingDefaultsAndUnknownFieldsCanBeUpdatedWithoutDataLoss() throws Exception {
        String legacy = "{\"id\":100,\"associatedProfileId\":300,\"name\":\"Alice\",\"pin\":\"AQID\",\"futureField\":\"retain\"}";
        when(jedis.get("UserModule:100")).thenReturn(legacy);
        UserModule expected = mapper.readValue(legacy, UserModule.class);
        UserModule replacement = mapper.readValue(legacy, UserModule.class);
        replacement.setName("Renamed");
        assertTrue(data.compareAndSetUser(expected, replacement));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(transaction).set(eq("UserModule:100"), json.capture());
        assertEquals("retain", mapper.readTree(json.getValue()).get("futureField").textValue());
        assertEquals("Renamed", mapper.readTree(json.getValue()).get("name").textValue());
    }

    @Test
    void corruptStoredCredentialHasSanitizedExceptionAndNeverWrites() {
        when(jedis.get("UserModule:100")).thenReturn("{\"pin\":\"secret malformed");
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> data.compareAndSetUserPin(100, new byte[]{1}, new byte[]{2}));
        assertFalse(exception.getMessage().contains("secret"));
        assertNull(exception.getCause());
        verify(jedis, never()).multi();
        verify(jedis).unwatch();
    }

    @Test
    void absentUserCanBeCreatedAtomicallyWithoutBlindSet() throws Exception {
        UserModule created = user(new byte[]{4});
        assertTrue(data.compareAndSetUser(null, created));
        var order = inOrder(jedis, transaction);
        order.verify(jedis).watch("UserModule:100");
        order.verify(jedis).get("UserModule:100");
        order.verify(jedis).multi();
        order.verify(transaction).set("UserModule:100", mapper.writeValueAsString(created));
        order.verify(transaction).exec();
        verify(jedis).unwatch();
        verify(jedis, never()).set(anyString(), anyString());
    }

    @Test
    void createNeverOverwritesAnExistingUserOrCredential() throws Exception {
        when(jedis.get("UserModule:100")).thenReturn(mapper.writeValueAsString(user(new byte[]{9})));
        assertFalse(data.compareAndSetUser(null, user(null)));
        verify(jedis, never()).multi();
        verify(jedis).unwatch();
    }

    @Test
    void concurrentCreationAbortsWatchedCreateAndDoesNotRetryBlindly() {
        when(transaction.exec()).thenReturn(null);
        assertFalse(data.compareAndSetUser(null, user(null)));
        verify(transaction, times(1)).exec();
        verify(jedis, never()).set(anyString(), anyString());
        verify(jedis).unwatch();
    }
}
