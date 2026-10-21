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
package io.reshapr.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HttpDownloader} using a local JDK {@link HttpServer}.
 * @author laurent
 */
class HttpDownloaderTest {

   private static final String CONTENT = "openapi: 3.0.0\n";

   private static HttpServer server;
   private static String baseUrl;
   private static final Map<String, String> lastAuthHeaders = new ConcurrentHashMap<>();

   @BeforeAll
   static void startServer() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/spec", exchange -> {
         String auth = exchange.getRequestHeaders().getFirst("Authorization");
         lastAuthHeaders.put("Authorization", auth == null ? "" : auth);
         String custom = exchange.getRequestHeaders().getFirst("X-Api-Key");
         lastAuthHeaders.put("X-Api-Key", custom == null ? "" : custom);

         byte[] body = CONTENT.getBytes(StandardCharsets.UTF_8);
         exchange.getResponseHeaders().add("ETag", "\"abc123\"");
         exchange.sendResponseHeaders(200, exchange.getRequestMethod().equals("HEAD") ? -1 : body.length);
         if (!exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseBody().write(body);
         }
         exchange.close();
      });
      server.createContext("/noetag", exchange -> {
         exchange.sendResponseHeaders(200, -1);
         exchange.close();
      });
      server.start();
      baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
   }

   @AfterAll
   static void stopServer() {
      server.stop(0);
   }

   @Test
   void downloadsContentToLocalFile() throws IOException {
      File file = HttpDownloader.handleHTTPDownloadToFile(baseUrl + "/spec", null, false);
      try {
         assertTrue(file.exists());
         assertEquals(CONTENT, Files.readString(file.toPath()));
      } finally {
         file.delete();
      }
   }

   @Test
   void returnsResponseHeaders() throws IOException {
      HttpDownloader.FileAndHeaders result = HttpDownloader.handleHTTPDownloadToFileAndHeaders(
            baseUrl + "/spec", null, false);
      try {
         assertEquals(CONTENT, Files.readString(result.localFile().toPath()));
         assertTrue(result.responseHeaders().keySet().stream().anyMatch("etag"::equalsIgnoreCase));
      } finally {
         result.localFile().delete();
      }
   }

   @Test
   void downloadedFilesAreInPrivateDirectory() throws IOException {
      File file = HttpDownloader.handleHTTPDownloadToFile(baseUrl + "/spec", null, false);
      try {
         assertTrue(file.getParentFile().getName().startsWith("reshapr-downloads-"));
         assertTrue(file.getName().startsWith("reshapr-"));
         assertTrue(file.getName().endsWith(".download"));

         if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> dirPerms = Files.getPosixFilePermissions(file.getParentFile().toPath());
            assertEquals(PosixFilePermissions.fromString("rwx------"), dirPerms);
            Set<PosixFilePermission> filePerms = Files.getPosixFilePermissions(file.toPath());
            assertEquals(PosixFilePermissions.fromString("rw-------"), filePerms);
         }
      } finally {
         file.delete();
      }
   }

   @Test
   void eachDownloadGetsDistinctFile() throws IOException {
      File first = HttpDownloader.handleHTTPDownloadToFile(baseUrl + "/spec", null, false);
      File second = HttpDownloader.handleHTTPDownloadToFile(baseUrl + "/spec", null, false);
      try {
         assertNotEquals(first.getAbsolutePath(), second.getAbsolutePath());
      } finally {
         first.delete();
         second.delete();
      }
   }

   @Test
   void sendsBasicAuthentication() throws IOException {
      HttpSecret secret = new HttpSecret("user", "pass", null, null, null);
      HttpDownloader.handleHTTPDownloadToFile(baseUrl + "/spec", secret, false).delete();

      String expected = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
      assertEquals(expected, lastAuthHeaders.get("Authorization"));
   }

   @Test
   void sendsBearerTokenByDefault() throws IOException {
      HttpSecret secret = new HttpSecret(null, null, "tok", null, null);
      HttpDownloader.handleHTTPDownloadToFile(baseUrl + "/spec", secret, false).delete();

      assertEquals("Bearer tok", lastAuthHeaders.get("Authorization"));
   }

   @Test
   void sendsTokenInCustomHeader() throws IOException {
      HttpSecret secret = new HttpSecret(null, null, "tok", "X-Api-Key", null);
      HttpDownloader.handleHTTPDownloadToFile(baseUrl + "/spec", secret, false).delete();

      assertEquals("tok", lastAuthHeaders.get("X-Api-Key"));
      assertEquals("", lastAuthHeaders.get("Authorization"));
   }

   @Test
   void retrievesEtag() throws IOException {
      assertEquals("\"abc123\"", HttpDownloader.getURLEtag(baseUrl + "/spec", null, false));
   }

   @Test
   void returnsNullWhenNoEtag() throws IOException {
      assertNull(HttpDownloader.getURLEtag(baseUrl + "/noetag", null, false));
   }

   @Test
   void failsOnUnreachableUrl() {
      assertThrows(IOException.class,
            () -> HttpDownloader.handleHTTPDownloadToFile("http://127.0.0.1:1/unreachable", null, false));
   }
}
