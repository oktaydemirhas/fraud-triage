package dev.oktay.fraud.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First thing a POST meets, before any JSON is parsed: a size cap and a per-IP rate limit.
 * Running before body parsing means invalid or oversized requests are limited too.
 *
 * The limiter is a speed bump, not a quota: in memory, per instance. Its table is bounded, so spoofed
 * addresses cannot grow the heap; when it is full of live entries unknown addresses are refused.
 * Forwarding headers are client-controlled and ignored unless fraud.trust-forwarded-headers=true
 * (only when the service is reachable solely through a proxy you run).
 */
@Component
public class IngressGuard extends OncePerRequestFilter {
    static final long MAX_BYTES = 8 * 1024;
    static final int PER_MINUTE = 120;
    static final int MAX_TRACKED = 10_000;

    private final Clock clock;
    private final boolean trustForwardedHeaders;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public IngressGuard(Clock clock, @Value("${fraud.trust-forwarded-headers:false}") boolean trustForwardedHeaders) {
        this.clock = clock;
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (!allow(clientIp(req))) {
            reject(res, 429, "rate_limited");
            return;
        }
        long length = req.getContentLengthLong();
        if (length < 0) {
            reject(res, 411, "length_required");
        } else if (length > MAX_BYTES) {
            reject(res, 413, "payload_too_large");
        } else {
            chain.doFilter(req, res);
        }
    }

    private boolean allow(String ip) {
        long now = clock.millis();
        if (hits.size() >= MAX_TRACKED && !hits.containsKey(ip)) {
            sweep(now);
            if (hits.size() >= MAX_TRACKED) return false;
        }
        Deque<Long> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now);
            if (q.size() >= PER_MINUTE) return false;
            q.addLast(now);
            return true;
        }
    }

    private static void prune(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
    }

    private void sweep(long now) {
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now);
                return e.getValue().isEmpty();
            }
        });
    }

    private String clientIp(HttpServletRequest r) {
        if (trustForwardedHeaders) {
            String cf = r.getHeader("cf-connecting-ip");
            if (cf != null && !cf.isBlank()) return truncate(cf.strip());
            String xff = r.getHeader("x-forwarded-for");
            if (xff != null && !xff.isBlank()) return truncate(xff.split(",")[0].strip());
        }
        return r.getRemoteAddr();
    }

    private static String truncate(String s) {
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    private static void reject(HttpServletResponse res, int status, String code) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getOutputStream().write(("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
