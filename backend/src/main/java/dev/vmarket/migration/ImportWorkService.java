package dev.vmarket.migration;

import java.sql.SQLException;
import java.util.UUID;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable work ownership. All control-plane transactions lock job before work, as chunks do. */
@Service
public class ImportWorkService {
    private static final int LEASE_SECONDS = 60;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public ImportWorkService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public ImportWorkClaim claim(UUID jobId) {
        return acquire(jobId, false);
    }

    public ImportWorkClaim claimNext() {
        return transactions.execute(transaction -> {
            var next = jdbc.sql("""
                    SELECT j.id FROM migration_job j WHERE j.status='STAGED' AND EXISTS (
                        SELECT 1 FROM import_work_item w WHERE w.job_id=j.id AND w.state='READY' AND w.dispatch_requested=TRUE
                    ) ORDER BY j.created_at,j.id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                    """).query(UUID.class).optional();
            if (next.isEmpty()) return null;
            // The candidate statement may have observed an older work snapshot before obtaining the job lock.
            // Re-read with a new READ COMMITTED statement while holding the shared job->work lock protocol.
            boolean ready = jdbc.sql("""
                    SELECT state='READY' AND dispatch_requested=TRUE FROM import_work_item
                    WHERE job_id=:job FOR UPDATE
                    """)
                    .param("job", next.get())
                    .query(Boolean.class)
                    .optional()
                    .orElse(false);
            return ready ? claim(next.get()) : null;
        });
    }

    /** Ownership primitive only. Automatic recovery must use ImportRecoveryService to reconcile Batch metadata too. */
    public ImportWorkClaim takeOverExpired(UUID jobId) {
        return acquire(jobId, true);
    }

    private ImportWorkClaim acquire(UUID jobId, boolean recovery) {
        try {
            return transactions.execute(transaction -> {
                lockJob(jobId, true);
                UUID token = UUID.randomUUID();
                var generation = jdbc.sql("""
                        UPDATE import_work_item SET state='CLAIMED',claim_token=:token,generation=generation+1,
                            lease_until=clock_timestamp()+(:leaseSeconds * INTERVAL '1 second'),updated_at=clock_timestamp()
                        WHERE job_id=:job AND ((:recovery=FALSE AND state='READY')
                            OR (:recovery=TRUE AND state='CLAIMED' AND lease_until<=clock_timestamp()))
                        RETURNING generation
                        """)
                        .param("job", jobId)
                        .param("token", token)
                        .param("recovery", recovery)
                        .param("leaseSeconds", LEASE_SECONDS)
                        .query(Long.class)
                        .optional()
                        .orElseThrow(() -> new ImportConflictException("The work is not ready or has a live owner."));
                jdbc.sql("UPDATE migration_job SET status='RUNNING',updated_at=NOW() WHERE id=:job")
                        .param("job", jobId)
                        .update();
                return new ImportWorkClaim(jobId, token, generation);
            });
        } catch (DataAccessException conflict) {
            if (conflict instanceof CannotAcquireLockException
                    || (conflict.getMostSpecificCause() instanceof SQLException sql
                            && "55P03".equals(sql.getSQLState())))
                throw new ImportConflictException("The work is locked by an active transaction.");
            throw conflict;
        }
    }

    /** Resolve only this execution's generation; never adopt a newer owner's token. */
    public ImportWorkClaim resolve(UUID jobId, long generation) {
        UUID token = jdbc.sql("""
                SELECT claim_token FROM import_work_item WHERE job_id=:job AND state='CLAIMED'
                    AND generation=:generation AND lease_until>clock_timestamp()
                """)
                .param("job", jobId)
                .param("generation", generation)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new ImportConflictException("The execution no longer owns this generation."));
        return new ImportWorkClaim(jobId, token, generation);
    }

    /** The caller must already hold the job row lock. Ownership stays locked until business commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireOwner(ImportWorkClaim claim) {
        if (claim == null || claim.jobId() == null || claim.token() == null)
            throw new ImportConflictException("A current worker claim is required.");
        boolean current = jdbc.sql("""
                SELECT state='CLAIMED' AND claim_token=:token AND generation=:generation
                    AND lease_until>clock_timestamp()
                FROM import_work_item WHERE job_id=:job FOR UPDATE
                """)
                .param("job", claim.jobId())
                .param("token", claim.token())
                .param("generation", claim.generation())
                .query(Boolean.class)
                .optional()
                .orElse(false);
        if (!current) throw new ImportConflictException("The worker claim is stale, expired or finished.");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void renew(ImportWorkClaim claim) {
        int renewed = jdbc.sql("""
                UPDATE import_work_item SET lease_until=clock_timestamp()+(:leaseSeconds * INTERVAL '1 second'),updated_at=clock_timestamp()
                WHERE job_id=:job AND state='CLAIMED' AND claim_token=:token AND generation=:generation
                    AND lease_until>clock_timestamp()
                """)
                .param("job", claim.jobId())
                .param("token", claim.token())
                .param("generation", claim.generation())
                .param("leaseSeconds", LEASE_SECONDS)
                .update();
        if (renewed != 1) throw new ImportConflictException("The worker lost ownership before the chunk completed.");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(ImportWorkClaim claim) {
        int finished = jdbc.sql("""
                UPDATE import_work_item SET state='FINISHED',claim_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
                WHERE job_id=:job AND state='CLAIMED' AND claim_token=:token AND generation=:generation
                    AND lease_until>clock_timestamp()
                """)
                .param("job", claim.jobId())
                .param("token", claim.token())
                .param("generation", claim.generation())
                .update();
        if (finished != 1) throw new ImportConflictException("The worker lost ownership before finalization.");
    }

    public boolean failIfOwned(ImportWorkClaim claim) {
        return Boolean.TRUE.equals(transactions.execute(transaction -> {
            lockJob(claim.jobId(), false);
            int failed = jdbc.sql("""
                    UPDATE import_work_item SET state='FAILED',claim_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
                    WHERE job_id=:job AND state='CLAIMED' AND claim_token=:token AND generation=:generation
                        AND lease_until>clock_timestamp()
                    """)
                    .param("job", claim.jobId())
                    .param("token", claim.token())
                    .param("generation", claim.generation())
                    .update();
            if (failed != 1) return false;
            jdbc.sql("UPDATE migration_job SET status='FAILED',updated_at=NOW() WHERE id=:job")
                    .param("job", claim.jobId())
                    .update();
            return true;
        }));
    }

    public void completeAfterExecution(ImportWorkClaim claim) {
        transactions.executeWithoutResult(transaction -> {
            lockJob(claim.jobId(), false);
            requireOwner(claim);
            boolean complete = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM migration_job WHERE id=:id AND status IN ('COMPLETED','COMPLETED_WITH_ERRORS'))
                    AND NOT EXISTS(SELECT 1 FROM staged_import_row WHERE job_id=:id AND valid=TRUE AND applied_at IS NULL)
                    AND EXISTS(SELECT 1 FROM batch_job_execution e
                      JOIN batch_job_execution_params j ON j.job_execution_id=e.job_execution_id AND j.parameter_name='jobId'
                      JOIN batch_job_execution_params g ON g.job_execution_id=e.job_execution_id AND g.parameter_name='claimGeneration'
                      WHERE j.parameter_value=:job AND g.parameter_value=:generation AND e.status='COMPLETED')
                    """)
                    .param("id", claim.jobId())
                    .param("job", claim.jobId().toString())
                    .param("generation", Long.toString(claim.generation()))
                    .query(Boolean.class)
                    .single();
            if (!complete) throw new ImportConflictException("Batch execution is not durably complete.");
            finish(claim);
        });
    }

    private void lockJob(UUID jobId, boolean noWait) {
        jdbc.sql("SELECT id FROM migration_job WHERE id=:job FOR UPDATE" + (noWait ? " NOWAIT" : ""))
                .param("job", jobId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new InvalidImportException("Migration job does not exist."));
    }
}
