package com.innbucks.loyaltyservice.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IP address literals and allow-lists for the partner API (V62), parsed WITHOUT
 * DNS: a header value is never resolved, so a caller can never make us look up a
 * name of its choosing. IPv4 is parsed by hand; IPv6 only when the text holds
 * nothing but hex digits, colons and dots, which {@link InetAddress} then reads as
 * a literal.
 *
 * <p>The client address is nginx's {@code X-Real-IP}, never {@code
 * X-Forwarded-For}: nginx overwrites the former with the peer address, while the
 * latter's left-most entry is whatever the caller sent (the fleet rule in
 * ticketing-system's CLAUDE.md).
 */
public final class IpLiterals {

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    private IpLiterals() {
    }

    /** The address in {@code text} if it is an IP literal, else empty. Never DNS. */
    public static Optional<InetAddress> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.strip();
        Matcher m = IPV4.matcher(t);
        try {
            if (m.matches()) {
                byte[] b = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int part = Integer.parseInt(m.group(i + 1));
                    if (part > 255) {
                        return Optional.empty();
                    }
                    b[i] = (byte) part;
                }
                return Optional.of(InetAddress.getByAddress(b));
            }
            if (t.indexOf(':') >= 0 && IPV6_CHARS.matcher(t).matches()) {
                return Optional.of(InetAddress.getByName(t));
            }
        } catch (UnknownHostException | NumberFormatException notALiteral) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * The client address from nginx's {@code X-Real-IP}. Empty when the header is
     * missing, is not an IP literal, or appears more than once with different
     * values; the same value twice is accepted (an nginx config that sets it in
     * two places).
     */
    public static Optional<InetAddress> realIp(HttpServletRequest request) {
        List<String> values = request.getHeaders("X-Real-IP") == null ? List.of()
                : Collections.list(request.getHeaders("X-Real-IP"));
        String chosen = null;
        for (String raw : values) {
            for (String v : raw.split(",")) {
                String s = v.strip();
                if (s.isEmpty()) {
                    continue;
                }
                if (chosen != null && !chosen.equals(s)) {
                    return Optional.empty();
                }
                chosen = s;
            }
        }
        return parse(chosen);
    }

    /** One allow-list entry: an address, or a network in CIDR form. */
    record Entry(byte[] network, int prefixBits) {
        boolean matches(InetAddress address) {
            byte[] a = address.getAddress();
            if (a.length != network.length) {
                return false;
            }
            int full = prefixBits / 8;
            for (int i = 0; i < full; i++) {
                if (a[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefixBits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (a[full] & mask) == (network[full] & mask);
        }
    }

    /**
     * Parses a comma-separated allow-list of IP literals and CIDR ranges.
     *
     * @throws IllegalArgumentException naming the first entry that is not one
     */
    public static List<Entry> parseAllowList(String list) {
        List<Entry> entries = new ArrayList<>();
        if (list == null || list.isBlank()) {
            return entries;
        }
        for (String raw : list.split(",")) {
            String item = raw.strip();
            if (item.isEmpty()) {
                continue;
            }
            int slash = item.indexOf('/');
            String addr = slash < 0 ? item : item.substring(0, slash);
            InetAddress parsed = parse(addr).orElseThrow(() ->
                    new IllegalArgumentException("Not an IP address or CIDR range: " + item));
            int maxBits = parsed.getAddress().length * 8;
            int bits = maxBits;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(item.substring(slash + 1).strip());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Not an IP address or CIDR range: " + item);
                }
                if (bits < 0 || bits > maxBits) {
                    throw new IllegalArgumentException("Not an IP address or CIDR range: " + item);
                }
            }
            entries.add(new Entry(parsed.getAddress(), bits));
        }
        return entries;
    }

    /** True when {@code address} is in the allow-list. An empty or unreadable list allows nothing. */
    public static boolean allowed(String allowList, InetAddress address) {
        if (address == null) {
            return false;
        }
        try {
            return parseAllowList(allowList).stream().anyMatch(e -> e.matches(address));
        } catch (IllegalArgumentException badList) {
            return false;
        }
    }
}
