/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reshapr.proxy.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link LRUCache}.
 * @author vaishnav
 */
class LRUCacheTest {

    @Test
    void testLRUCacheEvictsEldest() {
        LRUCache<String, String> cache = new LRUCache<>(2);

        cache.put("1", "one");
        cache.put("2", "two");

        assertEquals(2, cache.size());
        assertEquals("one", cache.get("1"));
        assertEquals("two", cache.get("2"));

        cache.put("3", "three");

        assertEquals(2, cache.size());
        assertNull(cache.get("1")); // "1" should be evicted because "2" was accessed last
        assertEquals("two", cache.get("2"));
        assertEquals("three", cache.get("3"));
    }
}
