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
package org.eblocker.server.common.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eblocker.server.http.ssl.AppWhitelistModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Persists JSON entities, their numeric ID sequences and historical key aliases.
 * Collection operations retain lexical key ordering and exclude singleton/sequence keys.
 */
final class JedisEntityRepository {
    private static final Logger LOG = LoggerFactory.getLogger(JedisEntityRepository.class);

    private static final String ID_PREFIX_SEPARATOR = ":";

    private static final String ID_SEQUENCE_SUFFIX = "sequence";

    // map containing key prefixes for entities with different prefix than from class.getSimpleName()
    private static final Map<Class<?>, String> ID_PREFIX = new HashMap<>();

    static {
        ID_PREFIX.put(AppWhitelistModule.class, "AppModuleDetails");
    }

    private final JedisPool pool;
    private final ObjectMapper objectMapper;

    JedisEntityRepository(JedisPool pool, ObjectMapper objectMapper) {
        this.pool = pool;
        this.objectMapper = objectMapper;
    }

    String getKey(Class<?> entityClass, int id) {
        return getKey(entityClass) + ID_PREFIX_SEPARATOR + id;
    }

    String getKey(Class<?> entityClass) {
        String prefix = ID_PREFIX.get(entityClass);
        if (prefix == null) {
            return entityClass.getSimpleName();
        }
        return prefix;
    }

    String getIdSequenceKey(Class<?> entityClass) {
        return getKey(entityClass) + ID_PREFIX_SEPARATOR + ID_SEQUENCE_SUFFIX;
    }

    SortedSet<String> getIds(Class<?> entityClass) {
        try (Jedis jedis = pool.getResource()) {
            return getKeys(jedis, entityClass);
        }
    }

    SortedSet<String> getKeys(Jedis jedis, Class<?> entityClass) {
        return new TreeSet<>(jedis.keys(getKey(entityClass) + ID_PREFIX_SEPARATOR + "[0-9]*"));
    }

    <T> T get(Class<T> entityClass, int id) {
        return get(entityClass, getKey(entityClass, id));
    }

    <T> T get(Class<T> entityClass) {
        return get(entityClass, getKey(entityClass));
    }

    <T> T get(Class<T> entityClass, String id) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.get(id);
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, entityClass);

        } catch (IOException e) {
            LOG.error("Cannot deserialize entity of type {}.", entityClass.getName(), e);
            return null;
        }
    }

    <T> List<T> getAll(Class<T> entityClass) {
        SortedSet<String> ids = getIds(entityClass);
        List<T> entities = new ArrayList<>();
        for (String id : ids) {
            T entity = get(entityClass, id);
            if (entity != null) {
                entities.add(entity);
            }
        }
        return entities;
    }

    <T> T save(T entity, int id) {
        try (Jedis jedis = pool.getResource()) {
            String json = objectMapper.writeValueAsString(entity);
            jedis.set(getKey(entity.getClass(), id), json);
            return entity;

        } catch (JsonProcessingException e) {
            LOG.error("Cannot save entity {} with id {}.", entity.getClass().getName(), id, e);
            return null;
        }
    }

    boolean compareAndSetUserPin(int userId, byte[] expectedPin, byte[] replacementPin) {
        return compareAndSetUserJson(userId, current -> {
            JsonNode pinNode = current.get("pin");
            byte[] storedPin = pinNode == null || pinNode.isNull() ? null : pinNode.binaryValue();
            if (expectedPin == null ? storedPin != null : storedPin == null || !MessageDigest.isEqual(expectedPin, storedPin)) {
                return null;
            }
            current.put("pin", replacementPin);
            return current;
        });
    }

    boolean compareAndSetUser(UserModule expected, UserModule replacement) {
        if (replacement == null || replacement.getId() == null
                || (expected != null && !Objects.equals(expected.getId(), replacement.getId()))) {
            throw new IllegalArgumentException("User identity must remain unchanged");
        }
        try {
            // Normalize binary/date nodes through the actual persisted JSON format.
            JsonNode expectedJson = expected == null ? null : objectMapper.readTree(objectMapper.writeValueAsString(expected));
            ObjectNode replacementJson = (ObjectNode) objectMapper.readTree(objectMapper.writeValueAsString(replacement));
            return compareAndSetUserJson(replacement.getId(), expected == null, current -> {
                if (current == null) return replacementJson;
                if (expected == null) return null;
                // Older records may omit fields whose constructor now supplies defaults.
                // Compare exactly what get(UserModule) exposed to the caller, while
                // retaining fields unknown to this server version in the actual record.
                UserModule currentUser = objectMapper.treeToValue(current, UserModule.class);
                JsonNode currentJson = objectMapper.readTree(objectMapper.writeValueAsString(currentUser));
                if (!currentJson.equals(expectedJson)) return null;
                current.setAll(replacementJson);
                return current;
            });
        } catch (IOException e) {
            throw new IllegalStateException("Could not update the user credential record");
        }
    }

    private boolean compareAndSetUserJson(int userId, UserJsonChange change) {
        return compareAndSetUserJson(userId, false, change);
    }

    private boolean compareAndSetUserJson(int userId, boolean allowCreate, UserJsonChange change) {
        String key = getKey(UserModule.class, userId);
        try (Jedis jedis = pool.getResource()) {
            jedis.watch(key);
            try {
                String json = jedis.get(key);
                if (json == null && !allowCreate) return false;
                JsonNode parsed = json == null ? null : objectMapper.readTree(json);
                if (parsed != null && !(parsed instanceof ObjectNode)) return false;
                ObjectNode replacement = change.apply((ObjectNode) parsed);
                if (replacement == null) return false;
                String updated = objectMapper.writeValueAsString(replacement);
                try (Transaction transaction = jedis.multi()) {
                    transaction.set(key, updated);
                    List<Object> results = transaction.exec();
                    return results != null && results.size() == 1 && "OK".equals(results.get(0));
                }
            } finally {
                jedis.unwatch();
            }
        } catch (IOException e) {
            // Do not include JSON or parser messages: these records contain credentials.
            throw new IllegalStateException("Could not update the user credential record");
        }
    }

    private interface UserJsonChange {
        ObjectNode apply(ObjectNode current) throws IOException;
    }

    <T> T save(T entity) {
        try (Jedis jedis = pool.getResource()) {
            String json = objectMapper.writeValueAsString(entity);
            jedis.set(getKey(entity.getClass()), json);
            return entity;

        } catch (JsonProcessingException e) {
            LOG.error("Cannot save singleton entity {}.", entity.getClass().getName(), e);
            return null;
        }
    }

    void delete(Class<?> entityClass, int id) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(getKey(entityClass, id));
        }
    }

    void delete(Class<?> entityClass) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(getKey(entityClass));
        }
    }

    Set<String> keys(String globPattern) {
        try (Jedis jedis = pool.getResource()) {
            return new TreeSet<>(jedis.keys(globPattern));
        }
    }

    void delete(String key) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(key);
        }
    }

    void deleteAll(Class<?> entityClass) {
        try (Jedis jedis = pool.getResource()) {
            for (String id : getKeys(jedis, entityClass)) {
                jedis.del(id);
            }
        }
    }

    int nextId(Class<?> entityClass) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.incr(getIdSequenceKey(entityClass)).intValue();
        }
    }

    void setIdSequence(Class<?> entityClass, int value) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(getIdSequenceKey(entityClass), String.valueOf(value));
        }
    }
}
