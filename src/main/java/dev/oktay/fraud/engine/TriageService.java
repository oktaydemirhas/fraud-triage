package dev.oktay.fraud.engine;

import dev.oktay.fraud.model.Event;
import dev.oktay.fraud.model.Triage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Per-account event history in memory (swap point for Kafka + a state store). Guarantees:
 * - idempotent: a repeated event id returns the first result and is not counted twice;
 * - timestamp-ordered, NOT order-independent: a late event is inserted by timestamp and judged only against the events
 *   before it, but the events after it are not re-scored. The same events can therefore get different decisions
 *   depending on arrival order (e.g. a password reset that arrives after the payment it should have flagged);
 * - bounded per account (500 events / 30 days) and in account count (10 000); accounts are never evicted.
 */
@Service
public class TriageService {
    static final int MAX_EVENTS = 500;
    static final int MAX_ACCOUNTS = 10_000;
    static final Duration RETENTION = Duration.ofDays(30);
    static final Duration MAX_FUTURE = Duration.ofMinutes(5);

    public static class RejectedEventException extends RuntimeException {
        public RejectedEventException(String reason) {
            super(reason);
        }
    }

    private static final class Account {
        final List<Event> events = new ArrayList<>();
        final Map<String, Triage> results = new HashMap<>();
    }

    private static final Comparator<Event> ORDER = Comparator.comparing(Event::at).thenComparing(Event::id);

    private final RiskEngine engine;
    private final Clock clock;
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    public TriageService(RiskEngine engine, Clock clock) {
        this.engine = engine;
        this.clock = clock;
    }

    public Triage process(Event e) {
        Instant now = clock.instant();
        if (e.at().isAfter(now.plus(MAX_FUTURE))) throw new RejectedEventException("event_in_future");
        if (e.at().isBefore(now.minus(RETENTION))) throw new RejectedEventException("event_too_old");

        Account acc = accounts.get(e.accountId());
        if (acc == null) {
            if (accounts.size() >= MAX_ACCOUNTS) throw new RejectedEventException("account_limit");
            acc = accounts.computeIfAbsent(e.accountId(), k -> new Account());
        }
        synchronized (acc) {
            Triage seen = acc.results.get(e.id());
            if (seen != null) return seen;

            int idx = 0;
            while (idx < acc.events.size() && ORDER.compare(acc.events.get(idx), e) < 0) idx++;
            List<Event> prior = List.copyOf(acc.events.subList(0, idx));
            // Only events that were themselves ALLOWed define "normal" for the account (devices, amounts, home country).
            Map<String, Triage> results = acc.results;
            List<Event> baseline = prior.stream().filter(p -> {
                Triage r = results.get(p.id());
                return r != null && r.decision() == Triage.Decision.ALLOW;
            }).toList();
            Triage result = engine.score(e, prior, baseline);

            acc.events.add(idx, e);
            acc.results.put(e.id(), result);
            Instant newest = acc.events.get(acc.events.size() - 1).at();
            while (acc.events.size() > MAX_EVENTS || acc.events.get(0).at().isBefore(newest.minus(RETENTION))) {
                Event dropped = acc.events.remove(0);
                acc.results.remove(dropped.id());
            }
            return result;
        }
    }

    public int accountCount() {
        return accounts.size();
    }
}
