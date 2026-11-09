package dev.oktay.fraud.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IngressGuardTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneId.of("UTC"));

    private static MockHttpServletRequest post(String remote, String forwardedFor, int bodyBytes) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/events");
        r.setRemoteAddr(remote);
        if (forwardedFor != null) r.addHeader("X-Forwarded-For", forwardedFor);
        r.setContent(new byte[bodyBytes]);
        return r;
    }

    private static int status(IngressGuard g, MockHttpServletRequest r) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        g.doFilter(r, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void spoofedForwardingHeaderDoesNotResetTheLimit() throws Exception {
        IngressGuard g = new IngressGuard(CLOCK, false);
        int ok = 0;
        for (int i = 0; i < 200; i++) if (status(g, post("203.0.113.9", "198.51.100." + i, 10)) == 200) ok++;
        assertEquals(IngressGuard.PER_MINUTE, ok);
    }

    @Test
    void headerIsHonouredOnlyWhenTheOperatorTrustsTheProxy() throws Exception {
        IngressGuard g = new IngressGuard(CLOCK, true);
        int ok = 0;
        for (int i = 0; i < 200; i++) if (status(g, post("10.0.0.1", "198.51.100." + i, 10)) == 200) ok++;
        assertEquals(200, ok);
    }

    @Test
    void invalidAndOversizedBodiesAreLimitedBeforeParsing() throws Exception {
        IngressGuard g = new IngressGuard(CLOCK, false);
        assertEquals(413, status(g, post("203.0.113.1", null, (int) IngressGuard.MAX_BYTES + 1)));
        // Garbage bodies count against the rate limit as well, because the limit runs before parsing.
        int refused = 0;
        for (int i = 0; i < IngressGuard.PER_MINUTE + 20; i++) if (status(g, post("203.0.113.2", null, 10)) == 429) refused++;
        assertEquals(20, refused);
    }

    @Test
    void getRequestsAndPassThroughAreUntouched() throws Exception {
        IngressGuard g = new IngressGuard(CLOCK, false);
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/events");
        MockFilterChain chain = new MockFilterChain();
        g.doFilter(get, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());

        MockFilterChain blocked = new MockFilterChain();
        g.doFilter(post("203.0.113.3", null, (int) IngressGuard.MAX_BYTES + 1), new MockHttpServletResponse(), blocked);
        assertNull(blocked.getRequest());
    }
}
