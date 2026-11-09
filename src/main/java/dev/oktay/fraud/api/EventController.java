package dev.oktay.fraud.api;

import dev.oktay.fraud.engine.TriageService;
import dev.oktay.fraud.engine.TriageService.RejectedEventException;
import dev.oktay.fraud.explain.Explainer;
import dev.oktay.fraud.explain.Explainer.Explanation;
import dev.oktay.fraud.model.Event;
import dev.oktay.fraud.model.Triage;
import dev.oktay.fraud.model.Triage.Decision;
import dev.oktay.fraud.model.Triage.Signal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EventController {
    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    public record Response(Decision decision, int score, List<Signal> signals, Explanation explanation) {}

    private final TriageService triage;
    private final Explainer explainer;

    public EventController(TriageService triage, Explainer explainer) {
        this.triage = triage;
        this.explainer = explainer;
    }

    /** Rate limit and size cap run earlier, in IngressGuard, before the body is parsed. */
    @PostMapping("/api/events")
    public ResponseEntity<?> ingest(@Valid @RequestBody Event event) {
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

}
