package dev.vmarket.migration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collection;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Guards standard JDBC repository writes and business ownership in the SAME PostgreSQL transaction. */
public final class ImportRepositoryFence {
    private ImportRepositoryFence() {}

    public static JobRepository wrap(
            JobRepository delegate,
            ImportExecutionScope scope,
            ImportWorkService work,
            JdbcClient jdbc,
            PlatformTransactionManager manager) {
        var transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(15);
        return (JobRepository) Proxy.newProxyInstance(
                JobRepository.class.getClassLoader(), new Class<?>[] {JobRepository.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (method.getDeclaringClass() == Object.class
                            || name.startsWith("get")
                            || name.startsWith("find")
                            || name.startsWith("is")) {
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    }
                    var claim = scope.required();
                    if (name.startsWith("delete"))
                        throw new ImportConflictException("Import execution history cannot be deleted by a worker.");
                    if (args == null || !matchesAny(args, claim))
                        throw new ImportConflictException(
                                "Repository mutation does not match this worker's job and generation.");
                    try {
                        return transactions.execute(status -> {
                            jdbc.sql("SET LOCAL lock_timeout='3s'").update();
                            jdbc.sql("SET LOCAL statement_timeout='10s'").update();
                            jdbc.sql("SELECT id FROM migration_job WHERE id=:job FOR UPDATE")
                                    .param("job", claim.jobId())
                                    .query(java.util.UUID.class)
                                    .single();
                            work.requireOwner(claim);
                            try {
                                var result = method.invoke(delegate, args);
                                work.requireOwner(claim);
                                return result;
                            } catch (InvocationTargetException failure) {
                                throw new InvocationFailure(failure.getCause());
                            } catch (IllegalAccessException failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
                    } catch (InvocationFailure failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static boolean matchesAny(Object[] args, ImportWorkClaim claim) {
        for (var arg : args) {
            if (arg instanceof JobParameters parameters) return matches(parameters, claim);
            if (arg instanceof JobExecution execution) return matches(execution.getJobParameters(), claim);
            if (arg instanceof StepExecution step)
                return matches(step.getJobExecution().getJobParameters(), claim);
            if (arg instanceof Collection<?> entries)
                return !entries.isEmpty()
                        && entries.stream()
                                .allMatch(item -> item instanceof StepExecution step
                                        && matches(step.getJobExecution().getJobParameters(), claim));
        }
        return false;
    }

    private static boolean matches(JobParameters parameters, ImportWorkClaim claim) {
        return claim.jobId().toString().equals(parameters.getString("jobId"))
                && Long.valueOf(claim.generation()).equals(parameters.getLong("claimGeneration"));
    }

    private static final class InvocationFailure extends RuntimeException {
        InvocationFailure(Throwable cause) {
            super(cause);
        }
    }
}
