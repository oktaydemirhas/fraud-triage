package dev.oktay.fraud.eval;

import dev.oktay.fraud.engine.RiskEngine;
import dev.oktay.fraud.engine.TriageService;
import dev.oktay.fraud.model.Event;
import dev.oktay.fraud.model.Triage;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Loads scenario files and replays them through the real service. Times are minutes relative to BASE. */
final class Scenarios {
    private Scenarios() {}

    record Ev(Double t, String type, Double amount, String country, String device, Integer x, Double step) {}

    record Expect(String decision, List<String> codes, List<String> notCodes) {}

    record Scenario(String id, String note, List<Ev> events, Expect expect) {}

    static final Instant BASE = Instant.parse("2026-11-04T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(BASE.plusSeconds(3600), ZoneId.of("Europe/Istanbul"));

    static List<Scenario> load(String name) throws Exception {
        try (InputStream in = Scenarios.class.getResourceAsStream("/eval/" + name + ".json")) {
            return JsonMapper.builder().build().readValue(in, new TypeReference<List<Scenario>>() {});
        }
    }

    /** Replays every event in order through a fresh service and returns the triage of the last one. */
    static Triage run(Scenario s) {
        var service = new TriageService(new RiskEngine(), CLOCK);
        Triage last = null;
        int n = 0;
        for (Ev ev : s.events()) {
            int count = ev.x() == null ? 1 : ev.x();
            for (int i = 0; i < count; i++) {
                double minutes = ev.t() + i * (ev.step() == null ? 0 : ev.step());
                String country = ev.country() == null ? "TR" : "none".equals(ev.country()) ? null : ev.country();
                Event e = new Event("e" + (++n), "acc", Event.Type.valueOf(ev.type() == null ? "PAYMENT" : ev.type()),
                        BigDecimal.valueOf(ev.amount() == null ? 200 : ev.amount()), country,
                        ev.device() == null ? "D1" : ev.device(), "test merchant", BASE.plusSeconds(Math.round(minutes * 60)));
                last = service.process(e);
            }
        }
        return last;
    }

    static List<String> codes(Triage t) {
        List<String> out = new ArrayList<>();
        t.signals().forEach(sig -> out.add(sig.code()));
        return out;
    }
}
