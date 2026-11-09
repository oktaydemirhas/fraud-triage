package dev.oktay.fraud.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oktay.fraud.engine.TriageService.RejectedEventException;
import dev.oktay.fraud.model.Event;
import dev.oktay.fraud.model.Event.Type;
import dev.oktay.fraud.model.Triage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TriageServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private final TriageService service = new TriageService(new RiskEngine(), Clock.fixed(NOW, ZoneId.of("UTC")));

    private static Event pay(String id, String account, long minutesAgo) {
        return new Event(id, account, Type.PAYMENT, BigDecimal.valueOf(100), "TR", "D1", "m", NOW.minusSeconds(minutesAgo * 60));
    }

    @Test
    void duplicateEventIdIsIdempotentAndNotCountedTwice() {
        for (int i = 1; i <= 4; i++) service.process(pay("p" + i, "a", 5 - i));
        Triage again = null;
        for (int i = 0; i < 10; i++) again = service.process(pay("p4", "a", 1));
        assertSame(service.process(pay("p4", "a", 1)), again);
        Triage fifth = service.process(pay("p5", "a", 0));
        assertEquals(5L, ((Number) fifth.signals().stream().filter(s -> s.code().equals("VELOCITY")).findFirst().orElseThrow().evidence().get("payments")).longValue());
    }

    @Test
    void lateEventIsJudgedOnlyAgainstEarlierEvents() {
        service.process(pay("newest", "b", 0));
        // Four older events arrive afterwards, newest first. Each is judged only against what happened before it,
        // so none of them reaches 5 payments in 10 minutes even though 5 exist once all have arrived.
        for (int i = 1; i <= 4; i++) assertFalse(service.process(pay("late" + i, "b", i)).hasCode("VELOCITY"));
        // An event after all of them sees the full, correctly ordered history.
        Triage next = service.process(pay("after", "b", -1));
        assertTrue(next.hasCode("VELOCITY"));
    }

    @Test
    void accountsDoNotInfluenceEachOther() {
        for (int i = 1; i <= 8; i++) service.process(pay("a" + i, "busy", 8 - i));
        Triage other = service.process(pay("o1", "quiet", 0));
        assertFalse(other.hasCode("VELOCITY"));
    }

    @Test
    void futureAndAncientEventsAreRejected() {
        Event future = new Event("f", "a", Type.PAYMENT, BigDecimal.TEN, "TR", "D1", "m", NOW.plusSeconds(600));
        assertEquals("event_in_future", assertThrows(RejectedEventException.class, () -> service.process(future)).getMessage());
        Event old = new Event("o", "a", Type.PAYMENT, BigDecimal.TEN, "TR", "D1", "m", NOW.minusSeconds(31L * 24 * 3600));
        assertEquals("event_too_old", assertThrows(RejectedEventException.class, () -> service.process(old)).getMessage());
    }

    @Test
    void historyIsBounded() {
        for (int i = 0; i < 700; i++) service.process(new Event("h" + i, "big", Type.LOGIN_OK, null, "TR", "D1", null, NOW.minusSeconds(700 - i)));
        // The oldest ids were evicted, so re-sending one is processed as a new event instead of failing.
        assertNotNull(service.process(new Event("h0", "big", Type.LOGIN_OK, null, "TR", "D1", null, NOW.minusSeconds(700))));
        assertEquals(1, service.accountCount());
    }
}
