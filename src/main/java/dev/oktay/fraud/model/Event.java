package dev.oktay.fraud.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One account event. `merchant` is free text typed by someone else, so it is stored for humans
 * but is never sent to the LLM (see Explainer): the model only receives structured signal values.
 */
public record Event(
        @NotBlank @Size(max = 64) String id,
        @NotBlank @Size(max = 64) String accountId,
        @NotNull Type type,
        @DecimalMin("0") @DecimalMax("10000000") BigDecimal amount,
        @Pattern(regexp = "[A-Za-z]{2,3}") String country,
        @Size(max = 64) String deviceId,
        @Size(max = 100) String merchant,
        @NotNull Instant at) {

    public enum Type { PAYMENT, LOGIN_OK, LOGIN_FAILED, ADDRESS_CHANGE, PASSWORD_RESET }

    public boolean isPayment() {
        return type == Type.PAYMENT;
    }

    public double amountValue() {
        return amount == null ? 0 : amount.doubleValue();
    }
}
