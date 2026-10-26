package com.innbucks.loyaltyservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Addresses are read as literals, never resolved, and matched against the partner's allow-list (V62). */
class IpLiteralsTest {

    @Test
    void literalsParse_andNamesAreNeverResolved() {
        assertThat(IpLiterals.parse("203.0.113.10")).isPresent();
        assertThat(IpLiterals.parse("2001:db8::1")).isPresent();
        assertThat(IpLiterals.parse("localhost")).isEmpty();
        assertThat(IpLiterals.parse("example.com")).isEmpty();
        assertThat(IpLiterals.parse("abc")).isEmpty();
        assertThat(IpLiterals.parse("256.1.1.1")).isEmpty();
        assertThat(IpLiterals.parse("1.2.3")).isEmpty();
        assertThat(IpLiterals.parse("fe80::1%eth0")).isEmpty();
        assertThat(IpLiterals.parse(null)).isEmpty();
    }

    @Test
    void addressesAndRangesMatch() throws Exception {
        String list = "203.0.113.10, 198.51.100.0/28, 2001:db8:1::/48";
        assertThat(IpLiterals.allowed(list, InetAddress.getByName("203.0.113.10"))).isTrue();
        assertThat(IpLiterals.allowed(list, InetAddress.getByName("203.0.113.11"))).isFalse();
        assertThat(IpLiterals.allowed(list, InetAddress.getByName("198.51.100.15"))).isTrue();
        assertThat(IpLiterals.allowed(list, InetAddress.getByName("198.51.100.16"))).isFalse();
        assertThat(IpLiterals.allowed(list, InetAddress.getByName("2001:db8:1:ffff::7"))).isTrue();
        assertThat(IpLiterals.allowed(list, InetAddress.getByName("2001:db8:2::7"))).isFalse();
        assertThat(IpLiterals.allowed("", InetAddress.getByName("203.0.113.10"))).isFalse();
        assertThat(IpLiterals.allowed(list, null)).isFalse();
    }

    @Test
    void aBadAllowListIsRefusedOnInput_andAllowsNothingIfStored() throws Exception {
        assertThatThrownBy(() -> IpLiterals.parseAllowList("203.0.113.10, localhost"))
                .hasMessageContaining("localhost");
        assertThatThrownBy(() -> IpLiterals.parseAllowList("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThat(IpLiterals.allowed("localhost", InetAddress.getByName("127.0.0.1"))).isFalse();
    }

    @Test
    void theClientIsNginxsXRealIp_andConflictingValuesCountAsNone() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("X-Forwarded-For", "203.0.113.10");
        assertThat(IpLiterals.realIp(r)).isEmpty();

        r.addHeader("X-Real-IP", "198.51.100.7");
        assertThat(IpLiterals.realIp(r)).map(InetAddress::getHostAddress).contains("198.51.100.7");

        r.addHeader("X-Real-IP", "198.51.100.7");
        assertThat(IpLiterals.realIp(r)).isPresent();

        r.addHeader("X-Real-IP", "198.51.100.8");
        assertThat(IpLiterals.realIp(r)).isEmpty();
    }
}
