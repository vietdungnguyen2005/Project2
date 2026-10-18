package dev.vmarket.migration;

import javax.sql.DataSource;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;

@Configuration(proxyBeanMethods = false)
public class ImportRepositoryConfiguration {
    @Bean("importMetadataRepository")
    JobRepository metadataRepository(DataSource dataSource, PlatformTransactionManager manager) throws Exception {
        var factory = new JobRepositoryFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTransactionManager(manager);
        // The fencing boundary supplies REQUIRES_NEW plus the canonical job lock. The delegate MUST join it.
        factory.setValidateTransactionState(false);
        var attributes = new NameMatchTransactionAttributeSource();
        attributes.addTransactionalMethod("*", new DefaultTransactionAttribute());
        factory.setTransactionAttributeSource(attributes);
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    @Bean("fencedImportRepository")
    JobRepository fencedRepository(
            @Qualifier("importMetadataRepository") JobRepository delegate,
            ImportExecutionScope scope,
            ImportWorkService work,
            JdbcClient jdbc,
            PlatformTransactionManager manager) {
        return ImportRepositoryFence.wrap(delegate, scope, work, jdbc, manager);
    }

    @Bean("fencedImportLauncher")
    JobLauncher fencedLauncher(
            @Qualifier("fencedImportRepository") JobRepository repository,
            io.micrometer.core.instrument.MeterRegistry metrics)
            throws Exception {
        var launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);
        launcher.setTaskExecutor(new SyncTaskExecutor());
        launcher.setMeterRegistry(metrics);
        launcher.afterPropertiesSet();
        return launcher;
    }
}
