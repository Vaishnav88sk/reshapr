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
package io.reshapr.proxy.context;

/**
 * MethodHandlingContext is a utility class that provides a scoped storage for MethodHandlingInfo.
 * @author laurent
 */
public class MethodHandlingContext {

   public static final ScopedValue<MethodHandlingInfo> METHOD_HANDLING_INFO = ScopedValue.newInstance();

   private MethodHandlingContext() {
      // Utility class.
   }

   public static String getRemoteAddress() {
      return METHOD_HANDLING_INFO.get().remoteAddress();
   }

   public static SessionInfo getSessionInfo() {
      return METHOD_HANDLING_INFO.get().mcpSessionInfo();
   }

   public static String getUserId() {
      return METHOD_HANDLING_INFO.get().userId();
   }

   public static String getIssuer() {
      return METHOD_HANDLING_INFO.get().issuer();
   }

   public static String getOrganizationId() {
      return METHOD_HANDLING_INFO.get().organizationId();
   }

   /**
    * The stable, cross-IdP user key ({@code iss + sub}) used to bind user-elicited secrets in
    * stateless mode. Returns {@code null} when either the issuer or the subject is missing (i.e. the
    * exposition is not OAuth2-protected), in which case no secret can be safely bound to an identity.
    */
   public static String getUserKey() {
      MethodHandlingInfo info = METHOD_HANDLING_INFO.get();
      if (info.issuer() == null || info.userId() == null) {
         return null;
      }
      return info.issuer() + "|" + info.userId();
   }

   /** Whether the current call is handled in stateless mode (no MCP session bound). */
   public static boolean isStateless() {
      return METHOD_HANDLING_INFO.get().mcpSessionInfo() == null;
   }
}
