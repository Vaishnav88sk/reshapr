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
package io.reshapr.proxy;

import io.reshapr.proxy.mcp.McpSchema;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.jboss.logging.Logger;

import java.util.Arrays;
/**
 * A feature for providing runtime reflection registration hints to Graal VM.
 * @author laurent
 */
public class NativeConfigurationFeature implements Feature {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   @Override
   public void beforeAnalysis(BeforeAnalysisAccess access) {
      // Register all inner classes in io.reshapr.proxy.mcp.McpSchema class.
      Arrays.stream(McpSchema.class.getClasses()).forEach(clazz -> {
         registerClassForReflection(clazz);

         // Then recurse on subclasses as well...
         Arrays.stream(clazz.getClasses()).forEach(aClazz -> {
            registerClassForReflection(aClazz);
         });
      });
   }

   /**
    * Register all class elements (constructors, methods, fields) for reflection in GraalVM.
    * @param clazz The class to register.
    */
   private void registerClassForReflection(Class clazz) {
      logger.debugf("Registering %s for reflection", clazz.getCanonicalName());
      RuntimeReflection.register(clazz);
      RuntimeReflection.register(clazz.getDeclaredConstructors());
      RuntimeReflection.register(clazz.getDeclaredMethods());
      RuntimeReflection.register(clazz.getDeclaredFields());
      RuntimeReflection.register(clazz.getConstructors());
      RuntimeReflection.register(clazz.getMethods());
      RuntimeReflection.register(clazz.getFields());
   }
}
