package dev.vmarket.migration;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded recovery shares the same job/work locks and transaction as fenced metadata writers. */
@Service
public class ImportRecoveryService {
    private static final int MAX_RECOVERIES = 3;
    private final JdbcClient jdbc;
    private final JobExplorer explorer;
    private final JobRepository metadata;
    private final TransactionTemplate transactions;

    public ImportRecoveryService(
            JdbcClient jdbc,
            JobExplorer explorer,
            @Qualifier("importMetadataRepository") JobRepository metadata,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.explorer = explorer;
        this.metadata = metadata;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(15);
    }

    public boolean recoverOne() {
        return Boolean.TRUE.equals(transactions.execute(transaction -> {
            jdbc.sql("SET LOCAL lock_timeout='3s'").update();
            jdbc.sql("SET LOCAL statement_timeout='10s'").update();
            var candidate = jdbc.sql("""
                    SELECT j.id FROM migration_job j WHERE EXISTS (
                      SELECT 1 FROM import_work_item w WHERE w.job_id=j.id AND w.dispatch_requested=TRUE
                        AND w.state='CLAIMED' AND w.lease_until<=clock_timestamp())
                    ORDER BY j.updated_at,j.id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                    """).query(UUID.class).optional();
            if (candidate.isEmpty()) return false;
            UUID job = candidate.get();
            var ownership =
                    jdbc.sql("""
                    SELECT generation,recovery_count FROM import_work_item WHERE job_id=:job
                      AND state='CLAIMED' AND dispatch_requested=TRUE AND lease_until<=clock_timestamp() FOR UPDATE
                    """).param("job", job).query(Ownership.class).optional();
            if (ownership.isEmpty()) return false;
            var owner = ownership.get();
            var latest =
                    jdbc.sql("""
                    SELECT e.job_execution_id FROM batch_job_execution e
                    JOIN batch_job_instance i ON i.job_instance_id=e.job_instance_id
                    JOIN batch_job_execution_params p ON p.job_execution_id=e.job_execution_id
                    WHERE i.job_name='legacyCatalogImportJob' AND p.parameter_name='jobId' AND p.parameter_value=:job
                    ORDER BY e.job_execution_id DESC LIMIT 1
                    """).param("job", job.toString()).query(Long.class).optional();
            String state = "READY";
            String reason = "EXPIRED_OWNER_REQUEUED";
            if (owner.recoveryCount() >= MAX_RECOVERIES) {
                state = "BLOCKED";
                reason = "RECOVERY_LIMIT_REQUIRES_REVIEW";
            } else if (latest.isPresent()) {
                var execution = explorer.getJobExecution(latest.get());
                Long executionGeneration =
                        execution == null ? null : execution.getJobParameters().getLong("claimGeneration");
                boolean priorRetiredExecution = executionGeneration != null
                        && executionGeneration < owner.generation()
                        && (execution.getStatus() == BatchStatus.FAILED
                                || execution.getStatus() == BatchStatus.STOPPED);
                if (execution == null
                        || executionGeneration == null
                        || (!priorRetiredExecution && executionGeneration != owner.generation())
                        || execution.getStatus() == BatchStatus.UNKNOWN
                        || execution.getStatus() == BatchStatus.ABANDONED
                        || execution.getStepExecutions().stream()
                                .anyMatch(step -> step.getStatus() == BatchStatus.UNKNOWN)) {
                    state = "BLOCKED";
                    reason = "METADATA_MISMATCH_REQUIRES_REVIEW";
                } else if (priorRetiredExecution) {
                    reason = "EXPIRED_BEFORE_NEW_EXECUTION";
                } else if (execution.getStatus() == BatchStatus.COMPLETED) {
                    boolean pending = jdbc.sql(
                                    "SELECT EXISTS(SELECT 1 FROM staged_import_row WHERE job_id=:job AND valid=TRUE AND applied_at IS NULL)")
                            .param("job", job)
                            .query(Boolean.class)
                            .single();
                    String business = jdbc.sql("SELECT status FROM migration_job WHERE id=:job")
                            .param("job", job)
                            .query(String.class)
                            .single();
                    if (!pending && (business.equals("COMPLETED") || business.equals("COMPLETED_WITH_ERRORS"))) {
                        state = "FINISHED";
                        reason = "COMPLETED_METADATA_ACKNOWLEDGED";
                    } else {
                        state = "BLOCKED";
                        reason = "COMPLETED_METADATA_DISAGREES";
                    }
                } else if (execution.getStatus().isRunning()) {
                    // This raw standard repository joins this recovery transaction. Never call the worker-fenced proxy
                    // here.
                    for (var step : execution.getStepExecutions()) {
                        if (step.getStatus().isRunning()) {
                            step.setStatus(BatchStatus.FAILED);
                            step.setEndTime(LocalDateTime.now());
                            step.setExitStatus(
                                    ExitStatus.FAILED.addExitDescription("WORKER_LEASE_EXPIRED: fenced recovery"));
                            metadata.update(step);
                        }
                    }
                    execution.setStatus(BatchStatus.FAILED);
                    execution.setEndTime(LocalDateTime.now());
                    execution.setExitStatus(
                            ExitStatus.FAILED.addExitDescription("WORKER_LEASE_EXPIRED: fenced recovery"));
                    metadata.update(execution);
                }
            }
            // Generation revocation, metadata retirement and dispatch intent commit atomically.
            jdbc.sql("""
                    UPDATE import_work_item SET generation=generation+1,state=:state,claim_token=NULL,lease_until=NULL,
                      recovery_count=recovery_count+1,last_recovered_at=clock_timestamp(),last_recovery_reason=:reason,updated_at=clock_timestamp()
                    WHERE job_id=:job
                    """)
                    .param("state", state)
                    .param("reason", reason)
                    .param("job", job)
                    .update();
            if (!state.equals("FINISHED"))
                jdbc.sql("UPDATE migration_job SET status=:state,updated_at=NOW() WHERE id=:job")
                        .param("state", state.equals("READY") ? "STAGED" : "FAILED")
                        .param("job", job)
                        .update();
            return true;
        }));
    }

    private record Ownership(long generation, int recoveryCount) {}
}
