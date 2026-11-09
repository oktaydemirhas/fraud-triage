package dev.oktay.fraud.explain;

import dev.oktay.fraud.llm.LlmClient;
import dev.oktay.fraud.model.Triage;
import dev.oktay.fraud.model.Triage.Decision;
import dev.oktay.fraud.model.Triage.Signal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the analyst note. The decision and score are fixed before this runs; the LLM only phrases them.
 * Two structural defences instead of prompt rules:
 *  1. The prompt carries only structured values (codes, weights, numbers). Free text such as the merchant name is never sent,
 *     so there is nothing to inject through.
 *  2. The reply is checked in code: every number must come from the signals, and it may not contradict the decision.
 *     Anything else falls back to a template built from the same signals, so the API always returns a faithful note.
 */
@Component
public class Explainer {
    public enum Source { LLM, TEMPLATE }

    public record Explanation(String text, Source source, String rejectedBecause) {}

    static final String SYSTEM = """
            Sen bir dolandırıcılık analisti asistanısın. Sana bir işlemin kararı, puanı ve tetiklenen sinyaller verilir.
            Karar ve puanı sen değiştiremezsin; yalnızca sinyalleri analist için 2-3 cümleyle Türkçe açıkla.
            YALNIZCA verilen sayıları kullan, yeni sayı üretme veya yuvarlama yapma. Kişi, firma veya belgede olmayan hiçbir şey ekleme.
            Yanıtı {"summary": "..."} biçiminde JSON olarak ver.""";

    private static final Logger log = LoggerFactory.getLogger(Explainer.class);
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final int MAX_CHARS = 450;

    private final LlmClient llm;
    private final JsonMapper json = JsonMapper.builder().build();

    public Explainer(LlmClient llm) {
        this.llm = llm;
    }

    /** Same event id -> same note, so a client retrying the same event cannot trigger repeated paid LLM calls. Bounded LRU. */
    private final Map<String, Explanation> cache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Explanation> eldest) {
            return size() > 2000;
        }
    });

    public Explanation explain(Triage t) {
        String key = t.event().accountId() + "|" + t.event().id();
        Explanation cached = cache.get(key);
        if (cached != null) return cached;
        Explanation fresh = explainUncached(t);
        // A fallback caused by an outage is not cached: the next retry should get a chance at the real note.
        if (!"llm_unavailable".equals(fresh.rejectedBecause())) cache.put(key, fresh);
        return fresh;
    }

    private Explanation explainUncached(Triage t) {
        String reason;
        try {
            String reply = llm.completeJson(SYSTEM, json.writeValueAsString(promptData(t)));
            String summary = json.readTree(reply).path("summary").asString("").strip();
            reason = check(summary, t);
            if (reason == null) return new Explanation(summary, Source.LLM, null);
        } catch (RuntimeException e) {
            log.warn("explanation unavailable: {}", e.getMessage());
            reason = "llm_unavailable";
        }
        return new Explanation(template(t), Source.TEMPLATE, reason);
    }

    /** Everything the model sees. No ids, no merchant, no country names beyond what a signal needs. */
    static Map<String, Object> promptData(Triage t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decision", t.decision().name());
        m.put("score", t.score());
        m.put("eventType", t.event().type().name());
        m.put("signals", t.signals().stream().map(s -> Map.of("code", s.code(), "weight", s.weight(), "evidence", s.evidence())).toList());
        return m;
    }

    /** Returns null when the note is acceptable, otherwise the reason it was rejected. */
    static String check(String text, Triage t) {
        if (text.isBlank()) return "empty";
        if (text.length() > MAX_CHARS) return "too_long";
        Set<String> allowed = allowedNumbers(t);
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String n = canonical(m.group());
            if (!allowed.contains(n)) return "unsupported_number:" + n;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (t.decision() == Decision.REVIEW && (lower.contains("engellen") || lower.contains("bloke"))) return "contradicts_decision";
        if (t.decision() == Decision.BLOCK && (lower.contains("güvenli") || lower.contains("onaylan"))) return "contradicts_decision";
        return null;
    }

    private static Set<String> allowedNumbers(Triage t) {
        Set<String> out = new HashSet<>();
        add(out, t.score());
        for (Signal s : t.signals()) {
            add(out, s.weight());
            s.evidence().values().forEach(v -> add(out, v));
        }
        return out;
    }

    private static void add(Set<String> out, Object v) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            out.add(canonical(plain(d)));
            out.add(canonical(plain(Math.round(d))));
        }
    }

    private static String plain(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** "6.000" and "6,000" (thousands) -> "6000"; "40,2" -> "40.2"; "5.0" -> "5". */
    static String canonical(String raw) {
        String s = raw;
        if (s.matches("\\d{1,3}([.,]\\d{3})+")) s = s.replaceAll("[.,]", "");
        s = s.replace(',', '.');
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    static String template(Triage t) {
        List<String> parts = new ArrayList<>();
        for (Signal s : t.signals()) parts.add(describe(s));
        String head = "Risk puanı " + t.score() + " (" + (t.decision() == Decision.BLOCK ? "engelle" : t.decision() == Decision.REVIEW ? "incele" : "izin ver") + ").";
        return parts.isEmpty() ? head : head + " " + String.join(" ", parts);
    }

    private static String describe(Signal s) {
        Map<String, Object> e = s.evidence();
        return switch (s.code()) {
            case "VELOCITY" -> fmt(e.get("windowMinutes")) + " dakikada " + fmt(e.get("payments")) + " ödeme yapıldı.";
            case "BRUTE_FORCE_LOGIN" -> fmt(e.get("windowMinutes")) + " dakikada " + fmt(e.get("failedLogins")) + " başarısız giriş denemesi var.";
            case "IMPOSSIBLE_TRAVEL" -> "Önceki işlem farklı bir ülkeden " + fmt(e.get("minutesApart")) + " dakika önce yapılmış.";
            case "AMOUNT_ANOMALY" -> "Tutar (" + fmt(e.get("amountTry")) + " TL) hesabın olağan harcamasından belirgin yüksek (z=" + fmt(e.get("zScore")) + ").";
            case "NEW_DEVICE_HIGH_AMOUNT" -> "Yeni bir cihazdan " + fmt(e.get("amountTry")) + " TL ödeme yapıldı.";
            case "ACCOUNT_CHANGE_NEW_DEVICE" -> "Hesap bilgisi yeni bir cihazdan değiştirildi.";
            case "ACCOUNT_TAKEOVER_PATTERN" -> "Parola/adres değişikliğinden " + fmt(e.get("minutesAfterChange")) + " dakika sonra " + fmt(e.get("amountTry")) + " TL ödeme yapıldı.";
            case "FOREIGN_COUNTRY" -> "İşlem olağan ülke (" + e.get("homeCountry") + ") dışından (" + e.get("country") + ") yapıldı.";
            default -> s.code() + " sinyali tetiklendi.";
        };
    }

    private static String fmt(Object v) {
        return v instanceof Number n ? plain(n.doubleValue()) : String.valueOf(v);
    }
}
