/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import dev.tachyonmcp.api.annotations.InternalApi;
import io.netty.util.NetUtil;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HexFormat;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Parses serialized origins (RFC 6454 §6.2): {@code scheme "://" host [ ":" port ]}, the only form a
 * browser sends in {@code Origin}. The same strict shape applies to request headers and to configured
 * {@code allowedOrigins}, so both compare in one canonical form.
 */
@InternalApi
public final class Origins {

    private Origins() {}

    /**
     * Returns the canonical form of a serialized origin: scheme and host lower-cased, the default port
     * (80 for {@code http}, 443 for {@code https}) dropped, IPv6 literals compressed to browser notation.
     * Anything that is not a serialized origin —
     * another scheme, user info, any path including a bare {@code /}, a query, a fragment, a port
     * outside 1..65535 or not in plain decimal, an IPv6 zone ID, or the opaque {@code null} — yields {@code null}.
     * Malformed input is never repaired into a valid origin.
     *
     * @param origin the header or configuration value
     * @return the canonical origin, or {@code null} when {@code origin} is not a serialized origin
     */
    public static @Nullable String canonical(String origin) {
        if (origin.indexOf('?') >= 0 || origin.indexOf('#') >= 0) {
            return null;
        }
        final URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException e) {
            return null;
        }
        var scheme = uri.getScheme();
        if (scheme == null || uri.isOpaque()) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        var host = uri.getHost();
        var authority = uri.getRawAuthority();
        if (host == null
                || authority == null
                || uri.getRawUserInfo() != null
                || !uri.getRawPath().isEmpty()) {
            return null;
        }
        int port = uri.getPort();
        if (!hasCanonicalPortSpelling(authority, port) || port == 0 || port > 65535) {
            return null;
        }
        if (host.startsWith("[")) {
            if (host.indexOf('%') >= 0) {
                return null;
            }
            if (host.indexOf('.') >= 0) {
                final var colon = host.lastIndexOf(':');
                final var ipv4 =
                        NetUtil.createByteArrayFromIpAddressString(host.substring(colon + 1, host.length() - 1));
                if (ipv4 == null || ipv4.length != 4) {
                    return null;
                }
                // Netty maps dotted IPv4 tails to ::ffff; preserve the original IPv6 prefix instead.
                host = host.substring(0, colon + 1) + HexFormat.of().formatHex(ipv4, 0, 2) + ":"
                        + HexFormat.of().formatHex(ipv4, 2, 4) + "]";
            }
            final var address = NetUtil.createByteArrayFromIpAddressString(host);
            if (address == null || address.length != 16) {
                return null;
            }
            host = "[" + NetUtil.bytesToIpAddress(address) + "]";
        }
        var canonical = scheme + "://" + host.toLowerCase(Locale.ROOT);
        var defaultPort = scheme.equals("http") ? 80 : 443;
        return port < 0 || port == defaultPort ? canonical : canonical + ":" + port;
    }

    /**
     * Returns the canonical form of a configured {@code allowedOrigins} entry.
     *
     * @param entry the configured origin
     * @return the canonical origin
     * @throws IllegalArgumentException if {@code entry} is blank, {@code *}, {@code null}, or not a
     *     serialized {@code http}/{@code https} origin
     */
    public static String requireConfigured(String entry) {
        var canonical = entry.isBlank() || entry.equals("*") ? null : canonical(entry);
        if (canonical == null) {
            throw new IllegalArgumentException(
                    "allowedOrigins entry must be a serialized origin, http(s)://host[:port] with no path: '" + entry
                            + "'");
        }
        return canonical;
    }

    /**
     * Rejects an empty port ({@code host:}) and port spellings {@link URI} normalizes, such as leading
     * zeros: the digits after the last {@code :} outside an IPv6 literal must be the port in decimal.
     */
    private static boolean hasCanonicalPortSpelling(String authority, int port) {
        int hostEnd = authority.lastIndexOf(']');
        int colon = authority.lastIndexOf(':');
        if (colon <= hostEnd) {
            return port < 0;
        }
        return port >= 0 && authority.substring(colon + 1).equals(Integer.toString(port));
    }
}
