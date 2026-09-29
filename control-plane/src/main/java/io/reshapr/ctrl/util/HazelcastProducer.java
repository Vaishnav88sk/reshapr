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
package io.reshapr.ctrl.util;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;

/**
 * A CDI producer for HazelcastInstance. This allows us to inject HazelcastInstance
 * wherever needed in the application. The instance is created at application startup.
 * <p>
 * Note: {@code @Startup} must not be applied to {@code @Produces} methods (CDI spec violation).
 * The eager initialization is achieved by observing {@link StartupEvent} instead.
 * @author laurent
 */
@ApplicationScoped
public class HazelcastProducer {

   private HazelcastInstance hazelcastInstance;

   /** Eagerly create the Hazelcast instance at application startup. */
   void onStart(@Observes StartupEvent ev) {
      hazelcastInstance = Hazelcast.newHazelcastInstance();
   }

   @Produces
   @ApplicationScoped
   public HazelcastInstance createInstance() {
      // Hazelcast will load the hazelcast-kubernetes.yaml file.
      return hazelcastInstance;
   }
}
