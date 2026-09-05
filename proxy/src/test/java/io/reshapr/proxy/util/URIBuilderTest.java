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

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link URIBuilder}.
 * @author vaishnav
 */
class URIBuilderTest {

    @Test
    void testBuildURIFromPatternWithMap() {
        String pattern = "http://example.com/api/{resource}/:id";
        Map<String, String> params = Map.of(
                "resource", "users",
                "id", "123",
                "query", "test value"
        );

        String result = URIBuilder.buildURIFromPattern(pattern, params);

        assertEquals("http://example.com/api/users/123?query=test+value", result);
    }

    @Test
    void testBuildURIFromPatternWithMultimap() {
        String pattern = "http://example.com/api";
        Multimap<String, String> params = ArrayListMultimap.create();
        params.put("filter", "name");
        params.put("filter", "age");

        String result = URIBuilder.buildURIFromPattern(pattern, params);

        assertEquals("http://example.com/api?filter=name&filter=age", result);
    }

    @Test
    void testBuildURIFromPatternNullParams() {
        String pattern = "http://example.com/api/{resource}";
        String result = URIBuilder.buildURIFromPattern(pattern, (Map<String, String>) null);
        assertEquals(pattern, result);
    }
}
