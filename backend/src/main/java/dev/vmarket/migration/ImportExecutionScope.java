package dev.vmarket.migration;

import org.springframework.stereotype.Component;

/** Explicit synchronous Batch scope; deliberately not inherited by arbitrary background threads. */
@Component
public class ImportExecutionScope {
    private final ThreadLocal<ImportWorkClaim> current = new ThreadLocal<>();

    public ImportWorkClaim required() {
        var claim = current.get();
        if (claim == null) throw new ImportConflictException("Import metadata writes require a current worker scope.");
        return claim;
    }

    public void run(ImportWorkClaim claim, Runnable operation) {
        if (current.get() != null) throw new IllegalStateException("Nested import worker scopes are not supported.");
        current.set(claim);
        try {
            operation.run();
        } finally {
            current.remove();
        }
    }
}
