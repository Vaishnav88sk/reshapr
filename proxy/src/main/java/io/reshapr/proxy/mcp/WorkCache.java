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
package io.reshapr.proxy.mcp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.function.Supplier;

/**
 * A configurable basic working cache for MCP operations.
 * @author laurent
 */
@ApplicationScoped
public class WorkCache {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final Cache<String, Object> cache;

   /**
    * Build a working cache from a LRU cache with maximum capacity.
    * @param cacheSize The maximum capacity/size of the working cache.
    */
   public WorkCache(@ConfigProperty(name = "reshapr.gateway.mcp.cache.size") int cacheSize){
      this.cache = Caffeine.newBuilder()
            .maximumSize(cacheSize)
            .build();
   }

   public void set(String major, String minor, Object value) {
      String key = major + "_" + minor;
      cache.put(key, value);
   }

   public Object get(String major, String minor) {
      String key = major + "_" + minor;
      logger.tracef("Looking for key '%s", key);
      return cache.getIfPresent(key);
   }

   /**
    * Atomically get the cached value for the given key, or compute and cache it exactly once.
    * <p>Unlike a {@link #get(String, String)}/{@link #set(String, String, Object)} pair, this method
    * guarantees the {@code loader} runs at most once per key even under concurrent access: competing
    * threads for the same missing key block and share the single computed value. This is essential for
    * expensive one-time computations (e.g. parsing a large GraphQL schema) to avoid a thundering herd
    * of concurrent recomputations that can exhaust the heap.
    * @param major The major part of the cache key.
    * @param minor The minor part of the cache key.
    * @param loader The supplier used to compute the value on a cache miss.
    * @return The cached or newly computed value.
    */
   public Object computeIfAbsent(String major, String minor, Supplier<Object> loader) {
      String key = major + "_" + minor;
      return cache.get(key, k -> loader.get());
   }

   public long size() {
      return cache.estimatedSize();
   }

   public boolean isEmpty() {
      return cache.estimatedSize() == 0;
   }

   public void clear() {
      cache.cleanUp();
   }

   public void invalidateMajor(String major) {
      final String prefix = major + "_";
      List<String> keysToRemove = cache.asMap().keySet().stream()
            .filter(key -> key.startsWith(prefix))
            .toList();
      cache.invalidateAll(keysToRemove);
   }
}
