package com.example.g_one.speech.gnani;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One WebSocket to Gnani. The JDK client always upgrades over HTTP/1.1, which Gnani needs. Events arrive in order, and
 * after {@link #close()} none arrive at all.
 */
final class GnaniSocket {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final long SEND_TIMEOUT_SECONDS = 5;
    private static final long CLOSE_TIMEOUT_MS = 500;

    interface Events {
        void opened(GnaniSocket socket);

        void text(String message);

        void closed(int code);

        /** The connection failed, or never opened; {@code error} is a handshake rejection when the server said no. */
        void failed(Throwable error);
    }

    private final Events events;
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile boolean closing;
    private volatile WebSocket ws;

    private GnaniSocket(Events events) {
        this.events = events;
    }

    static GnaniSocket open(HttpClient http, URI uri, Map<String, String> headers, Events events) {
        GnaniSocket socket = new GnaniSocket(events);
        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT);
        headers.forEach(builder::header);
        builder.buildAsync(uri, socket.new Listener()).whenComplete((ws, error) -> {
            if (error != null) {
                socket.fail(error);
            }
        });
        return socket;
    }

    /** Sends one binary message and waits for it to go out. Only one thread may send. */
    void sendBinary(byte[] data) throws IOException {
        await(ws.sendBinary(ByteBuffer.wrap(data), true));
    }

    /** Sends one text message and waits for it to go out. Only one thread may send. */
    void sendText(String text) throws IOException {
        await(ws.sendText(text, true));
    }

    private static void await(CompletionStage<WebSocket> sending) throws IOException {
        try {
            sending.toCompletableFuture().get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending");
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Could not send to the speech service", e);
        }
    }

    /** Closes the connection without waiting. Idempotent. Gnani has no cancel message, so this is also how to cancel. */
    void close() {
        closing = true;
        WebSocket open = ws;
        if (open == null) {
            return; // the listener aborts it as soon as it opens
        }
        open.sendClose(WebSocket.NORMAL_CLOSURE, "")
                .orTimeout(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((sent, error) -> open.abort());
    }

    private void fail(Throwable error) {
        if (!closing && finished.compareAndSet(false, true)) {
            events.failed(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            ws = socket;
            if (closing) {
                socket.abort();
                return;
            }
            events.opened(GnaniSocket.this);
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                if (!closing) {
                    events.text(message);
                }
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            if (!closing && finished.compareAndSet(false, true)) {
                events.closed(code);
            }
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            fail(error);
        }
    }
}
