package dev.tachyonmcp.docs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class RawHttp {

    private static final String LIST_TOOLS = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{
              "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                       "io.modelcontextprotocol/clientInfo":{"name":"t","version":"1"},
                       "io.modelcontextprotocol/clientCapabilities":{}}}}
            """;

    public record Response(int status, Map<String, String> headers) {}

    private RawHttp() {}

    public static int postStatus(int port, String hostHeader) {
        return post("127.0.0.1", port, hostHeader, null).status();
    }

    public static Response post(String address, int port, String hostHeader, @Nullable String origin) {
        var payload = LIST_TOOLS.getBytes(StandardCharsets.UTF_8);
        var head = ("POST /mcp HTTP/1.1\r\nHost: %s\r\n%sContent-Type: application/json\r\n"
                        + "Accept: application/json, text/event-stream\r\nMCP-Protocol-Version: 2026-07-28\r\n"
                        + "Mcp-Method: tools/list\r\nConnection: close\r\nContent-Length: %d\r\n\r\n")
                .formatted(hostHeader, origin == null ? "" : "Origin: " + origin + "\r\n", payload.length);
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 2000);
            var out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            var status = Integer.parseInt(reader.readLine().split(" ")[1]);
            var headers = new LinkedHashMap<String, String>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                var colon = line.indexOf(':');
                headers.put(line.substring(0, colon).toLowerCase(java.util.Locale.ROOT), line.substring(colon + 1).trim());
            }
            return new Response(status, headers);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
