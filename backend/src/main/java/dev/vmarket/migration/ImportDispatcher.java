package dev.vmarket.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One bounded durable claim per tick; no in-memory queue or unbounded executor. */
@Component
public class ImportDispatcher implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(ImportDispatcher.class);
    private final ImportWorkService work;
    private final ImportJobRunner runner;
    private final boolean enabled;
    private final ImportRecoveryService recovery;

    public ImportDispatcher(
            ImportWorkService work,
            ImportJobRunner runner,
            ImportRecoveryService recovery,
            @Value("${vmarket.import-worker.enabled:true}") boolean enabled) {
        this.work = work;
        this.runner = runner;
        this.enabled = enabled;
        this.recovery = recovery;
    }

    @Scheduled(initialDelay = 2000, fixedDelay = 1000)
    public void tick() {
        if (!enabled) return;
        try {
            run();
        } catch (RuntimeException failure) {
            // Never log claims, request bodies or arbitrary exception messages.
            LOG.warn(
                    "Import dispatch failed ({}); durable work state requires inspection",
                    failure.getClass().getSimpleName());
        }
    }

    @Override
    public void run() {
        try {
            recovery.recoverOne();
        } catch (RuntimeException failure) {
            // Recovery rolls back independently. Do not starve unrelated READY work on a damaged candidate.
            LOG.warn(
                    "Import recovery failed ({}); candidate requires inspection",
                    failure.getClass().getSimpleName());
        }
        var claim = work.claimNext();
        if (claim != null) runner.execute(claim);
    }
}
