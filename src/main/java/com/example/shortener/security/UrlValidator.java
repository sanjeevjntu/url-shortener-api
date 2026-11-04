package com.example.shortener.security;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/// Validates and normalises target URLs (decision D5). Pure: it never performs a DNS lookup.
///
/// **Normalises:** lowercase scheme and host, IDN to ASCII, drop default port, drop `user:pass@`, resolve
/// dot segments, empty path to `/`, percent-encode raw non-ASCII. **Preserves:** already-encoded bytes
/// (`%20` stays `%20`), the query string and the fragment.
///
/// **Rejects:** non-http(s) schemes, over-long URLs, single-label hosts (`http://intranet/`), `localhost`-style
/// and internal names, loopback/private/link-local/metadata/CGNAT IPs, and ambiguous numeric hosts such as
/// `http://2130706433/` or `http://0x7f.1/` that browsers silently treat as IP addresses.
public final class UrlValidator {

    public static final int MAX_LENGTH = 4096;

    private static final Set<String> BLOCKED_NAMES =
            Set.of("localhost", "metadata.google.internal", "instance-data", "ip6-localhost", "ip6-loopback");
    private static final List<String> BLOCKED_SUFFIXES =
            List.of(".localhost", ".local", ".internal", ".localdomain", ".home.arpa", ".lan", ".intranet");

    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    /// WHATWG URL rule: if the last label looks numeric (decimal or hex), the whole host is parsed as IPv4.
    private static final Pattern NUMERIC_LABEL = Pattern.compile("0x[0-9a-f]*|[0-9]+");
    private static final Pattern DOTTED_QUAD =
            Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-f:.]+");

    /// Returns the normalised URL or throws [InvalidUrlException] with a caller-safe message.
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidUrlException("URL is required");
        }
        String input = raw.strip();
        if (input.length() > MAX_LENGTH) {
            throw new InvalidUrlException("URL is longer than " + MAX_LENGTH + " characters");
        }

        URI uri;
        try {
            uri = new URI(input).normalize(); // resolves "." and ".." segments, keeps raw (encoded) components
        } catch (URISyntaxException _) {
            throw new InvalidUrlException(
                    "URL is not well formed (spaces and special characters must be percent-encoded)");
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new InvalidUrlException("Only http and https URLs are allowed");
        }
        if (uri.isOpaque() || uri.getRawAuthority() == null) {
            throw new InvalidUrlException("URL must include a host");
        }

        HostPort hostPort = parseAuthority(uri.getRawAuthority());
        String host = checkHost(hostPort.host());
        String port = normalizePort(hostPort.port(), scheme);

        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        StringBuilder out = new StringBuilder(input.length() + 8)
                .append(scheme)
                .append("://")
                .append(host)
                .append(port)
                .append(asciiOnly(path));
        if (uri.getRawQuery() != null) {
            out.append('?').append(asciiOnly(uri.getRawQuery()));
        }
        if (uri.getRawFragment() != null) {
            out.append('#').append(asciiOnly(uri.getRawFragment()));
        }
        if (out.length() > MAX_LENGTH) {
            throw new InvalidUrlException("URL is longer than " + MAX_LENGTH + " characters");
        }
        return out.toString();
    }

    private record HostPort(String host, String port) {}

    /// Splits `userinfo@host:port`, dropping userinfo. Handles bracketed IPv6 (`[::1]:8080`).
    private static HostPort parseAuthority(String authority) {
        String hostPort = authority.substring(authority.lastIndexOf('@') + 1);
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            if (end < 0) {
                throw new InvalidUrlException("URL host is not valid");
            }
            String rest = hostPort.substring(end + 1);
            if (!rest.isEmpty() && !rest.startsWith(":")) {
                throw new InvalidUrlException("URL host is not valid");
            }
            return new HostPort(hostPort.substring(0, end + 1), rest.isEmpty() ? "" : rest.substring(1));
        }
        int colon = hostPort.lastIndexOf(':');
        return colon < 0
                ? new HostPort(hostPort, "")
                : new HostPort(hostPort.substring(0, colon), hostPort.substring(colon + 1));
    }

    private static String normalizePort(String port, String scheme) {
        if (port.isEmpty()) {
            return "";
        }
        if (port.length() > 5 || !port.chars().allMatch(Character::isDigit)) {
            throw new InvalidUrlException("URL port is not valid");
        }
        int value = Integer.parseInt(port);
        if (value < 1 || value > 65_535) {
            throw new InvalidUrlException("URL port is not valid");
        }
        boolean isDefault = ("http".equals(scheme) && value == 80) || ("https".equals(scheme) && value == 443);
        return isDefault ? "" : ":" + value;
    }

    /// Returns the normalised host, or throws if it is malformed or points somewhere internal.
    private static String checkHost(String rawHost) {
        if (rawHost.isEmpty() || rawHost.indexOf('%') >= 0) {
            throw new InvalidUrlException("URL host is not valid");
        }
        if (rawHost.startsWith("[")) {
            String literal = rawHost.substring(1, rawHost.length() - 1).toLowerCase(Locale.ROOT);
            if (!IPV6_CHARS.matcher(literal).matches()) {
                throw new InvalidUrlException("URL host is not valid");
            }
            checkAddress(parseLiteral(literal));
            return "[" + literal + "]";
        }

        String host;
        try {
            host = IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException _) {
            throw new InvalidUrlException("URL host is not valid");
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1); // "example.com." is the same host
        }
        if (host.isEmpty() || host.length() > 253) {
            throw new InvalidUrlException("URL host is not valid");
        }

        String[] labels = host.split("\\.", -1);
        if (NUMERIC_LABEL.matcher(labels[labels.length - 1]).matches()) {
            if (!DOTTED_QUAD.matcher(host).matches()) {
                throw new InvalidUrlException("URL host is an ambiguous numeric address");
            }
            checkAddress(parseLiteral(host));
            return host;
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                throw new InvalidUrlException("URL host is not valid");
            }
        }
        if (labels.length < 2
                || BLOCKED_NAMES.contains(host)
                || BLOCKED_SUFFIXES.stream().anyMatch(host::endsWith)) {
            throw new InvalidUrlException("URL host is not allowed");
        }
        return host;
    }

    /// Only called with strings already checked to be IP literals, so no DNS lookup can happen.
    private static InetAddress parseLiteral(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException _) {
            throw new InvalidUrlException("URL host is not valid");
        }
    }

    private static void checkAddress(InetAddress address) {
        boolean blocked = address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() // includes 169.254.169.254 (cloud metadata)
                || address.isSiteLocalAddress() // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()
                || isOtherNonPublic(address);
        if (blocked) {
            throw new InvalidUrlException("URL host is not allowed");
        }
    }

    private static boolean isOtherNonPublic(InetAddress address) {
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 0 // "this network"
                    || (first == 100 && second >= 64 && second <= 127) // CGNAT 100.64/10
                    || first >= 240; // reserved + broadcast
        }
        if (address instanceof Inet6Address v6) {
            if (v6.isIPv4CompatibleAddress()) { // ::a.b.c.d
                return true;
            }
            return (b[0] & 0xFE) == 0xFC; // unique local fc00::/7
        }
        return false;
    }

    /// Percent-encodes any raw non-ASCII character as UTF-8; existing `%XX` escapes are untouched.
    private static String asciiOnly(String component) {
        if (component.chars().allMatch(c -> c < 0x80)) {
            return component;
        }
        StringBuilder out = new StringBuilder(component.length() * 2);
        component.codePoints().forEach(cp -> {
            if (cp < 0x80) {
                out.append((char) cp);
            } else {
                for (byte x : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(String.format(Locale.ROOT, "%02X", x & 0xFF));
                }
            }
        });
        return out.toString();
    }
}
