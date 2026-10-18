package dev.vmarket.migration;

public class ImportConflictException extends RuntimeException {
    public ImportConflictException(String message) {
        super(message);
    }
}
