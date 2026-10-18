package dev.vmarket.shared;

/** Application byte budgets; the BFF enforces the same values before forwarding. */
public final class RequestBodyLimits {
    public static final int CSV_MAX_BYTES = 5_000_000;
    public static final int JSON_MAX_BYTES = 65_536;

    private RequestBodyLimits() {}
}
