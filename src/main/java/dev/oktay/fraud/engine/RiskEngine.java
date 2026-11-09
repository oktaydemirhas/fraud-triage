package dev.oktay.fraud.engine;

import dev.oktay.fraud.model.Event;
import dev.oktay.fraud.model.Event.Type;
import dev.oktay.fraud.model.Triage;
import dev.oktay.fraud.model.Triage.Decision;
import dev.oktay.fraud.model.Triage.Signal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deterministic scoring. `prior` is every earlier event of the same account (already ordered), `e` the event being judged.
 * The score is the sum of the weights of the signals that fired, capped at 100; the LLM never touches it.
 */
@Component
public class RiskEngine {
    static final int REVIEW_AT = 30;
    static final int BLOCK_AT = 70;

    static final int VELOCITY_WINDOW_MIN = 10;
    static final int LOGIN_WINDOW_MIN = 10;
    static final int TRAVEL_WINDOW_MIN = 120;
    static final int ATO_WINDOW_MIN = 30;
    static final int MIN_HISTORY = 5;
    static final double MATERIAL_AMOUNT = 500;
    static final double HIGH_AMOUNT = 5000;
    static final double ATO_AMOUNT = 2000;

    public Triage score(Event e, List<Event> prior) {
        List<Signal> s = new ArrayList<>();
        Instant at = e.at();

        // Payments in the last 10 minutes, this one included.
        long payments = prior.stream().filter(p -> p.isPayment() && within(p, at, VELOCITY_WINDOW_MIN)).count() + (e.isPayment() ? 1 : 0);
        if (e.isPayment() && payments >= 8) s.add(sig("VELOCITY", 45, "payments", payments, "windowMinutes", VELOCITY_WINDOW_MIN));
        else if (e.isPayment() && payments >= 5) s.add(sig("VELOCITY", 30, "payments", payments, "windowMinutes", VELOCITY_WINDOW_MIN));

        long failures = prior.stream().filter(p -> p.type() == Type.LOGIN_FAILED && within(p, at, LOGIN_WINDOW_MIN)).count() + (e.type() == Type.LOGIN_FAILED ? 1 : 0);
        if (failures >= 5 && e.type() == Type.LOGIN_OK) s.add(sig("BRUTE_FORCE_LOGIN", 50, "failedLogins", failures, "windowMinutes", LOGIN_WINDOW_MIN));
        else if (failures >= 5 && e.type() == Type.LOGIN_FAILED) s.add(sig("BRUTE_FORCE_LOGIN", 25, "failedLogins", failures, "windowMinutes", LOGIN_WINDOW_MIN));

        if (e.country() != null) {
            Event elsewhere = prior.stream().filter(p -> p.country() != null && !p.country().equalsIgnoreCase(e.country()) && within(p, at, TRAVEL_WINDOW_MIN))
                    .reduce((a, b) -> b).orElse(null);
            if (elsewhere != null) {
                long minutes = Duration.between(elsewhere.at(), at).toMinutes();
                s.add(sig("IMPOSSIBLE_TRAVEL", 40, "minutesApart", minutes, "windowMinutes", TRAVEL_WINDOW_MIN));
            }
        }

        // Below MATERIAL_AMOUNT a statistical outlier is noise: on a tight history (180-220 TL) a normal 300 TL payment is "4.6 sigma".
        if (e.isPayment() && e.amountValue() >= MATERIAL_AMOUNT) {
            double z = amountZ(e, prior);
            if (!Double.isNaN(z) && z >= 5) s.add(sig("AMOUNT_ANOMALY", 45, "zScore", round1(z), "amountTry", e.amountValue()));
            else if (!Double.isNaN(z) && z >= 3) s.add(sig("AMOUNT_ANOMALY", 35, "zScore", round1(z), "amountTry", e.amountValue()));
        }

        boolean newDevice = e.deviceId() != null && !prior.isEmpty() && prior.stream().noneMatch(p -> e.deviceId().equals(p.deviceId()));
        if (e.isPayment() && newDevice && e.amountValue() >= HIGH_AMOUNT) s.add(sig("NEW_DEVICE_HIGH_AMOUNT", 30, "amountTry", e.amountValue(), "thresholdTry", HIGH_AMOUNT));
        if ((e.type() == Type.ADDRESS_CHANGE || e.type() == Type.PASSWORD_RESET) && newDevice) s.add(sig("ACCOUNT_CHANGE_NEW_DEVICE", 25));

        if (e.isPayment() && e.amountValue() >= ATO_AMOUNT) {
            Event change = prior.stream().filter(p -> (p.type() == Type.PASSWORD_RESET || p.type() == Type.ADDRESS_CHANGE) && within(p, at, ATO_WINDOW_MIN))
                    .reduce((a, b) -> b).orElse(null);
            if (change != null) s.add(sig("ACCOUNT_TAKEOVER_PATTERN", 40, "minutesAfterChange", Duration.between(change.at(), at).toMinutes(), "amountTry", e.amountValue()));
        }

        if (e.isPayment() && e.country() != null && prior.size() >= MIN_HISTORY) {
            String home = homeCountry(prior);
            if (home != null && !home.equalsIgnoreCase(e.country())) s.add(sig("FOREIGN_COUNTRY", 15, "country", e.country().toUpperCase(), "homeCountry", home));
        }

        int score = Math.min(100, s.stream().mapToInt(Signal::weight).sum());
        Decision d = score >= BLOCK_AT ? Decision.BLOCK : score >= REVIEW_AT ? Decision.REVIEW : Decision.ALLOW;
        return new Triage(e, score, d, List.copyOf(s));
    }

    /** True if `p` happened at most `minutes` before `at` (the boundary counts) and not after it. */
    private static boolean within(Event p, Instant at, int minutes) {
        return !p.at().isAfter(at) && !p.at().isBefore(at.minus(Duration.ofMinutes(minutes)));
    }

    /** z-score of this payment against the account's earlier payments; NaN until there are enough of them. */
    static double amountZ(Event e, List<Event> prior) {
        List<Double> amounts = prior.stream().filter(Event::isPayment).map(Event::amountValue).toList();
        if (amounts.size() < MIN_HISTORY) return Double.NaN;
        double mean = amounts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = amounts.stream().mapToDouble(a -> (a - mean) * (a - mean)).sum() / (amounts.size() - 1);
        double std = Math.sqrt(var);
        if (std == 0) return e.amountValue() == mean ? 0 : e.amountValue() > mean ? 99 : -99;
        return Math.min(99, (e.amountValue() - mean) / std);
    }

    private static String homeCountry(List<Event> prior) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Event p : prior) if (p.country() != null) counts.merge(p.country().toUpperCase(), 1, Integer::sum);
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static Signal sig(String code, int weight, Object... kv) {
        Map<String, Object> evidence = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) evidence.put((String) kv[i], kv[i + 1]);
        return new Signal(code, weight, evidence);
    }
}
