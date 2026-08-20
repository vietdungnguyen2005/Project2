package dev.vmarket.ops;

public class InvalidFulfillmentTransitionException extends RuntimeException {
    public InvalidFulfillmentTransitionException(String message) {
        super(message);
    }
}
