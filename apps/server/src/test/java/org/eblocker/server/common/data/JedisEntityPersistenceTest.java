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
import org.eblocker.server.http.ssl.AppWhitelistModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JedisEntityPersistenceTest {
    private Jedis jedis;
    private JedisPool pool;
    private JedisDataSource dataSource;

    @BeforeEach
    void setup() {
        jedis = mock(Jedis.class);
        pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        dataSource = new JedisDataSource(pool, new ObjectMapper());
    }

    @Test
    void appWhitelistRetainsHistoricPrefixAndSequence() {
        when(jedis.get("AppModuleDetails:7")).thenReturn("{\"id\":7,\"name\":\"Example\"}");
        when(jedis.keys("AppModuleDetails:[0-9]*")).thenReturn(Set.of("AppModuleDetails:7"));
        when(jedis.incr("AppModuleDetails:sequence")).thenReturn(8L);

        AppWhitelistModule module = dataSource.get(AppWhitelistModule.class, 7);
        assertNotNull(module);
        assertEquals("Example", module.getName());
        assertEquals(Set.of("AppModuleDetails:7"), dataSource.getIds(AppWhitelistModule.class));
        assertEquals(8, dataSource.nextId(AppWhitelistModule.class));
        dataSource.setIdSequence(AppWhitelistModule.class, 12);
        verify(jedis).set("AppModuleDetails:sequence", "12");
        assertSame(module, dataSource.save(module, 7));
        verify(jedis).set(eq("AppModuleDetails:7"), anyString());
        dataSource.delete(AppWhitelistModule.class, 7);
        verify(jedis).del("AppModuleDetails:7");
    }

    @Test
    void entityListingIsLexicallyOrderedAndSkipsMissingOrMalformedRecords() {
        when(jedis.keys("Entity:[0-9]*")).thenReturn(Set.of("Entity:2", "Entity:10", "Entity:3", "Entity:4"));
        when(jedis.get("Entity:2")).thenReturn("{\"id\":2}");
        when(jedis.get("Entity:10")).thenReturn("{\"id\":10}");
        when(jedis.get("Entity:3")).thenReturn(null);
        when(jedis.get("Entity:4")).thenReturn("invalid");

        assertEquals(List.of("Entity:10", "Entity:2", "Entity:3", "Entity:4"),
                new ArrayList<>(dataSource.getIds(Entity.class)));
        assertEquals(List.of(10, 2), dataSource.getAll(Entity.class).stream().map(Entity::getId).toList());
    }

    @Test
    void missingEmptyAndMalformedJsonRemainAbsent() {
        when(jedis.get("Entity")).thenReturn(null, "", "invalid");
        assertNull(dataSource.get(Entity.class));
        assertNull(dataSource.get(Entity.class));
        assertNull(dataSource.get(Entity.class));
        verify(jedis, times(3)).close();
    }

    @Test
    void singletonAndNumberedEntitiesUseUnchangedJson() {
        Entity entity = new Entity();
        entity.setId(23);
        assertSame(entity, dataSource.save(entity));
        assertSame(entity, dataSource.save(entity, 7));
        verify(jedis).set("Entity", "{\"id\":23}");
        verify(jedis).set("Entity:7", "{\"id\":23}");
    }

    @Test
    void serializationFailureLeavesStoredValuesUntouchedAndClosesConnection() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        Entity entity = new Entity();
        when(failingMapper.writeValueAsString(entity)).thenThrow(new JsonProcessingException("test failure") { });
        dataSource = new JedisDataSource(pool, failingMapper);

        assertNull(dataSource.save(entity));
        assertNull(dataSource.save(entity, 7));
        verify(jedis, never()).set(anyString(), anyString());
        verify(jedis, times(2)).close();
    }

    @Test
    void deletingEntityCollectionRetainsSingletonAndSequence() {
        when(jedis.keys("Entity:[0-9]*")).thenReturn(Set.of("Entity:7", "Entity:8"));
        dataSource.deleteAll(Entity.class);
        verify(jedis).del("Entity:7");
        verify(jedis).del("Entity:8");
        verify(jedis, never()).del("Entity");
        verify(jedis, never()).del("Entity:sequence");

        dataSource.delete(Entity.class);
        verify(jedis).del("Entity");
    }

    @Test
    void rawKeyAccessPreservesCallerPatternsAndDeletion() {
        when(jedis.keys("custom:*")).thenReturn(Set.of("custom:z", "custom:a"));
        assertEquals(List.of("custom:a", "custom:z"), new ArrayList<>(dataSource.keys("custom:*")));
        dataSource.delete("custom:a");
        verify(jedis).del("custom:a");
    }

    public static class Entity {
        private int id;

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }
    }
}
