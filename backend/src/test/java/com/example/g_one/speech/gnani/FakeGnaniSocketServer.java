package com.example.g_one.speech.gnani;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** A minimal RFC 6455 server on localhost standing in for Gnani's WebSockets. Tests script each connection. */
final class FakeGnaniSocketServer implements AutoCloseable {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** One accepted connection. */
    static final class Conn {
        final String path;
        final Map<String, String> headers;
        final List<byte[]> binary = new CopyOnWriteArrayList<>();
        final List<Long> binaryAtNanos = new CopyOnWriteArrayList<>();
        final List<String> texts = new CopyOnWriteArrayList<>();
        final CompletableFuture<Integer> clientClosed = new CompletableFuture<>();
        /** Completes the first time a text message arrives. */
        final CompletableFuture<String> firstText = new CompletableFuture<>();
        private final Socket socket;
        private final OutputStream out;

        private Conn(Socket socket, String path, Map<String, String> headers) throws IOException {
            this.socket = socket;
            this.path = path;
            this.headers = headers;
            this.out = socket.getOutputStream();
        }

        synchronized void send(String text) {
            write(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        /** Sends a close frame with this status and ends the connection. */
        synchronized void closeWith(int code) {
            write(0x8, new byte[] {(byte) (code >> 8), (byte) code});
            drop();
        }

        /** Cuts the TCP connection with no close frame. */
        void drop() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }

        private void write(int opcode, byte[] payload) {
            try {
                out.write(0x80 | opcode);
                if (payload.length < 126) {
                    out.write(payload.length);
                } else if (payload.length < 65536) {
                    out.write(126);
                    out.write(payload.length >> 8);
                    out.write(payload.length);
                } else {
                    out.write(127);
                    for (int shift = 56; shift >= 0; shift -= 8) out.write((int) ((long) payload.length >> shift));
                }
                out.write(payload);
                out.flush();
            } catch (IOException ignored) {
                // The client went away; the reader side records it.
            }
        }
    }

    final List<Conn> conns = new CopyOnWriteArrayList<>();
    /** Next N handshakes are answered with this status instead of an upgrade. */
    final AtomicInteger rejections = new AtomicInteger();
    volatile int rejectStatus = 401;
    /** Runs for each new connection, in accept order; the last one repeats. */
    private final List<Consumer<Conn>> scripts = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();
    private final ServerSocket server;

    FakeGnaniSocketServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::acceptLoop);
    }

    void onConnect(Consumer<Conn> script) {
        scripts.add(script);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        server.close();
        conns.forEach(Conn::drop);
    }

    private void acceptLoop() {
        try {
            while (true) {
                Socket socket = server.accept();
                Thread.ofVirtual().start(() -> serve(socket));
            }
        } catch (IOException e) {
            // Server closed.
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            String requestLine = readLine(in);
            Map<String, String> headers = new LinkedHashMap<>();
            for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
            }
            OutputStream out = socket.getOutputStream();
            if (rejections.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                out.write(("HTTP/1.1 " + rejectStatus + " Rejected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((headers.get("sec-websocket-key") + GUID).getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Conn conn = new Conn(socket, requestLine.split(" ")[1], headers);
            conns.add(conn);
            int index = Math.min(accepted.getAndIncrement(), scripts.size() - 1);
            if (index >= 0) scripts.get(index).accept(conn);
            readFrames(in, conn);
        } catch (Exception e) {
            // A dropped connection ends the test script; the assertions look at what was recorded.
        }
        conns.stream().filter(c -> socketOf(c) == socket).forEach(c -> c.clientClosed.complete(-1));
    }

    private static Socket socketOf(Conn c) {
        return c.socket;
    }

    private static void readFrames(DataInputStream in, Conn conn) throws IOException {
        ByteArrayOutputStream partial = new ByteArrayOutputStream();
        int partialOpcode = 0;
        while (true) {
            int b0 = in.readUnsignedByte();
            int b1 = in.readUnsignedByte();
            int opcode = b0 & 0x0f;
            boolean fin = (b0 & 0x80) != 0;
            long length = b1 & 0x7f;
            if (length == 126) length = in.readUnsignedShort();
            else if (length == 127) length = in.readLong();
            byte[] mask = (b1 & 0x80) != 0 ? in.readNBytes(4) : new byte[4];
            byte[] payload = in.readNBytes((int) length);
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            if (opcode == 0x8) {
                conn.clientClosed.complete(payload.length >= 2 ? ((payload[0] & 0xff) << 8) | (payload[1] & 0xff) : 1005);
                synchronized (conn) {
                    conn.write(0x8, payload);
                }
                return;
            }
            if (opcode == 0x9) {
                synchronized (conn) {
                    conn.write(0xA, payload);
                }
                continue;
            }
            if (opcode != 0) partialOpcode = opcode;
            partial.write(payload);
            if (!fin) continue;
            byte[] whole = partial.toByteArray();
            partial.reset();
            if (partialOpcode == 0x2) {
                conn.binary.add(whole);
                conn.binaryAtNanos.add(System.nanoTime());
            } else if (partialOpcode == 0x1) {
                String text = new String(whole, StandardCharsets.UTF_8);
                conn.texts.add(text);
                conn.firstText.complete(text);
            }
        }
    }

    private static String readLine(DataInputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != '\n') {
            if (c < 0) throw new IOException("Connection closed during handshake");
            if (c != '\r') line.write(c);
        }
        return line.toString(StandardCharsets.US_ASCII);
    }
}
