package org.yanalice.backend;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Loopback-only static responder. No outbound client, DNS, firmware or file routes. */
public final class LocalBackend {
    private final byte[] sync;
    private final List<ServerSocket> sockets = new ArrayList<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(16));
    private volatile boolean running = true;

    private LocalBackend(File directory) throws Exception {
        sync = Files.readAllBytes(new File(directory, "sync.json").toPath());
        if (sync.length < 2 || sync.length > 1024 * 1024) throw new IOException("Invalid sync fixture size");
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Private config directory and duration required");
        int seconds = Integer.parseInt(args[1]);
        if (seconds < 5 || seconds > 1800) throw new IllegalArgumentException("Bounded duration required");
        File directory = new File(args[0]);
        LocalBackend server = new LocalBackend(directory);
        try {
            InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            ServerSocket http = new ServerSocket(); http.bind(new InetSocketAddress(loopback, 18080), 8);
            server.sockets.add(http);
            KeyStore store = KeyStore.getInstance("PKCS12");
            char[] password = new String(Files.readAllBytes(new File(directory, "password").toPath()), StandardCharsets.UTF_8).trim().toCharArray();
            try (InputStream input = new FileInputStream(new File(directory, "server.p12"))) { store.load(input, password); }
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(store, password);
            Arrays.fill(password, '\0');
            SSLContext tls = SSLContext.getInstance("TLS"); tls.init(keys.getKeyManagers(), null, null);
            SSLServerSocket https = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket();
            https.setEnabledProtocols(new String[]{"TLSv1.2"});
            https.bind(new InetSocketAddress(loopback, 18443), 8); server.sockets.add(https);
            for (ServerSocket socket : server.sockets) new Thread(() -> server.accept(socket), "local-backend-accept").start();
            System.out.println("LOCAL_BACKEND_READY http=127.0.0.1:18080 https=127.0.0.1:18443 upstream=false firmware=false");
            Thread.sleep(seconds * 1000L);
        } finally {
            server.running = false;
            for (ServerSocket socket : server.sockets) socket.close();
            server.workers.shutdownNow();
            server.workers.awaitTermination(4, TimeUnit.SECONDS);
            System.out.println("LOCAL_BACKEND_END");
        }
    }
    private void accept(ServerSocket server) {
        while (running) try {
            Socket socket = server.accept(); socket.setSoTimeout(3000);
            try { workers.execute(() -> handle(socket)); }
            catch (RejectedExecutionException full) { socket.close(); }
        } catch (IOException error) { if (running) System.out.println("LOCAL_BACKEND_ACCEPT_ERROR"); }
    }
    private static String line(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream value = new ByteArrayOutputStream(); int c;
        while ((c = in.read()) != -1) {
            if (--budget[0] < 0 || value.size() >= 4096) throw new IOException("Header limit");
            if (c == '\n') return value.toString("US-ASCII").replaceFirst("\r$", "");
            value.write(c);
        }
        throw new EOFException();
    }
    private void handle(Socket connection) {
        try (Socket socket = connection) {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            int[] budget = {16384}; String[] request = line(input, budget).split(" ");
            if (request.length != 3 || !request[2].matches("HTTP/1\\.[01]")) throw new IOException("Bad request line");
            Map<String,String> headers = new HashMap<>();
            for (String header; !(header = line(input, budget)).isEmpty();) {
                int colon = header.indexOf(':'); if (colon < 1) throw new IOException("Bad header");
                String key = header.substring(0, colon).toLowerCase(Locale.ROOT);
                if (headers.put(key, header.substring(colon + 1).trim()) != null) throw new IOException("Duplicate header");
            }
            long length = Long.parseLong(headers.getOrDefault("content-length", "0"));
            if (length < 0 || length > 65536 || headers.containsKey("transfer-encoding")) {
                respond(socket, 400, "{\"status\":\"invalid_body\"}".getBytes(StandardCharsets.UTF_8), false); return;
            }
            // Consume and discard bounded input; credentials are never logged or retained.
            byte[] discard = new byte[4096];
            while (length > 0) { int n = input.read(discard, 0, (int)Math.min(length, discard.length)); if (n < 0) throw new EOFException(); length -= n; }
            URI uri = new URI(request[1]); String path = uri.getPath().toLowerCase(Locale.ROOT);
            String method = request[0]; boolean head = method.equals("HEAD");
            int code = 200; String label = "other", data = "{\"status\":\"diagnostic_only\",\"upstream\":false}";
            byte[] body = null;
            boolean platform = true;
            if (uri.getRawQuery() != null) for (String field : uri.getRawQuery().split("&")) {
                String[] pair = field.split("=", 2);
                if (URLDecoder.decode(pair[0], "UTF-8").equals("platform"))
                    platform &= pair.length == 2 && URLDecoder.decode(pair[1], "UTF-8").equals("yandexstation_2");
            }
            if (!Arrays.asList("GET", "POST", "PUT", "HEAD").contains(method)) code = 405;
            else if (path.equals("/__yanalice/health")) { label = "health"; data = "{\"service\":\"yanalice-station-local\",\"upstream\":false,\"firmware\":false}"; }
            else if (path.contains("check_updates") || path.contains("allow_update")) { label = "no_update"; data = "{\"hasUpdate\":false}"; }
            else if (platform && (path.equals("/get_sync_info") || path.equals("/quasar/get_sync_info"))) { label = "sync"; body = sync; }
            else if (platform && path.equals("/register")) { label = "register"; data = "{\"message\":\"allvalid\",\"status\":\"ok\"}"; }
            else if (platform && (path.equals("/update_device_state") || path.equals("/set_device_capability"))) { label = "state"; data = "{\"status\":\"ok\"}"; }
            else if (path.equals("/generate_204") || path.equals("/gen_204")) { label = "connectivity"; code = 204; data = ""; }
            else code = 503;
            respond(socket, code, body == null ? data.getBytes(StandardCharsets.UTF_8) : body, head);
            System.out.println("LOCAL_BACKEND_REQUEST endpoint=" + label + " code=" + code);
        } catch (Exception error) { System.out.println("LOCAL_BACKEND_REQUEST_ERROR type=" + error.getClass().getSimpleName()); }
    }
    private static void respond(Socket socket, int code, byte[] body, boolean head) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + code + " Local\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        if (!head) out.write(body); out.flush();
    }
}
