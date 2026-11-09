package dev.oktay.fraud.model;

import java.util.List;
import java.util.Map;

public record Triage(Event event, int score, Decision decision, List<Signal> signals) {
    public enum Decision { ALLOW, REVIEW, BLOCK }

    /** `evidence` holds every number a human (or the explainer) may quote about this signal. */
    public record Signal(String code, int weight, Map<String, Object> evidence) {}

    public boolean hasCode(String code) {
        return signals.stream().anyMatch(s -> s.code().equals(code));
    }
}
