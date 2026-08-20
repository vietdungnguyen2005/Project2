package dev.vmarket.reconciliation;

public class InvalidReconciliationException extends RuntimeException {
    public InvalidReconciliationException(String message) {
        super(message);
    }
}
