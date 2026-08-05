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

import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.ServiceEntry;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * HTTP-level tests for the protocol-version-driven routing of the MCP endpoint: the {@link McpController}
 * resolves the negotiated protocol version (from the legacy {@code MCP-Session-Id} session or from the
 * stateless {@code MCP-Protocol-Version} header) and picks the matching {@link McpProtocolDialect}, which in
 * turn shapes the wire result. These tests assert that the <b>same</b> {@code prompts/list} /
 * {@code resources/list} call yields the modern shape (mandatory {@code resultType} discriminator plus the
 * {@code ttlMs} / {@code cacheScope} client-cache hints) for protocol {@code >= 2026-07-28}, and the legacy
 * "nude" shape for older protocols.
 * @author laurent
 */
@QuarkusTest
class McpProtocolVersionRoutingTest {

   /** Stable exposition id seeded for this test and used in the {@code /mcp/{expositionId}} endpoint. */
   private static final String EXPOSITION_ID = "proto-routing-exp";

   /** A legacy (session-based) protocol version, strictly before the stateless cut-over. */
   private static final String LEGACY_VERSION = "2025-06-18";

   @Inject
   GatewayRegistry registry;

   /** Seed (idempotently) a minimal, unsecured REST exposition so {@code /mcp/{id}} is reachable. */
   @BeforeEach
   void seedExposition() {
      ServiceEntry service = new ServiceEntry("proto-routing-svc", "acme", "Routing API", "1.0.0", "REST", List.of());
      // apiKey == null && oauth2Configuration == null -> SecureEndpointFilter treats the endpoint as public.
      ConfigurationEntry configuration = new ConfigurationEntry("proto-routing-cfg", "cfg",
            "http://backend", null, List.of(), List.of(), null, null, null);
      registry.addExposition(new ExpositionEntry(EXPOSITION_ID, "routing-exp", service, configuration, null, List.of()));
   }

   @Test
   @DisplayName("stateless protocol (>= 2026-07-28) selects the modern prompts/list shape")
   void testStatelessVersionYieldsModernPromptsListShape() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body(jsonRpc("prompts/list"))
      .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
      .then()
            .statusCode(200)
            .body("error", nullValue())
            // Modern discriminator + client-cache hints are present.
            .body("result.resultType", equalTo("complete"))
            .body("result.ttlMs", notNullValue())
            .body("result.cacheScope", equalTo("public"))
            .body("result.prompts", hasSize(0));
   }

   @Test
   @DisplayName("legacy protocol (session-bound) selects the legacy prompts/list shape")
   void testLegacySessionYieldsLegacyPromptsListShape() {
      // 1. initialize with a legacy protocol version -> the server creates a session and advertises its id.
      String sessionId = given()
            .contentType(ContentType.JSON)
            .body(initialize(LEGACY_VERSION))
      .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
      .then()
            .statusCode(200)
            .header(McpSchema.HEADER_SESSION_ID, notNullValue())
            .extract().header(McpSchema.HEADER_SESSION_ID);

      // 2. prompts/list bound to that session -> the version is read back from the session (legacy dialect).
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_SESSION_ID, sessionId)
            .body(jsonRpc("prompts/list"))
      .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
      .then()
            .statusCode(200)
            .body("error", nullValue())
            // Modern-only fields must be absent in the legacy wire shape.
            .body("result.resultType", nullValue())
            .body("result.ttlMs", nullValue())
            .body("result.cacheScope", nullValue())
            .body("result.prompts", hasSize(0));
   }

   @Test
   @DisplayName("stateless protocol (>= 2026-07-28) also selects the modern resources/list shape")
   void testStatelessVersionYieldsModernResourcesListShape() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body(jsonRpc("resources/list"))
      .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
      .then()
            .statusCode(200)
            .body("error", nullValue())
            .body("result.resultType", equalTo("complete"))
            .body("result.ttlMs", notNullValue())
            .body("result.cacheScope", equalTo("public"))
            .body("result.resources", hasSize(0));
   }

   @Test
   @DisplayName("a non-handshake call without session and without the stateless version is rejected")
   void testMissingSessionWithLegacyVersionIsRejected() {
      given()
            .contentType(ContentType.JSON)
            // Legacy version but no session id -> the handshake was skipped: must be rejected.
            .header(McpSchema.HEADER_PROTOCOL_VERSION, LEGACY_VERSION)
            .body(jsonRpc("prompts/list"))
      .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
      .then()
            .statusCode(200)
            .body("result", nullValue())
            .body("error.code", equalTo(McpSchema.ErrorCodes.INVALID_REQUEST));
   }

   @Test
   @DisplayName("an unknown exposition returns 404 regardless of protocol version")
   void testUnknownExpositionReturnsNotFound() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .body(jsonRpc("prompts/list"))
      .when()
            .post("/mcp/{expositionId}", "does-not-exist")
      .then()
            .statusCode(404);
   }

   @Test
   @DisplayName("modern Mcp-Method header disagreeing with the body method is rejected (400 + -32020)")
   void testModernMethodHeaderMismatchIsRejected() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            // Mcp-Method claims tools/list while the body carries server/discover.
            .header(McpSchema.HEADER_METHOD, "tools/list")
            .body(modernJsonRpc("server/discover", ""))
      .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
      .then()
            .statusCode(400)
            .body("result", nullValue())
            .body("error.code", equalTo(McpSchema.ErrorCodes.HEADER_MISMATCH));
   }

   @Test
   @DisplayName("modern Mcp-Name header disagreeing with the body target is rejected (400 + -32020)")
   void testModernNameHeaderMismatchIsRejected() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .header(McpSchema.HEADER_METHOD, "resources/read")
            // Mcp-Name disagrees with params.uri: rejected pre-dispatch, before any resource read.
            .header(McpSchema.HEADER_NAME, "file://wrong")
            .body(modernJsonRpc("resources/read", "\"uri\":\"file://real\","))
      .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
      .then()
            .statusCode(400)
            .body("result", nullValue())
            .body("error.code", equalTo(McpSchema.ErrorCodes.HEADER_MISMATCH));
   }

   @Test
   @DisplayName("modern MCP-Protocol-Version header disagreeing with the envelope is rejected (400 + -32020)")
   void testModernProtocolVersionHeaderMismatchIsRejected() {
      given()
            .contentType(ContentType.JSON)
            // Header names a different revision than the envelope's 2026-07-28.
            .header(McpSchema.HEADER_PROTOCOL_VERSION, "2025-11-25")
            .header(McpSchema.HEADER_METHOD, "server/discover")
            .body(modernJsonRpc("server/discover", ""))
      .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
      .then()
            .statusCode(400)
            .body("result", nullValue())
            .body("error.code", equalTo(McpSchema.ErrorCodes.HEADER_MISMATCH));
   }

   @Test
   @DisplayName("modern request whose mirror headers all agree with the body is accepted")
   void testModernMatchingHeadersAreAccepted() {
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .header(McpSchema.HEADER_METHOD, "server/discover")
            .body(modernJsonRpc("server/discover", ""))
      .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
      .then()
            .statusCode(200)
            .body("error", nullValue())
            .body("result.resultType", equalTo("complete"));
   }

   @Test
   @DisplayName("methods removed by the 2026 revision answer 404 + -32601 under a modern envelope")
   void testRemovedMethodsRejectedInModernMode() {
      for (String method : List.of("initialize", "ping", "logging/setLevel",
            "resources/subscribe", "resources/unsubscribe")) {
         given()
               .contentType(ContentType.JSON)
               .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
               .header(McpSchema.HEADER_METHOD, method)
               .body(modernJsonRpc(method, ""))
         .when()
               .post("/mcp/{expositionId}", EXPOSITION_ID)
         .then()
               .statusCode(404)
               .body("result", nullValue())
               .body("error.code", equalTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND));
      }
   }

   @Test
   @DisplayName("a method surviving into 2026 but unimplemented stays in-band -32601 on HTTP 200")
   void testSurvivingUnimplementedMethodStaysInBand() {
      // completion/complete is NOT removed by the revision: an unimplemented survivor keeps the ordinary
      // in-band error on HTTP 200 rather than the 404 reserved for removed methods.
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .header(McpSchema.HEADER_METHOD, "completion/complete")
            .body(modernJsonRpc("completion/complete", ""))
      .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
      .then()
            .statusCode(200)
            .body("result", nullValue())
            .body("error.code", equalTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND));
   }

   @Test
   @DisplayName("modern request declaring an unsupported protocol version is rejected (400 + -32022)")
   void testModernUnsupportedVersionIsRejected() {
      String unsupported = "1999-01-01";
      given()
            .contentType(ContentType.JSON)
            // Header and envelope agree on the unsupported version, so the -32020 header rung stays silent
            // and the request reaches version negotiation.
            .header(McpSchema.HEADER_PROTOCOL_VERSION, unsupported)
            .header(McpSchema.HEADER_METHOD, "server/discover")
            .body("""
                  {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"%s":"%s"}}}"""
                  .formatted(McpSchema.META_KEY_PROTOCOL_VERSION, unsupported))
      .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
      .then()
            .statusCode(400)
            .body("result", nullValue())
            .body("error.code", equalTo(McpSchema.ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION))
            .body("error.data.supported", hasSize(McpSchema.SUPPORTED_PROTOCOL_VERSIONS.size()));
   }

   @Test
   @DisplayName("modern resources/read of an unknown URI returns in-band invalid params (200 + -32602)")
   void testModernResourceNotFoundYieldsInvalidParams() {
      String missingUri = "file://does-not-exist";
      given()
            .contentType(ContentType.JSON)
            .header(McpSchema.HEADER_PROTOCOL_VERSION, McpSchema.PROTOCOL_VERSION_STATELESS)
            .header(McpSchema.HEADER_METHOD, "resources/read")
            .header(McpSchema.HEADER_NAME, missingUri)
            .body(modernJsonRpc("resources/read", "\"uri\":\"" + missingUri + "\","))
      .when()
            .post("/mcp/{expositionId}", EXPOSITION_ID)
      .then()
            .statusCode(200)
            .body("result", nullValue())
            .body("error.code", equalTo(McpSchema.ErrorCodes.INVALID_PARAMS));
   }

   /**
    * Build a modern (stateless) JSON-RPC request whose {@code params} carry the SEP-2243 envelope in
    * {@code _meta}. {@code extraParams} is raw JSON prepended inside {@code params} (e.g. {@code "uri":"x",}).
    */
   private static String modernJsonRpc(String method, String extraParams) {
      return """
            {"jsonrpc":"2.0","id":1,"method":"%s","params":{%s"_meta":{"%s":"%s"}}}"""
            .formatted(method, extraParams, McpSchema.META_KEY_PROTOCOL_VERSION,
                  McpSchema.PROTOCOL_VERSION_STATELESS);
   }

   /** Build a minimal JSON-RPC request envelope for a parameter-less method. */
   private static String jsonRpc(String method) {
      return """
            {"jsonrpc":"2.0","id":1,"method":"%s","params":{}}""".formatted(method);
   }

   /** Build a minimal {@code initialize} JSON-RPC request negotiating the given protocol version. */
   private static String initialize(String protocolVersion) {
      return """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{\
            "protocolVersion":"%s",\
            "capabilities":{},\
            "clientInfo":{"name":"reshapr-test-client","version":"1.0.0"}}}""".formatted(protocolVersion);
   }
}

