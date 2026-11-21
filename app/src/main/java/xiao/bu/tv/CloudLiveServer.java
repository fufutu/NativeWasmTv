package xiao.bu.tv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Loopback HTTP server that serves the generated playlist and one live m3u8 per
 * channel. The player never talks to the cloud API directly.
 */
final class CloudLiveServer {
    private static final String TAG = "CloudLive";
    private static final String CONTENT_M3U8 = "application/vnd.apple.mpegurl";
    private static final String CONTENT_M3U = "application/x-mpegurl";

    private final CloudLiveService service;
    private final ExecutorService workers = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger index = new AtomicInteger();

        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "cloud-live-http-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private int port;

    CloudLiveServer(CloudLiveService service) {
        this.service = service;
    }

    int port() {
        return port;
    }

    synchronized int start(int preferredPort) throws IOException {
        if (running) {
            return port;
        }
        IOException lastError = null;
        for (int candidate = preferredPort; candidate < preferredPort + 10; candidate++) {
            ServerSocket socket = null;
            try {
                socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), candidate));
                serverSocket = socket;
                port = candidate;
                running = true;
                acceptThread = new Thread(new Runnable() {
                    @Override public void run() {
                        acceptLoop();
                    }
                }, "cloud-live-accept");
                acceptThread.setDaemon(true);
                acceptThread.start();
                AppLog.i(TAG, "Pseudo-live server listening on 127.0.0.1:" + port);
                return port;
            } catch (IOException error) {
                lastError = error;
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        throw lastError == null ? new IOException("无法绑定本地端口") : lastError;
    }

    synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                socket.setSoTimeout(10000);
                workers.execute(new Runnable() {
                    @Override public void run() {
                        handle(socket);
                    }
                });
            } catch (IOException error) {
                if (running) {
                    AppLog.w(TAG, "Accept failed", error);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            while (true) {
                String header = reader.readLine();
                if (header == null || header.length() == 0) {
                    break;
                }
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"GET".equals(parts[0])) {
                send(socket, 405, "text/plain; charset=utf-8", "Method Not Allowed");
                return;
            }
            String target = parts[1];
            String path = target;
            String query = "";
            int question = target.indexOf('?');
            if (question >= 0) {
                path = target.substring(0, question);
                query = target.substring(question + 1);
            }
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            handleGet(socket, path, query);
        } catch (IOException error) {
            AppLog.w(TAG, "Connection failed", error);
        } catch (JSONException error) {
            AppLog.w(TAG, "Unable to build response", error);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleGet(Socket socket, String path, String query)
            throws IOException, JSONException {
        if ("/refresh".equals(path)) {
            if (!authorized(query)) {
                sendJson(socket, 403, new JSONObject().put("error", "forbidden"));
                return;
            }
            boolean force = "1".equals(parameter(query, "force"))
                    || "true".equalsIgnoreCase(parameter(query, "force"));
            if (!service.requestRefresh(force)) {
                sendJson(socket, 409, new JSONObject().put("error", "refreshing"));
                return;
            }
            sendJson(socket, 202, new JSONObject().put("status", "accepted")
                    .put("mode", force ? "force" : "incremental"));
            return;
        }
        if ("/status".equals(path)) {
            if (!authorized(query)) {
                sendJson(socket, 403, new JSONObject().put("error", "forbidden"));
                return;
            }
            sendJson(socket, 200, service.statusJson());
            return;
        }
        if ("/".equals(path) || "/playlist.m3u".equals(path)
                || "/playlist.m3u8".equals(path)) {
            send(socket, 200, CONTENT_M3U, service.playlistText());
            return;
        }
        if (path.endsWith(".m3u8")) {
            String channelId = path.substring(1, path.length() - 5);
            if (channelId.startsWith("stream/")) {
                channelId = channelId.substring(7);
            }
            if (channelId.length() > 0) {
                boolean reset = "1".equals(parameter(query, "reset"))
                        || "true".equalsIgnoreCase(parameter(query, "reset"));
                CloudLiveTimeline.Window window = service.buildWindow(channelId, reset);
                if (window == null) {
                    send(socket, 404, "text/plain; charset=utf-8",
                            "Channel not found or unavailable");
                    return;
                }
                send(socket, 200, CONTENT_M3U8, renderWindow(window));
                return;
            }
        }
        send(socket, 404, "text/plain; charset=utf-8", "Not Found");
    }

    private String renderWindow(CloudLiveTimeline.Window window) {
        StringBuilder builder = new StringBuilder();
        builder.append("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-TARGETDURATION:").append(window.targetDuration).append('\n')
                .append("#EXT-X-MEDIA-SEQUENCE:").append(window.mediaSequence).append('\n');
        for (CloudLiveApi.Segment segment : window.items) {
            builder.append("#EXTINF:").append(segment.duration).append(",\n")
                    .append(segment.url).append('\n');
        }
        return builder.toString();
    }

    private boolean authorized(String query) {
        String expected = service.refreshToken();
        if (expected.length() == 0) {
            return true;
        }
        return expected.equals(parameter(query, "token"));
    }

    private static String parameter(String query, String name) {
        if (query == null || query.length() == 0) {
            return "";
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            if (name.equals(pair.substring(0, equals))) {
                return pair.substring(equals + 1);
            }
        }
        return "";
    }

    private void sendJson(Socket socket, int status, JSONObject json) throws IOException {
        send(socket, status, "application/json; charset=utf-8", json.toString());
    }

    private void send(Socket socket, int status, String contentType, String content)
            throws IOException {
        byte[] body = content.getBytes("UTF-8");
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append(status == 200 ? " OK"
                        : status == 202 ? " Accepted"
                        : status == 403 ? " Forbidden"
                        : status == 404 ? " Not Found"
                        : status == 409 ? " Conflict" : " Error")
                .append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Cache-Control: no-cache\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        header.append("Connection: close\r\n\r\n");
        OutputStream output = socket.getOutputStream();
        output.write(header.toString().getBytes("UTF-8"));
        output.write(body);
        output.flush();
    }
}
