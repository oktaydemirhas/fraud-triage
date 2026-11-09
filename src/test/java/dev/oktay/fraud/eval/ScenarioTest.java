package dev.oktay.fraud.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oktay.fraud.eval.Scenarios.Scenario;
import dev.oktay.fraud.model.Triage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Deterministic scenario eval: no LLM, no key. `scenarios.json` is the dev set, `holdout.json` was written before the first run. */
class ScenarioTest {
    @Test
    void devSet() throws Exception {
        assertSet("scenarios", true);
    }

    @Test
    void holdoutSet() throws Exception {
        assertSet("holdout", true);
    }

    private void assertSet(String name, boolean enforce) throws Exception {
        List<Scenario> all = Scenarios.load(name);
        List<String> failures = new ArrayList<>();
        for (Scenario s : all) {
            Triage t = Scenarios.run(s);
            List<String> codes = Scenarios.codes(t);
            List<String> problems = new ArrayList<>();
            if (!t.decision().name().equals(s.expect().decision())) problems.add("decision " + t.decision() + " (score " + t.score() + "), expected " + s.expect().decision());
            if (s.expect().codes() != null && !codes.containsAll(s.expect().codes())) problems.add("codes " + codes + " missing " + s.expect().codes());
            if (s.expect().notCodes() != null) s.expect().notCodes().stream().filter(codes::contains).forEach(c -> problems.add("unexpected " + c));
            if (!problems.isEmpty()) failures.add(s.id() + " [" + s.note() + "]: " + String.join("; ", problems));
        }
        System.out.printf("=== fraud scenarios: %s === %d/%d passed%n", name, all.size() - failures.size(), all.size());
        failures.forEach(f -> System.out.println("FAIL " + f));
        if (enforce) assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
