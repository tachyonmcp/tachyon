/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

record CorsRawResponse(int status, Map<String, String> headers, String body) {

    @Nullable
    String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    static CorsRawResponse read(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        int tail = 0;
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            tail = (tail << 8) | b;
            if (tail == 0x0D0A0D0A) break;
        }
        var lines = head.toString(StandardCharsets.US_ASCII).split("\r\n");
        var status = Integer.parseInt(lines[0].split(" ")[1]);
        var headers = new HashMap<String, String>();
        for (int i = 1; i < lines.length; i++) {
            var colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.merge(
                        lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim(),
                        (x, y) -> x + ", " + y);
            }
        }
        var length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        var body = in.readNBytes(length);
        return new CorsRawResponse(status, headers, new String(body, StandardCharsets.UTF_8));
    }
}
