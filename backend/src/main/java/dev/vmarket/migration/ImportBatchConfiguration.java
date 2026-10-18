package dev.vmarket.migration;

import java.util.UUID;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.observability.DefaultBatchJobObservationConvention;
import org.springframework.batch.core.observability.DefaultBatchStepObservationConvention;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

@Configuration
public class ImportBatchConfiguration {
    @Bean
    Job legacyCatalogImportJob(
            @org.springframework.beans.factory.annotation.Qualifier("fencedImportRepository") JobRepository jobRepository,
            Step applyLegacyCatalogStep) {
        return new JobBuilder("legacyCatalogImportJob", jobRepository)
                // Batch 5.2 also emits legacy meters. Keep Observation timers in a distinct namespace.
                .observationConvention(new DefaultBatchJobObservationConvention() {
                    @Override
                    public String getName() {
                        return "vmarket.import.job";
                    }
                })
                .start(applyLegacyCatalogStep)
                .build();
    }

    @Bean
    Step applyLegacyCatalogStep(
            @org.springframework.beans.factory.annotation.Qualifier("fencedImportRepository") JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ImportChunkService chunks,
            ImportWorkService work) {
        return new StepBuilder("applyLegacyCatalogStep", jobRepository)
                .allowStartIfComplete(true)
                .observationConvention(new DefaultBatchStepObservationConvention() {
                    @Override
                    public String getName() {
                        return "vmarket.import.step";
                    }
                })
                .tasklet(
                        (contribution, context) -> {
                            var jobId = UUID.fromString((String)
                                    context.getStepContext().getJobParameters().get("jobId"));
                            var parameters = context.getStepContext().getJobParameters();
                            var claim = work.resolve(jobId, (Long) parameters.get("claimGeneration"));
                            return chunks.applyNextChunk(claim) > 0
                                    ? org.springframework.batch.repeat.RepeatStatus.CONTINUABLE
                                    : org.springframework.batch.repeat.RepeatStatus.FINISHED;
                        },
                        transactionManager)
                // Only the chunk service owns a business transaction; do not suspend an outer DB transaction.
                .transactionAttribute(new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED))
                .build();
    }
}
