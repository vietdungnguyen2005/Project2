package dev.vmarket.migration;

import java.util.UUID;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ImportBatchConfiguration {
    @Bean
    Job legacyCatalogImportJob(JobRepository jobRepository, Step applyLegacyCatalogStep) {
        return new JobBuilder("legacyCatalogImportJob", jobRepository)
                .start(applyLegacyCatalogStep)
                .build();
    }

    @Bean
    Step applyLegacyCatalogStep(
            JobRepository jobRepository, PlatformTransactionManager transactionManager, ImportChunkService chunks) {
        return new StepBuilder("applyLegacyCatalogStep", jobRepository)
                .tasklet(
                        (contribution, context) -> {
                            var jobId = UUID.fromString((String)
                                    context.getStepContext().getJobParameters().get("jobId"));
                            while (chunks.applyNextChunk(jobId) > 0) {
                                // Each chunk commits independently so a failed execution can resume safely.
                            }
                            return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                        },
                        transactionManager)
                .build();
    }
}
