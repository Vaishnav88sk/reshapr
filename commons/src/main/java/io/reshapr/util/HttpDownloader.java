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

import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * A modern HTTP downloader utility using Java 21's {@link HttpClient}.
 * Supports diverse security authentication mechanisms (Basic, Bearer, custom token headers)
 * and SSL/TLS configurations (custom CA certificates, disabled SSL validation).
 *
 * @author laurent
 */
public final class HttpDownloader {

   private static final Logger log = Logger.getLogger(HttpDownloader.class);

   /** Constant representing the header line in a custom CA Cert in PEM format. */
   private static final String BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----";
   /** Constant representing the footer line in a custom CA Cert in PEM format. */
   private static final String END_CERTIFICATE = "-----END CERTIFICATE-----";

   private HttpDownloader() {
      // Utility class — no instantiation.
   }

   /**
    * Retrieve the {@code ETag} header value from a remote URL.<p>
    * Depending on the secret content, the HTTP connection is prepared for handling
    * target service authentication (Basic / Bearer / custom header) and remote SSL
    * connections (custom CA certificate or disabled SSL validation).
    *
    * @param remoteUrl            the remote URL to check
    * @param secret               the optional secret associated with this URL (may be {@code null})
    * @param disableSSLValidation whether to disable SSL validation
    * @return the ETag value if present, or {@code null}
    * @throws IOException if anything goes wrong during preparation or execution
    */
   public static String getURLEtag(String remoteUrl, HttpSecret secret, boolean disableSSLValidation) throws IOException {
      try {
         HttpClient client = buildHttpClient(remoteUrl, secret, disableSSLValidation);
         HttpRequest request = buildHttpRequest(remoteUrl, secret);

         HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

         // Try both common casings of the ETag header.
         return response.headers().firstValue("Etag")
               .or(() -> response.headers().firstValue("ETag"))
               .map(etag -> {
                  log.debugf("Found an Etag for %s: %s", remoteUrl, etag);
                  return etag;
               })
               .orElseGet(() -> {
                  log.debugf("No Etag found for %s!", remoteUrl);
                  return null;
               });
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new IOException("Interrupted while retrieving ETag for " + remoteUrl, e);
      } catch (IOException e) {
         throw e;
      } catch (Exception e) {
         log.error("Caught an exception while retrieving Etag for {}", remoteUrl, e);
         throw new IOException("Failed to retrieve ETag for " + remoteUrl, e);
      }
   }

   /**
    * Download the content of a remote URL to a local temporary file.
    * <p>
    * Depending on the secret content, the HTTP connection is prepared for handling
    * target service authentication and SSL configuration.
    *
    * @param remoteUrl            the remote URL to download
    * @param secret               the optional secret associated with this URL (may be {@code null})
    * @param disableSSLValidation whether to disable SSL validation
    * @return a temporary {@link File} containing the downloaded content
    * @throws IOException if anything goes wrong during preparation or execution
    */
   public static File handleHTTPDownloadToFile(String remoteUrl, HttpSecret secret, boolean disableSSLValidation)
         throws IOException {
      return handleHTTPDownloadToFileAndHeaders(remoteUrl, secret, disableSSLValidation).localFile();
   }

   /**
    * Download the content of a remote URL to a local temporary file, also returning the HTTP response headers.
    * <p>
    * Depending on the secret content, the HTTP connection is prepared for handling
    * target service authentication and SSL configuration.
    *
    * @param remoteUrl            the remote URL to download
    * @param secret               the optional secret associated with this URL (may be {@code null})
    * @param disableSSLValidation whether to disable SSL validation
    * @return a {@link FileAndHeaders} record containing the temporary file and response headers
    * @throws IOException if anything goes wrong during preparation or execution
    */
   public static FileAndHeaders handleHTTPDownloadToFileAndHeaders(String remoteUrl, HttpSecret secret,
         boolean disableSSLValidation) throws IOException {
      try {
         HttpClient client = buildHttpClient(remoteUrl, secret, disableSSLValidation);
         HttpRequest request = buildHttpRequest(remoteUrl, secret);

         Path tempFile = Files.createTempFile(
               "reshapr-" + System.currentTimeMillis(),
               ".download",
               PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));

         HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));

         Map<String, List<String>> responseHeaders = response.headers().map();
         return new FileAndHeaders(tempFile.toFile(), responseHeaders);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new IOException("Interrupted while downloading " + remoteUrl, e);
      } catch (IOException e) {
         throw e;
      } catch (Exception e) {
         throw new IOException("Failed to download " + remoteUrl, e);
      }
   }

   /**
    * Build an {@link HttpClient} with the appropriate SSL context for the given URL and secret.
    */
   private static HttpClient buildHttpClient(String remoteUrl, HttpSecret secret, boolean disableSSLValidation)
         throws Exception {
      HttpClient.Builder builder = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL);

      URI uri = URI.create(remoteUrl);
      if ("https".equals(uri.getScheme())) {
         if (disableSSLValidation) {
            log.debugf("SSL Validation is disabled for %s, installing accept-everything TrustManager", remoteUrl);
            builder.sslContext(createAcceptEverythingSSLContext());
         } else if (secret != null && secret.certPem() != null && !secret.certPem().isBlank()) {
            log.debugf("Secret for %s contains a CA Cert, installing certificate into TrustManager", remoteUrl);
            builder.sslContext(createCustomCaCertSSLContext(secret.certPem()));
         }
      }

      return builder.build();
   }

   /**
    * Build an {@link HttpRequest} with the appropriate authentication headers from the secret.
    */
   private static HttpRequest buildHttpRequest(String remoteUrl, HttpSecret secret) {
      HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(remoteUrl))
            .GET();

      if (secret != null) {
         // Basic authentication.
         if (secret.username() != null && secret.password() != null) {
            log.debugf("Secret for %s contains username/password, assuming Authorization Basic", remoteUrl);
            String encoded = Base64.getEncoder()
                  .encodeToString((secret.username() + ":" + secret.password()).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
         }

         // Token authentication.
         if (secret.token() != null) {
            if (secret.tokenHeader() != null && !secret.tokenHeader().isBlank()) {
               log.debugf("Secret for %s contains token and token header, adding them as request header", remoteUrl);
               builder.header(secret.tokenHeader().strip(), secret.token());
            } else {
               log.debugf("Secret for %s contains token only, assuming Authorization Bearer", remoteUrl);
               builder.header("Authorization", "Bearer " + secret.token());
            }
         }
      }

      return builder.build();
   }

   /**
    * Create an {@link SSLContext} that trusts all certificates and all hostnames.
    */
   private static SSLContext createAcceptEverythingSSLContext() throws Exception {
      TrustManager[] trustAllCerts = { new X509TrustManager() {
         @Override
         public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
         }

         @Override
         public void checkClientTrusted(X509Certificate[] certs, String authType) {
            // Accept everything — no validation.
         }

         @Override
         public void checkServerTrusted(X509Certificate[] certs, String authType) {
            // Accept everything — no validation.
         }
      } };

      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
      return sslContext;
   }

   /**
    * Create an {@link SSLContext} that trusts only the given PEM-encoded CA certificate.
    */
   private static SSLContext createCustomCaCertSSLContext(String caCertPem) throws Exception {
      // Strip PEM headers and decode base64 content.
      String strippedPem = caCertPem.replace(BEGIN_CERTIFICATE, "").replace(END_CERTIFICATE, "").strip();
      byte[] decoded = Base64.getDecoder().decode(strippedPem);

      // Generate an X.509 certificate from the decoded bytes.
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      X509Certificate caCert;
      try (InputStream is = new ByteArrayInputStream(decoded)) {
         caCert = (X509Certificate) cf.generateCertificate(is);
      }

      // Load the certificate into a new KeyStore.
      KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
      ks.load(null, null);
      ks.setCertificateEntry("caCert", caCert);

      // Initialize a TrustManagerFactory with the custom KeyStore.
      TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(ks);

      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, tmf.getTrustManagers(), null);
      return sslContext;
   }

   /**
    * A record wrapping a downloaded local file together with the HTTP response headers
    * received during the download.
    *
    * @param localFile       the temporary file containing the downloaded content
    * @param responseHeaders the HTTP response headers
    */
   public record FileAndHeaders(File localFile, Map<String, List<String>> responseHeaders) {
   }
}



