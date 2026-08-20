package dev.vmarket.migration;

public class InvalidImportException extends RuntimeException {
    public InvalidImportException(String message) {
        super(message);
    }
}
