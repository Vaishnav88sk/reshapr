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
package io.reshapr.benchmarks.e2e;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fixed-port variant of the {@code MinimalHttpBackend} used by the in-JVM JMH benchmarks, designed
 * for the end-to-end benchmark environment: the proxy under test (a separate Quarkus process)
 * targets it as the backend API of a benchmarked exposition.
 *
 * <p>Same design goals: never be the bottleneck. Raw loopback sockets, one virtual thread per
 * connection, HTTP keep-alive, a single pre-serialized canned response written in one syscall.
 * Requests are parsed just enough to honor {@code Content-Length} framing.</p>
 *
 * @author laurent
 */
public final class CannedHttpBackend implements AutoCloseable {

   private final ServerSocket serverSocket;
   private final byte[] cannedResponse;
   private final ExecutorService executor;
   private volatile boolean running = true;

   /**
    * Start the backend on the given loopback port, always answering with the given body.
    * @param port The fixed port to bind on the loopback interface.
    * @param responseBody The fixed JSON body returned for every request.
    * @throws IOException If the server socket cannot be bound.
    */
   public CannedHttpBackend(int port, byte[] responseBody) throws IOException {
      byte[] head = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
            + responseBody.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
      cannedResponse = new byte[head.length + responseBody.length];
      System.arraycopy(head, 0, cannedResponse, 0, head.length);
      System.arraycopy(responseBody, 0, cannedResponse, head.length, responseBody.length);

      serverSocket = new ServerSocket();
      serverSocket.setReuseAddress(true);
      serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 4096);

      executor = Executors.newVirtualThreadPerTaskExecutor();
      executor.submit(this::acceptLoop);
   }

   /** @return The port the backend is listening on. */
   public int port() {
      return serverSocket.getLocalPort();
   }

   private void acceptLoop() {
      while (running) {
         try {
            Socket socket = serverSocket.accept();
            socket.setTcpNoDelay(true);
            executor.submit(() -> handleConnection(socket));
         } catch (IOException e) {
            // Socket closed on shutdown, or transient accept failure: exit if no longer running.
            if (!running) {
               return;
            }
         }
      }
   }

   /** Serve requests on a persistent connection until the peer closes it. */
   private void handleConnection(Socket socket) {
      try (socket;
           InputStream rawIn = socket.getInputStream();
           OutputStream out = socket.getOutputStream()) {
         BufferedInputStream in = new BufferedInputStream(rawIn, 16 * 1024);
         while (running) {
            long contentLength = 0;
            boolean sawRequestLine = false;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
               sawRequestLine = true;
               if (line.regionMatches(true, 0, "content-length", 0, 14)) {
                  contentLength = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
               }
            }
            if (line == null || !sawRequestLine) {
               // EOF or empty read: the client closed the connection.
               return;
            }
            // Consume the request body then reply with the canned response.
            in.skipNBytes(contentLength);
            out.write(cannedResponse);
            out.flush();
         }
      } catch (IOException e) {
         // Connection reset by peer or shutdown in progress: nothing to do.
      }
   }

   /** Read a CRLF-terminated ASCII line; returns {@code null} on EOF before any byte. */
   private static String readLine(InputStream in) throws IOException {
      StringBuilder sb = new StringBuilder(64);
      int c;
      while ((c = in.read()) != -1) {
         if (c == '\n') {
            int len = sb.length();
            if (len > 0 && sb.charAt(len - 1) == '\r') {
               sb.setLength(len - 1);
            }
            return sb.toString();
         }
         sb.append((char) c);
      }
      return sb.isEmpty() ? null : sb.toString();
   }

   @Override
   public void close() throws IOException {
      running = false;
      serverSocket.close();
      executor.shutdownNow();
   }
}
