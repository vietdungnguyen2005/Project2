package dev.vmarket.migration;

public class ImportExecutionException extends RuntimeException {
    public ImportExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
