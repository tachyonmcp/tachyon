/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.dropIfRejected;
import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.rejectAndClose;

import dev.tachyonmcp.api.annotations.InternalApi;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guards against DNS-rebinding attacks by validating the {@code Host} and {@code Origin} headers.
 * Rejected requests receive {@code 403 Forbidden} and the connection is closed.
 *
 * <p><b>Host.</b> A request's {@code Host} authority must be {@code localhost}, {@code localhost.},
 * {@code 127.0.0.1}, {@code [::1]}, or match one of the configured {@link
 * #DnsRebindingProtectionHandler(List) allowedHosts}. The guard fails closed: a request carrying
 * <em>multiple</em> {@code Host} headers is rejected (RFC&nbsp;7230 §5.4), and a missing/blank {@code
 * Host} is rejected on HTTP/1.1+ (where {@code Host} is mandatory). HTTP/1.0, where {@code Host} is
 * optional, is exempt from the missing-Host rule.
 *
 * <p><b>Origin.</b> When present, {@code Origin} must use one of the loopback hosts accepted above,
 * or equal one of the configured {@link #DnsRebindingProtectionHandler(List, Set) allowedOrigins}
 * exactly. A request carrying multiple {@code Origin} headers is rejected, and the opaque {@code
 * Origin: null} is always rejected, even when listed: any web page can send it from a sandboxed
 * iframe or through a cross-origin redirect, so admitting it would admit every site. The guard fails
 * closed on any origin it cannot positively identify as loopback or allowed.
 * {@code allowedHosts} does <em>not</em> widen this: a browser page whose {@code Origin} is a non-local
 * host (e.g. {@code http://host.docker.internal:3000}) is still rejected unless that origin is in
 * {@code allowedOrigins}. {@code allowedHosts} helps clients reach a server whose {@code Host} is
 * non-local (e.g. a container reaching the host via {@code host.docker.internal}); a remote browser
 * page reaching such a server needs both lists.
 *
 * <p><b>{@code allowedHosts} semantics.</b> Each entry is a bare <em>authority</em> (a host, or
 * {@code host:port}) — not a URL. Matching is case-insensitive.
 * <ul>
 *   <li>{@code "example.com"} — that host on <em>any</em> port.</li>
 *   <li>{@code "example.com:8096"} — only that {@code host:port}.</li>
 *   <li>IPv6 literals must be bracketed: {@code "[2001:db8::1]"} or {@code "[2001:db8::1]:8096"}.</li>
 * </ul>
 * Entries are trimmed; an entry that is blank, or contains whitespace, control characters, or URL
 * syntax (scheme, path, user-info, query, or fragment) is rejected with {@link
 * IllegalArgumentException}. The default (empty allowlist) preserves the loopback-only behaviour.
 */
@ChannelHandler.Sharable
@InternalApi
public class DnsRebindingProtectionHandler extends ChannelInboundHandlerAdapter {

    private static final String NULL_ORIGIN = "null";

    private final Set<String> allowedHosts;
    private final Set<String> allowedOrigins;

    /** Loopback-only protection (no additional allowed hosts). */
    public DnsRebindingProtectionHandler() {
        this(List.of());
    }

    /**
     * @param allowedHosts additional {@code Host} authorities to accept beyond the built-in loopback
     *     hosts (see the class documentation for entry syntax and matching rules)
     * @throws IllegalArgumentException if an entry is not a bare host/{@code host:port} authority
     */
    public DnsRebindingProtectionHandler(List<String> allowedHosts) {
        this(allowedHosts, Set.of());
    }

    /**
     * @param allowedHosts   additional {@code Host} authorities to accept beyond the built-in loopback
     *     hosts (see the class documentation for entry syntax and matching rules)
     * @param allowedOrigins additional {@code Origin} values to accept beyond loopback, matched exactly;
     *     {@code "null"} is ignored
     * @throws IllegalArgumentException if an {@code allowedHosts} entry is not a bare host/{@code
     *     host:port} authority
     */
    public DnsRebindingProtectionHandler(List<String> allowedHosts, Set<String> allowedOrigins) {
        this.allowedHosts = allowedHosts.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(h -> !h.isEmpty())
                .map(DnsRebindingProtectionHandler::validateHostEntry)
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.allowedOrigins = Set.copyOf(allowedOrigins);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (dropIfRejected(ctx, msg)) {
            return;
        }
        if (msg instanceof HttpRequest req) {
            var origins = req.headers().getAll(HttpHeaderNames.ORIGIN);
            if (origins.size() > 1) {
                // Multiple Origin headers are as ambiguous as multiple Host headers — reject.
                reject(ctx, msg);
                return;
            }
            var origin = origins.isEmpty() ? null : origins.get(0);
            if (origin != null && !origin.isEmpty() && !isAllowedOrigin(origin)) {
                reject(ctx, msg);
                return;
            }
            var hosts = req.headers().getAll(HttpHeaderNames.HOST);
            if (hosts.size() > 1) {
                // Multiple Host headers are ambiguous and a request-smuggling vector — reject.
                reject(ctx, msg);
                return;
            }
            var host = hosts.isEmpty() ? null : hosts.get(0);
            if (host == null || host.isBlank()) {
                // HTTP/1.1+ mandates Host; a DNS-rebinding guard must fail closed on its absence.
                // HTTP/1.0 permits omitting it, so it is exempt.
                if (req.protocolVersion().compareTo(HttpVersion.HTTP_1_1) >= 0) {
                    reject(ctx, msg);
                    return;
                }
            } else if (!isLocalhostAuthority(host) && !isAllowedHost(host)) {
                reject(ctx, msg);
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }

    /**
     * Rejects the request with {@code 403 Forbidden} and closes the connection. Releases the inbound
     * message first: it is not forwarded down the pipeline, so nothing else will free its buffers.
     */
    private static void reject(ChannelHandlerContext ctx, Object msg) {
        rejectAndClose(ctx, msg, HttpResponseStatus.FORBIDDEN, "Forbidden");
    }

    /** Returns {@code true} when {@code origin} is loopback or configured; never for the opaque origin. */
    private boolean isAllowedOrigin(String origin) {
        if (NULL_ORIGIN.equals(origin)) {
            return false;
        }
        return isLocalhostOrigin(origin) || allowedOrigins.contains(origin);
    }

    /** Returns {@code true} when {@code authority} matches a configured allowed host. */
    private boolean isAllowedHost(String authority) {
        if (allowedHosts.isEmpty()) {
            return false;
        }
        var lower = authority.toLowerCase(Locale.ROOT);
        return allowedHosts.contains(lower) || allowedHosts.contains(stripPort(lower));
    }

    /** Returns {@code true} when the origin's host part is a loopback host accepted by the guard. */
    static boolean isLocalhostOrigin(String origin) {
        int sep = origin.indexOf("//");
        if (sep < 0) return false;
        var authority = origin.substring(sep + 2);
        // Strip any path
        int slash = authority.indexOf('/');
        if (slash >= 0) authority = authority.substring(0, slash);
        return isLocalhostAuthority(authority);
    }

    /** Returns {@code true} when {@code authority} ({@code host} or {@code host:port}) is loopback. */
    static boolean isLocalhostAuthority(String authority) {
        if (authority.isEmpty()) return false;
        var host = stripPort(authority);
        return host.equalsIgnoreCase("localhost")
                || host.equalsIgnoreCase("localhost.")
                || host.equals("127.0.0.1")
                || host.equals("[::1]");
    }

    /**
     * Strips a trailing {@code :port} (digits only) from an authority, leaving the host. Handles
     * bracketed IPv6 literals ({@code [::1]:8096} -> {@code [::1]}); the colons inside the brackets
     * are not ports. A malformed suffix (non-numeric port, junk after {@code ]}) is left in place so
     * the authority matches nothing and the guard fails closed.
     */
    private static String stripPort(String authority) {
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) return authority;
            var rest = authority.substring(close + 1);
            return rest.isEmpty() || isPortSuffix(rest) ? authority.substring(0, close + 1) : authority;
        }
        int colon = authority.lastIndexOf(':');
        return colon >= 0 && isPortSuffix(authority.substring(colon)) ? authority.substring(0, colon) : authority;
    }

    /** Returns {@code true} when {@code suffix} is {@code :digits}. */
    private static boolean isPortSuffix(String suffix) {
        if (suffix.length() < 2 || suffix.charAt(0) != ':') return false;
        for (int i = 1; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return false;
        }
        return true;
    }

    /** Validates a trimmed, non-empty allowlist entry is a bare authority; throws otherwise. */
    private static String validateHostEntry(String entry) {
        for (int i = 0; i < entry.length(); i++) {
            char c = entry.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw new IllegalArgumentException(
                        "allowedHosts entry must not contain whitespace or control characters: '" + entry + "'");
            }
        }
        if (entry.indexOf('/') >= 0 || entry.indexOf('@') >= 0 || entry.indexOf('?') >= 0 || entry.indexOf('#') >= 0) {
            throw new IllegalArgumentException(
                    "allowedHosts entry must be a bare host or host:port authority, not a URL: '" + entry + "'");
        }
        return entry;
    }
}
