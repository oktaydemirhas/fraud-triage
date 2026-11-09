package dev.oktay.fraud.llm;

/** Anything that turns (system, user) into a JSON string; tests plug in a fake. */
public interface LlmClient {
    String completeJson(String system, String user);
}
