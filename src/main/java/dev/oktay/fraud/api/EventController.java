package dev.oktay.fraud.api;

import dev.oktay.fraud.engine.TriageService;
import dev.oktay.fraud.engine.TriageService.RejectedEventException;
import dev.oktay.fraud.explain.Explainer;
import dev.oktay.fraud.explain.Explainer.Explanation;
import dev.oktay.fraud.model.Event;
import dev.oktay.fraud.model.Triage;
import dev.oktay.fraud.model.Triage.Decision;
import dev.oktay.fraud.model.Triage.Signal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EventController {
    private static final Logger log = LoggerFactory.getLogger(EventController.class);
    private static final int PER_MINUTE = 120;

    public record Response(Decision decision, int score, List<Signal> signals, Explanation explanation) {}

    private final TriageService triage;
    private final Explainer explainer;
    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public EventController(TriageService triage, Explainer explainer, Clock clock) {
        this.triage = triage;
        this.explainer = explainer;
        this.clock = clock;
    }

    @PostMapping("/api/events")
    public ResponseEntity<?> ingest(@Valid @RequestBody Event event, HttpServletRequest http) {
        if (!allow(clientIp(http))) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "rate_limited"));
        Triage t = triage.process(event);
        // Only events that need a human get a (paid) explanation; ALLOW stays free and fast.
        Explanation ex = t.decision() == Decision.ALLOW ? null : explainer.explain(t);
        log.info("triage decision={} score={} codes={}", t.decision(), t.score(), t.signals().stream().map(Signal::code).toList());
        return ResponseEntity.ok(new Response(t.decision(), t.score(), t.signals(), ex));
    }

    @ExceptionHandler(RejectedEventException.class)
    ResponseEntity<Map<String, String>> rejected(RejectedEventException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
    }

    /** Per-IP sliding window in memory: a speed bump, not a quota. */
    private boolean allow(String ip) {
        long now = clock.millis();
        Deque<Long> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            if (q.size() >= PER_MINUTE) return false;
            q.addLast(now);
            return true;
        }
    }

    private static String clientIp(HttpServletRequest r) {
        String cf = r.getHeader("cf-connecting-ip");
        if (cf != null && !cf.isBlank()) return cf.strip();
        String xff = r.getHeader("x-forwarded-for");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].strip();
        return r.getRemoteAddr();
    }
}
