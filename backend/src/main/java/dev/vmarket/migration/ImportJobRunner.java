package dev.vmarket.migration;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Service;

/** Executes an already claimed durable item; never claims ownership from inside a Batch task. */
@Service
public class ImportJobRunner {
    private final JobLauncher launcher;
    private final Job job;
    private final ImportWorkService work;
    private final ImportExecutionScope scope;

    public ImportJobRunner(
            @org.springframework.beans.factory.annotation.Qualifier("fencedImportLauncher") JobLauncher launcher,
            Job legacyCatalogImportJob,
            ImportWorkService work,
            ImportExecutionScope scope) {
        this.launcher = launcher;
        this.job = legacyCatalogImportJob;
        this.work = work;
        this.scope = scope;
    }

    public void execute(ImportWorkClaim claim) {
        scope.run(claim, () -> executeScoped(claim));
    }

    private void executeScoped(ImportWorkClaim claim) {
        boolean batchCompleted = false;
        try {
            var execution = launcher.run(
                    job,
                    new JobParametersBuilder()
                            .addString("jobId", claim.jobId().toString())
                            .addLong("claimGeneration", claim.generation(), false)
                            .toJobParameters());
            if (execution.getStatus() == BatchStatus.FAILED || execution.getStatus() == BatchStatus.STOPPED)
                work.failIfOwned(claim);
            else if (execution.getStatus() == BatchStatus.COMPLETED) {
                batchCompleted = true;
                work.completeAfterExecution(claim);
            }
        } catch (Exception exception) {
            if (!batchCompleted) work.failIfOwned(claim);
            throw new ImportExecutionException("Legacy import job failed", exception);
        }
    }
}
