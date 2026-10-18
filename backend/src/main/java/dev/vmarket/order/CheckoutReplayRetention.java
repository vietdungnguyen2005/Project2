package dev.vmarket.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Purges only expired encrypted response copies. Orders, key tombstones and tracking hashes remain. */
@Component
public class CheckoutReplayRetention implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(CheckoutReplayRetention.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public CheckoutReplayRetention(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(15);
    }

    @Override
    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void run() {
        try {
            transactions.executeWithoutResult(transaction -> {
                jdbc.sql("SET LOCAL lock_timeout='3s'").update();
                jdbc.sql("SET LOCAL statement_timeout='10s'").update();
                jdbc.sql("""
                        WITH expired AS (
                          SELECT id FROM customer_order WHERE tracking_replay_ciphertext IS NOT NULL
                            AND created_at<=clock_timestamp()-INTERVAL '24 hours'
                          ORDER BY created_at,id LIMIT 100 FOR UPDATE SKIP LOCKED
                        ) UPDATE customer_order SET tracking_replay_ciphertext=NULL WHERE id IN (SELECT id FROM expired)
                        """).update();
            });
        } catch (RuntimeException failure) {
            LOG.warn(
                    "Checkout replay cleanup failed ({}); retry on next tick",
                    failure.getClass().getSimpleName());
        }
    }
}
