package me.arasple.mc.trchat.neoforge;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Small bounded RESP2 transport. No Redis library or runtime downloads required. */
final class RespConnection implements AutoCloseable {
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    RespConnection(ChatConfig.Redis config) throws IOException {
        socket = config.ssl ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        try {
            socket.connect(new InetSocketAddress(config.host, config.port), config.timeoutMillis);
            socket.setSoTimeout(config.timeoutMillis);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            if (socket instanceof SSLSocket ssl) {
                var parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
            }
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
            if (!config.password.isEmpty()) {
                if (config.username.isEmpty()) command("AUTH", config.password);
                else command("AUTH", config.username, config.password);
            }
            if (config.database != 0) command("SELECT", Integer.toString(config.database));
        } catch (IOException | RuntimeException ex) { socket.close(); throw ex; }
    }

    Object command(String... args) throws IOException { write(output, args); return read(input); }
    void subscribe(String channel) throws IOException {
        command("SUBSCRIBE", channel);
        // Subscription reads time out periodically so the owner can check shutdown.
        socket.setSoTimeout(30000);
    }
    void ping() throws IOException { write(output, "PING"); }
    Object next() throws IOException { return read(input); }

    static void write(OutputStream out, String... args) throws IOException {
        out.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            out.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.write('\r'); out.write('\n');
        }
        out.flush();
    }

    static Object read(InputStream in) throws IOException { return read(in, 0); }
    private static Object read(InputStream in, int depth) throws IOException {
        if (depth > 8) throw new IOException("RESP nesting too deep");
        int type = in.read();
        if (type < 0) throw new EOFException();
        String header = line(in);
        try {
            return switch (type) {
                case '+' -> header;
                // Do not expose server error text, which may contain credentials.
                case '-' -> throw new IOException("Redis rejected command");
                case ':' -> Long.parseLong(header);
                case '$' -> {
                    int size = Integer.parseInt(header);
                    if (size == -1) yield null;
                    if (size < 0 || size > 1048576) throw new IOException("Invalid RESP bulk size");
                    byte[] data = in.readNBytes(size);
                    if (data.length != size || in.read() != '\r' || in.read() != '\n') throw new EOFException();
                    yield new String(data, StandardCharsets.UTF_8);
                }
                case '*' -> {
                    int count = Integer.parseInt(header);
                    if (count == -1) yield null;
                    if (count < 0 || count > 1024) throw new IOException("Invalid RESP array size");
                    List<Object> values = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) values.add(read(in, depth + 1));
                    yield values;
                }
                default -> throw new IOException("Invalid RESP type");
            };
        } catch (NumberFormatException ex) { throw new IOException("Invalid RESP number", ex); }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 4096; i++) {
            int next = in.read();
            if (next < 0) throw new EOFException();
            if (next == '\r') {
                if (in.read() != '\n') throw new IOException("Invalid RESP line");
                return bytes.toString(StandardCharsets.US_ASCII);
            }
            bytes.write(next);
        }
        throw new IOException("RESP line too long");
    }
    @Override public void close() throws IOException { socket.close(); }
}
