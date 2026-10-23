package dev.vmarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@Sql(scripts = "/reset-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VMarketApplicationTest {
    @Test
    void supplierEnrichmentDoesNotChangeLegacyUnknownColumnCompatibility() {
        applicationContext
                .getBean(dev.vmarket.migration.LegacyImportService.class)
                .importCsv(
                        "extra.csv",
                        "UTF-8",
                        "sku,vendor_code,name,on_hand,price_minor,expected_product_version\nVM-001,NAMI,Legacy jacket,32,11000,-1\n"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(jdbc.sql("SELECT applied_rows FROM migration_job")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void supplierProfileMigrationPreservesLegacyHistoryAndMakesDefinitionsImmutable() {
        var configuration = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("supplier_upgrade")
                .defaultSchema("supplier_upgrade");
        configuration.target("13").load().migrate();
        jdbc.sql("""
                INSERT INTO supplier_upgrade.migration_job(id,source_name,file_checksum,encoding,status)
                VALUES('00000000-0000-0000-0000-000000000914','retained.csv',repeat('e',64),'UTF-8','COMPLETED')
                """).update();
        jdbc.sql("UPDATE supplier_upgrade.inventory_stock SET on_hand=25,version=9")
                .update();
        configuration.target("14").load().migrate();
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM supplier_upgrade.migration_job WHERE profile_id IS NULL AND profile_version IS NULL AND source_name='retained.csv'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM supplier_upgrade.inventory_stock WHERE on_hand=25 AND version=9")
                        .query(Integer.class)
                        .single())
                .isEqualTo(8);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                                "UPDATE supplier_upgrade.integration_profile SET encoding='UTF-8' WHERE profile_id='supplier-a'")
                        .update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.sql("DELETE FROM supplier_upgrade.integration_profile WHERE profile_id='supplier-a'")
                                .update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void profilesExposeRequiredFieldsAndApprovedTypeRangePolicy() throws Exception {
        mockMvc.perform(get("/api/ops/integration-profiles")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requiredFields[0]").value("sku"))
                .andExpect(jsonPath("$[0].validation.on_hand.max").value(1000000))
                .andExpect(jsonPath("$[0].normalization.name").value("TRIM"));
    }

    @Test
    void onboardingPreservesOperatorCsrfAndOpsSecretBoundariesAndBlocksUnsafeRestarts() throws Exception {
        String csv = "SKU,ProductName,AvailableQuantity\nVM-002,Desk,60\n";
        mockMvc.perform(get("/api/ops/integration-profiles")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/ops/onboarding/supplier-b/1/preview")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .contentType("text/csv")
                        .content(csv))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/ops/onboarding/supplier-b/1/preview")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "wrong")
                        .contentType("text/csv")
                        .content(csv))
                .andExpect(status().isUnauthorized());
        var service = applicationContext.getBean(dev.vmarket.migration.SupplierOnboardingService.class);
        byte[] bytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var preview = service.preview("supplier-b", 1, bytes, "operator@example.test");
        service.confirm("supplier-b", 1, bytes, "blocked.csv", preview.confirmationReceipt(), "operator@example.test");
        jdbc.sql("UPDATE import_work_item SET state='BLOCKED',last_recovery_reason='METADATA_MISMATCH_REQUIRES_REVIEW'")
                .update();
        jdbc.sql("UPDATE migration_job SET status='FAILED'").update();
        var id = jdbc.sql("SELECT id FROM migration_job")
                .query(java.util.UUID.class)
                .single();
        var imports = applicationContext.getBean(dev.vmarket.migration.LegacyImportService.class);
        assertThat(imports.inspect(id).restartAllowed()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imports.restart(id, true))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
    }

    @Test
    void supplierDatabaseFailureRollsBackChunkAndUsesOnlyTheExistingSafeRestart() {
        var service = applicationContext.getBean(dev.vmarket.migration.SupplierOnboardingService.class);
        byte[] bytes = "SKU,ProductName,AvailableQuantity\nVM-002,Recoverable desk,62\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var preview = service.preview("supplier-b", 1, bytes, "operator@example.test");
        service.confirm(
                "supplier-b", 1, bytes, "database-failure.csv", preview.confirmationReceipt(), "operator@example.test");
        jdbc.sql("""
                CREATE FUNCTION supplier_db_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Synthetic database connection failure' USING ERRCODE='08006'; END; $$
                """).update();
        jdbc.sql(
                        "CREATE TRIGGER supplier_db_failure BEFORE INSERT ON inventory_movement FOR EACH ROW EXECUTE FUNCTION supplier_db_failure()")
                .update();
        try {
            applicationContext.getBean("importDispatcher", Runnable.class).run();
            assertThat(jdbc.sql("SELECT state FROM import_work_item")
                            .query(String.class)
                            .single())
                    .isEqualTo("FAILED");
            assertThat(jdbc.sql("SELECT applied_rows FROM migration_job")
                            .query(Integer.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement")
                            .query(Integer.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql("SELECT version FROM product WHERE sku='VM-002'")
                            .query(Long.class)
                            .single())
                    .isZero();
        } finally {
            jdbc.sql("DROP TRIGGER supplier_db_failure ON inventory_movement").update();
            jdbc.sql("DROP FUNCTION supplier_db_failure()").update();
        }
        var job = jdbc.sql("SELECT id FROM migration_job")
                .query(java.util.UUID.class)
                .single();
        applicationContext
                .getBean(dev.vmarket.migration.LegacyImportService.class)
                .restart(job, true);
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        assertThat(jdbc.sql("SELECT applied_rows FROM migration_job")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void concurrentSupplierConfirmationsShareOneDurableIdentityAndOneMovement() throws Exception {
        var service = applicationContext.getBean(dev.vmarket.migration.SupplierOnboardingService.class);
        byte[] bytes = "SKU,ProductName,AvailableQuantity\nVM-002,Concurrent desk,61\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var preview = service.preview("supplier-b", 1, bytes, "operator@example.test");
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task = () -> {
                gate.await();
                service.confirm(
                        "supplier-b", 1, bytes, "same.csv", preview.confirmationReceipt(), "operator@example.test");
                return true;
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            gate.countDown();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void seededSupplierOnboardingBenchmarkProducesMeasuredImportAndReconciliationEvidence() throws Exception {
        final int seed = 20261023;
        var random = new java.util.Random(seed);
        jdbc.sql("""
                INSERT INTO product(id,vendor_id,sku,name,category,description,price_minor,image_path,image_alt)
                SELECT md5('fde-benchmark-'||n)::uuid,'10000000-0000-0000-0000-000000000002',
                       'RESTART-FDE-'||lpad(n::text,3,'0'),'Synthetic item','OFFICE','Synthetic benchmark',100,'/demo.jpg','Synthetic'
                FROM generate_series(1,200) n
                """).update();
        jdbc.sql(
                        "INSERT INTO inventory_stock(product_id,on_hand) SELECT id,100 FROM product WHERE sku LIKE 'RESTART-FDE-%'")
                .update();
        var csv = new StringBuilder("SKU,ProductName,AvailableQuantity\n");
        for (int i = 1; i <= 200; i++)
            csv.append("RESTART-FDE-%03d,Synthetic item %d,%d\n".formatted(i, i, 101 + random.nextInt(100)));
        for (int i = 1; i <= 20; i++) csv.append("BAD-%d,Synthetic invalid,invalid\n".formatted(i));
        byte[] bytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var service = applicationContext.getBean(dev.vmarket.migration.SupplierOnboardingService.class);
        long start = System.nanoTime();
        var preview = service.preview("supplier-b", 1, bytes, "benchmark");
        long previewEnd = System.nanoTime();
        assertThat(preview.acceptedRows()).isEqualTo(200);
        assertThat(preview.rejectedRows()).isEqualTo(20);
        service.confirm("supplier-b", 1, bytes, "seeded-supplier.csv", preview.confirmationReceipt(), "benchmark");
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        long importEnd = System.nanoTime();
        service.confirm("supplier-b", 1, bytes, "duplicate.csv", preview.confirmationReceipt(), "benchmark");
        String job = jdbc.sql("SELECT id::text FROM migration_job")
                .query(String.class)
                .single();
        var auth = operatorRequest();
        var report = mockMvc.perform(post("/api/ops/reconciliations")
                        .with(auth)
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"migrationJobId\":\"" + job + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long end = System.nanoTime();
        var reconciliation = objectMapper.readTree(report);
        assertThat(reconciliation.path("mismatchCount").asInt()).isZero();
        assertThat(reconciliation.path("stockNotVerifiedRows").asInt()).isZero();
        int movements = jdbc.sql("SELECT COUNT(*) FROM inventory_movement")
                .query(Integer.class)
                .single();
        int jobs = jdbc.sql("SELECT COUNT(*) FROM migration_job")
                .query(Integer.class)
                .single();
        assertThat(movements).isEqualTo(200);
        assertThat(jobs).isEqualTo(1);
        var metrics = new java.util.LinkedHashMap<String, Object>();
        metrics.put("seed", seed);
        metrics.put("synthetic", true);
        metrics.put("rows", 220);
        metrics.put("validRows", preview.acceptedRows());
        metrics.put("rejectedRows", preview.rejectedRows());
        metrics.put(
                "sourceSha256",
                java.util.HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(bytes)));
        metrics.put("previewMs", (previewEnd - start) / 1_000_000.0);
        metrics.put("previewThroughImportMs", (importEnd - start) / 1_000_000.0);
        metrics.put("endToEndThroughReconciliationMs", (end - start) / 1_000_000.0);
        metrics.put("acceptedRowsPerSecondThroughImport", 200.0 / ((importEnd - start) / 1_000_000_000.0));
        metrics.put("jobsAfterDuplicate", jobs);
        metrics.put("movementsAfterDuplicate", movements);
        metrics.put("mismatches", reconciliation.path("mismatchCount").asInt());
        metrics.put(
                "unverifiedStockRows",
                reconciliation.path("stockNotVerifiedRows").asInt());
        metrics.put(
                "environment",
                System.getProperty("os.name") + " / Java " + System.getProperty("java.version")
                        + " / PG17.6 Redis8.2 Testcontainers");
        metrics.put("measuredAt", java.time.Instant.now().toString());
        objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValue(
                        java.nio.file.Path.of("target/onboarding-benchmark.json")
                                .toFile(),
                        metrics);
    }

    @Test
    void stagedSupplierSnapshotRejectsLaterCatalogChangesAndInactiveProfilesCannotConfirm() {
        var service = applicationContext.getBean(dev.vmarket.migration.SupplierOnboardingService.class);
        var profiles = applicationContext.getBean(dev.vmarket.migration.IntegrationProfileService.class);
        byte[] bytes =
                "SKU,ProductName,AvailableQuantity\nVM-002,Desk,60\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var preview = service.preview("supplier-b", 1, bytes, "operator@example.test");
        profiles.activate("supplier-b", 1, false, "operator@example.test");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.confirm(
                        "supplier-b", 1, bytes, "file.csv", preview.confirmationReceipt(), "operator@example.test"))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class)
                .hasMessageContaining("inactive");
        profiles.activate("supplier-b", 1, true, "operator@example.test");
        service.confirm("supplier-b", 1, bytes, "file.csv", preview.confirmationReceipt(), "operator@example.test");
        jdbc.sql("UPDATE product SET price_minor=5800,version=version+1 WHERE sku='VM-002'")
                .update();
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        assertThat(jdbc.sql("SELECT validation_error FROM staged_import_row")
                        .query(String.class)
                        .single())
                .contains("STALE_PRODUCT_VERSION");
        assertThat(jdbc.sql("SELECT price_minor FROM product WHERE sku='VM-002'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(5800);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void supplierProfilesDecodeOriginalCp932AndRejectWrongEncodingHeadersAndOversizedInputsBeforeStaging() {
        var service = applicationContext.getBean(dev.vmarket.migration.SupplierOnboardingService.class);
        byte[] cp932 = "商品コード,商品名,在庫数\nVM-001,軽量ジャケット,35\n".getBytes(Charset.forName("windows-31j"));
        var preview = service.preview("supplier-a", 1, cp932, "operator@example.test");
        assertThat(preview.acceptedRows()).isEqualTo(1);
        assertThat(preview.sample().getFirst().name()).isEqualTo("軽量ジャケット");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.preview("supplier-b", 1, cp932, "operator@example.test"))
                .isInstanceOf(dev.vmarket.migration.InvalidImportException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.preview("supplier-a", 1, new byte[] {(byte) 0x81}, "operator@example.test"))
                .isInstanceOf(dev.vmarket.migration.InvalidImportException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.preview(
                        "supplier-b", 1, "SKU,ProductName\nVM-002,Desk\n".getBytes(), "operator@example.test"))
                .isInstanceOf(dev.vmarket.migration.InvalidImportException.class)
                .hasMessageContaining("Missing required");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.preview("supplier-b", 1, new byte[5_000_001], "operator@example.test"))
                .isInstanceOf(dev.vmarket.migration.InvalidImportException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.preview(
                        "supplier-b",
                        1,
                        ("SKU,ProductName,AvailableQuantity\n" + "VM-002,Desk,60\n".repeat(1001)).getBytes(),
                        "operator@example.test"))
                .isInstanceOf(dev.vmarket.migration.InvalidImportException.class)
                .hasMessageContaining("1000");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void previewConfirmationIsBoundToTheOperatorBytesAndCurrentCatalogState() {
        var service = applicationContext.getBean(dev.vmarket.migration.SupplierOnboardingService.class);
        byte[] bytes =
                "SKU,ProductName,AvailableQuantity\nVM-002,Desk,60\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var preview = service.preview("supplier-b", 1, bytes, "operator@example.test");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.confirm("supplier-b", 1, bytes, "file.csv", null, "operator@example.test"))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.confirm(
                        "supplier-b", 1, bytes, "file.csv", preview.confirmationReceipt(), "different-operator"))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.confirm(
                        "supplier-b",
                        1,
                        "changed".getBytes(),
                        "file.csv",
                        preview.confirmationReceipt(),
                        "operator@example.test"))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
        jdbc.sql("UPDATE product SET price_minor=5800,version=version+1 WHERE sku='VM-002'")
                .update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.confirm(
                        "supplier-b", 1, bytes, "file.csv", preview.confirmationReceipt(), "operator@example.test"))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class)
                .hasMessageContaining("changed after preview");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void identicalSupplierBytesCannotReplayThroughAnotherProfileVersionOrLegacyEndpoint() {
        var service = applicationContext.getBean(dev.vmarket.migration.SupplierOnboardingService.class);
        var profiles = applicationContext.getBean(dev.vmarket.migration.IntegrationProfileService.class);
        profiles.publish(
                new dev.vmarket.migration.IntegrationProfileService.Definition(
                        "supplier-b",
                        2,
                        "UTF-8",
                        "RIVERBYTE",
                        java.util.Map.of("sku", "SKU", "name", "ProductName", "on_hand", "AvailableQuantity"),
                        "TRIM_UPPER"),
                "operator@example.test");
        profiles.activate("supplier-b", 2, true, "operator@example.test");
        byte[] bytes =
                "SKU,ProductName,AvailableQuantity\nVM-002,Desk,60\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var p1 = service.preview("supplier-b", 1, bytes, "operator@example.test");
        var p2 = service.preview("supplier-b", 2, bytes, "operator@example.test");
        service.confirm("supplier-b", 1, bytes, "v1.csv", p1.confirmationReceipt(), "operator@example.test");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.confirm(
                        "supplier-b", 2, bytes, "v2.csv", p2.confirmationReceipt(), "operator@example.test"))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class)
                .hasMessageContaining("different profile/version");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> applicationContext
                        .getBean(dev.vmarket.migration.LegacyImportService.class)
                        .importCsv("legacy.csv", "UTF-8", bytes, true))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void oversizedRejectedSupplierCellsCannotPoisonTheValidRowsStagingTransaction() {
        var service = applicationContext.getBean(dev.vmarket.migration.SupplierOnboardingService.class);
        byte[] bytes = ("SKU,ProductName,AvailableQuantity\nVM-002,Desk,60\n" + "X".repeat(200) + ",Name,5\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var preview = service.preview("supplier-b", 1, bytes, "operator@example.test");
        assertThat(preview.acceptedRows()).isEqualTo(1);
        assertThat(preview.rejectedRows()).isEqualTo(1);
        service.confirm(
                "supplier-b", 1, bytes, "oversized.csv", preview.confirmationReceipt(), "operator@example.test");
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        assertThat(jdbc.sql("SELECT applied_rows FROM migration_job")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void importInspectionExposesSafeReasonsAndWorkStateNotRawPayload() throws Exception {
        var imports = applicationContext.getBean(dev.vmarket.migration.LegacyImportService.class);
        imports.importCsv(
                "inspection.csv",
                "UTF-8",
                "sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,Private cell,invalid-sensitive-value,11000\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                true);
        String id = jdbc.sql("SELECT id::text FROM migration_job")
                .query(String.class)
                .single();
        mockMvc.perform(get("/api/ops/imports/" + id + "/inspection")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("workState").value("READY"))
                .andExpect(jsonPath("restartAllowed").value(false))
                .andExpect(jsonPath("errors[0].rowNumber").value(2))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("Private cell", "invalid-sensitive-value", "claimToken"));
    }

    @Test
    void confirmedSupplierPreviewUsesExistingDurableImportAndRawByteDedup() throws Exception {
        var auth = operatorRequest();
        String csv = "SKU,ProductName,AvailableQuantity\nVM-002,Updated desk,60\n";
        String preview = mockMvc.perform(post("/api/ops/onboarding/supplier-b/1/preview")
                        .with(auth)
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .contentType("text/csv")
                        .content(csv))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String receipt =
                objectMapper.readTree(preview).path("confirmationReceipt").asText();
        String job = mockMvc.perform(post("/api/ops/onboarding/supplier-b/1/confirm")
                        .with(auth)
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .header("X-Source-Name", "supplier-b.csv")
                        .header("X-Preview-Receipt", receipt)
                        .contentType("text/csv")
                        .content(csv))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("status").value("STAGED"))
                .andExpect(jsonPath("profileId").value("supplier-b"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = objectMapper.readTree(job).path("id").asText();
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        mockMvc.perform(post("/api/ops/onboarding/supplier-b/1/confirm")
                        .with(auth)
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .header("X-Source-Name", "renamed.csv")
                        .header("X-Preview-Receipt", receipt)
                        .contentType("text/csv")
                        .content(csv))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("id").value(id));
        assertThat(jdbc.sql(
                                "SELECT on_hand FROM inventory_stock s JOIN product p ON p.id=s.product_id WHERE sku='VM-002'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(60);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void supplierPreviewMapsAndQuarantinesRowsWithoutAnyBusinessWrites() throws Exception {
        var response = mockMvc.perform(post("/api/ops/onboarding/supplier-b/1/preview")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .contentType("text/csv")
                        .content("SKU,ProductName,AvailableQuantity\n vm-002 ,Desk,60\nVM-005,Basket,not-a-number\n"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("acceptedRows").value(1))
                .andExpect(jsonPath("rejectedRows").value(1))
                .andExpect(jsonPath("sample[0].sku").value("VM-002"))
                .andExpect(jsonPath("errors[0].rowNumber").value(3))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response).doesNotContain("not-a-number");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement")
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_event")
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT version FROM product WHERE sku='VM-002'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void operatorPublishesImmutableProfileVersionAndAuditsActivation() throws Exception {
        var auth = operatorRequest();
        String definition = """
                {"profileId":"supplier-b","version":2,"encoding":"UTF-8","vendorCode":"RIVERBYTE",
                 "mapping":{"sku":"SKU","name":"ProductName","on_hand":"AvailableQuantity"},"skuNormalization":"TRIM_UPPER"}
                """;
        mockMvc.perform(post("/api/ops/integration-profiles")
                        .with(auth)
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(definition))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("active").value(false));
        mockMvc.perform(patch("/api/ops/integration-profiles/supplier-b/2")
                        .with(auth)
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("active").value(true));
        mockMvc.perform(post("/api/ops/integration-profiles")
                        .with(auth)
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(definition))
                .andExpect(status().isConflict());
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM audit_event WHERE aggregate_type='INTEGRATION_PROFILE' AND actor='operator@example.test'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void supplierProfilesExposeVersionedMappingsWithoutCreatingImportWork() throws Exception {
        mockMvc.perform(get("/api/ops/integration-profiles")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].profileId").value("supplier-a"))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].encoding").value("CP932"))
                .andExpect(jsonPath("$[0].mapping.sku").value("商品コード"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void checkoutKeyLockAlsoTimesOutWithoutExecutingASecondSale() throws Exception {
        String lockName = jdbc.sql("""
                SELECT 'vmarket:checkout:'||encode(sha256(convert_to(
                  'guest-checkout:v1:'||encode(sha256(convert_to(repeat('c',64),'UTF8')),'hex')||':bounded-key-lock','UTF8')),'hex')
                """).query(String.class).single();
        try (var executor = Executors.newSingleThreadExecutor()) {
            try (var blocker = dataSource.getConnection()) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                    statement.setString(1, lockName);
                    statement.executeQuery().close();
                }
                try {
                    var pending = executor.submit(() -> mockMvc.perform(post("/api/orders")
                                    .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                    .header("X-Checkout-Session", "c".repeat(64))
                                    .header("Idempotency-Key", "bounded-key-lock")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(orderPayload("VM-004", 1)))
                            .andReturn());
                    var response = pending.get(6, java.util.concurrent.TimeUnit.SECONDS)
                            .getResponse();
                    assertThat(response.getStatus()).isEqualTo(503);
                    assertThat(objectMapper
                                    .readTree(response.getContentAsString())
                                    .get("code")
                                    .asText())
                            .isEqualTo("DATABASE_UNAVAILABLE");
                } finally {
                    blocker.rollback();
                }
            }
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order")
                        .query(Long.class)
                        .single())
                .isZero();
        createOrder("bounded-key-lock", "VM-004", 1);
        assertThat(availableQuantity("VM-004")).isEqualTo(43);
    }

    @Test
    void checkoutStatementTimeoutRollsBackEarlierWritesWhenAuditStalls() throws Exception {
        jdbc.sql(
                        "CREATE FUNCTION qa_slow_checkout() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(30); RETURN NEW; END; $$")
                .update();
        jdbc.sql(
                        "CREATE TRIGGER qa_slow_checkout_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION qa_slow_checkout()")
                .update();
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                var pending = executor.submit(() -> mockMvc.perform(post("/api/orders")
                                .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                .header("X-Checkout-Session", "c".repeat(64))
                                .header("Idempotency-Key", "transaction-budget")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderPayload("VM-004", 1)))
                        .andReturn());
                var response =
                        pending.get(13, java.util.concurrent.TimeUnit.SECONDS).getResponse();
                assertThat(response.getStatus()).isEqualTo(503);
                assertThat(objectMapper
                                .readTree(response.getContentAsString())
                                .get("code")
                                .asText())
                        .isEqualTo("DATABASE_UNAVAILABLE");
            } finally {
                jdbc.sql("DROP TRIGGER qa_slow_checkout_audit ON audit_event").update();
                jdbc.sql("DROP FUNCTION qa_slow_checkout()").update();
            }
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE movement_type='ORDER_PLACED'")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(availableQuantity("VM-004")).isEqualTo(44);
        createOrder("transaction-budget", "VM-004", 1);
        assertThat(availableQuantity("VM-004")).isEqualTo(43);
    }

    @Test
    void jdbcConnectionAndSocketWaitingHaveExplicitConfigurationBudgets() {
        var pool = (com.zaxxer.hikari.HikariDataSource) dataSource;
        var properties = pool.getDataSourceProperties();
        assertThat(String.valueOf(properties.get("connectTimeout"))).isEqualTo("3");
        assertThat(String.valueOf(properties.get("loginTimeout"))).isEqualTo("3");
        assertThat(String.valueOf(properties.get("socketTimeout"))).isEqualTo("15");
        assertThat(String.valueOf(properties.get("cancelSignalTimeout"))).isEqualTo("2");
        assertThat(pool.getConnectionTimeout()).isEqualTo(2000);
        assertThat(pool.getValidationTimeout()).isEqualTo(1000);
    }

    @Test
    void anExhaustedConnectionPoolReturnsPromptlyWithoutStartingAnotherCheckout() throws Exception {
        var pool = (com.zaxxer.hikari.HikariDataSource) dataSource;
        var held = new java.util.ArrayList<java.sql.Connection>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                for (int index = 0; index < pool.getMaximumPoolSize(); index++) held.add(pool.getConnection());
                var pending = executor.submit(() -> mockMvc.perform(post("/api/orders")
                                .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                .header("X-Checkout-Session", "c".repeat(64))
                                .header("Idempotency-Key", "pool-starvation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderPayload("VM-004", 1)))
                        .andReturn());
                var response =
                        pending.get(5, java.util.concurrent.TimeUnit.SECONDS).getResponse();
                assertThat(response.getStatus()).isEqualTo(503);
                assertThat(objectMapper
                                .readTree(response.getContentAsString())
                                .get("code")
                                .asText())
                        .isEqualTo("DATABASE_UNAVAILABLE");
                assertThat(response.getContentAsString()).doesNotContain("jdbc:", "Hikari", "password");
            } finally {
                for (var connection : held) connection.close();
            }
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order")
                        .query(Long.class)
                        .single())
                .isZero();
        createOrder("pool-starvation", "VM-004", 1);
        assertThat(availableQuantity("VM-004")).isEqualTo(43);
    }

    @Test
    void checkoutStockLockWaitIsBoundedAndTheSameKeyCanBeUsedAfterRollback() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            try (var blocker = dataSource.getConnection()) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.createStatement()) {
                    statement
                            .executeQuery(
                                    "SELECT product_id FROM inventory_stock WHERE product_id='20000000-0000-0000-0000-000000000004' FOR UPDATE")
                            .close();
                }
                try {
                    var pending = executor.submit(() -> mockMvc.perform(post("/api/orders")
                                    .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                    .header("X-Checkout-Session", "c".repeat(64))
                                    .header("Idempotency-Key", "bounded-stock-lock")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(orderPayload("VM-004", 1)))
                            .andReturn());
                    var response = pending.get(6, java.util.concurrent.TimeUnit.SECONDS)
                            .getResponse();
                    assertThat(response.getStatus()).isEqualTo(503);
                    assertThat(objectMapper
                                    .readTree(response.getContentAsString())
                                    .get("code")
                                    .asText())
                            .isEqualTo("DATABASE_UNAVAILABLE");
                    assertThat(response.getContentAsString()).doesNotContain("inventory_stock", "SQL", "Exception");
                } finally {
                    blocker.rollback();
                }
            }
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(availableQuantity("VM-004")).isEqualTo(44);
        createOrder("bounded-stock-lock", "VM-004", 1);
        assertThat(availableQuantity("VM-004")).isEqualTo(43);
    }

    @Test
    void concurrentCreateAndCloseCanNeverBothSucceedForTheSameGuestKey() throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<Integer>>of(
                    () -> {
                        ready.countDown();
                        assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                .isTrue();
                        return mockMvc.perform(post("/api/orders")
                                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                        .header("X-Checkout-Session", "c".repeat(64))
                                        .header("Idempotency-Key", "create-close-race")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(orderPayload("VM-004", 1)))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    },
                    () -> {
                        ready.countDown();
                        assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                .isTrue();
                        return mockMvc.perform(post("/api/orders/checkout/cancel")
                                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                        .header("X-Checkout-Session", "c".repeat(64))
                                        .header("Idempotency-Key", "create-close-race"))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    }));
            int creation = results.get(0).get();
            int closure = results.get(1).get();
            assertThat(creation == 201 && closure == 409 || creation == 410 && closure == 204)
                    .isTrue();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order")
                            .query(Long.class)
                            .single())
                    .isEqualTo(creation == 201 ? 1L : 0L);
            assertThat(availableQuantity("VM-004")).isEqualTo(creation == 201 ? 43 : 44);
        }
    }

    @Test
    void closingAnExistingOrderIsRejectedAndClosuresDoNotAffectAnotherGuestScope() throws Exception {
        var existing =
                createOrder("cannot-close-existing", "VM-004", 1).getResponse().getContentAsString();
        mockMvc.perform(post("/api/orders/checkout/cancel")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-Checkout-Session", "c".repeat(64))
                        .header("Idempotency-Key", "cannot-close-existing"))
                .andExpect(status().isConflict());
        assertThat(createOrder("cannot-close-existing", "VM-004", 1)
                        .getResponse()
                        .getContentAsString())
                .isEqualTo(existing);
        mockMvc.perform(post("/api/orders/checkout/cancel")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-Checkout-Session", "d".repeat(64))
                        .header("Idempotency-Key", "scope-specific-close"))
                .andExpect(status().isNoContent());
        createOrder("scope-specific-close", "VM-004", 1);
        assertThat(availableQuantity("VM-004")).isEqualTo(42);
    }

    @Test
    void closingUnusedIntentsHasASerializedCapacityLimitButExistingClosuresRemainReadable() throws Exception {
        jdbc.sql(
                        "INSERT INTO closed_checkout_intent(key_hash) SELECT repeat(md5('capacity-'||n),2) FROM generate_series(1,9999) n")
                .update();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<Integer>>of(
                    () -> mockMvc.perform(post("/api/orders/checkout/cancel")
                                    .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                    .header("X-Checkout-Session", "c".repeat(64))
                                    .header("Idempotency-Key", "capacity-a"))
                            .andReturn()
                            .getResponse()
                            .getStatus(),
                    () -> mockMvc.perform(post("/api/orders/checkout/cancel")
                                    .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                    .header("X-Checkout-Session", "c".repeat(64))
                                    .header("Idempotency-Key", "capacity-b"))
                            .andReturn()
                            .getResponse()
                            .getStatus()));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(204, 503);
            String closedKey = results.get(0).get() == 204 ? "capacity-a" : "capacity-b";
            mockMvc.perform(post("/api/orders/checkout/cancel")
                            .header("X-V-Market-BFF-Secret", "local-bff-secret")
                            .header("X-Checkout-Session", "c".repeat(64))
                            .header("Idempotency-Key", closedKey))
                    .andExpect(status().isNoContent());
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM closed_checkout_intent")
                        .query(Long.class)
                        .single())
                .isEqualTo(10000);
    }

    @Test
    void closingAnUnusedCheckoutPreventsALateOriginalRequestFromCreatingAnOrder() throws Exception {
        var request = post("/api/orders/checkout/cancel")
                .header("X-V-Market-BFF-Secret", "local-bff-secret")
                .header("X-Checkout-Session", "c".repeat(64))
                .header("Idempotency-Key", "closed-before-arrival");
        mockMvc.perform(request).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-Checkout-Session", "c".repeat(64))
                        .header("Idempotency-Key", "closed-before-arrival")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderPayload("VM-004", 1)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CHECKOUT_CANCELLED"));
        mockMvc.perform(get("/api/orders/checkout")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-Checkout-Session", "c".repeat(64))
                        .header("Idempotency-Key", "closed-before-arrival"))
                .andExpect(status().isGone());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(availableQuantity("VM-004")).isEqualTo(44);
    }

    @Test
    void aGuestCanRecoverItsCommittedCheckoutWithoutResendingCustomerData() throws Exception {
        var first = objectMapper.readTree(createOrder("recover-without-payload", "VM-004", 1)
                .getResponse()
                .getContentAsString());
        var recovered = mockMvc.perform(get("/api/orders/checkout")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-Checkout-Session", "c".repeat(64))
                        .header("Idempotency-Key", "recover-without-payload"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(recovered.getResponse().getContentAsString()))
                .isEqualTo(first);
        mockMvc.perform(get("/api/orders/checkout")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-Checkout-Session", "d".repeat(64))
                        .header("Idempotency-Key", "recover-without-payload"))
                .andExpect(status().isNotFound());
        assertThat(availableQuantity("VM-004")).isEqualTo(43);
    }

    @Test
    void replayCleanupIsBoundedAndSkipsAnExpiredOrderLockedByAnotherTransaction() throws Exception {
        var live = objectMapper.readTree(
                createOrder("cleanup-template", "VM-004", 1).getResponse().getContentAsString());
        // Synthetic retention records only: the cloned ciphertext is never used as a valid tracking response.
        jdbc.sql("""
                INSERT INTO customer_order
                SELECT (jsonb_populate_record(NULL::customer_order,to_jsonb(o)||jsonb_build_object(
                  'id',md5('cleanup-'||n)::uuid,'order_number','RETENTION-'||n,
                  'tracking_token_hash',repeat(md5('cleanup-token-'||n),2),
                  'idempotency_key',repeat(md5('cleanup-key-'||n),2),
                  'created_at',clock_timestamp()-INTERVAL '25 hours'))).*
                FROM customer_order o CROSS JOIN generate_series(1,101) n WHERE o.order_number=:number
                """).param("number", live.get("orderNumber").asText()).update();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "SELECT id FROM customer_order WHERE order_number='RETENTION-1' FOR UPDATE")) {
                statement.executeQuery().close();
                applicationContext
                        .getBean("checkoutReplayRetention", Runnable.class)
                        .run();
                assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order WHERE tracking_replay_ciphertext IS NULL")
                                .query(Long.class)
                                .single())
                        .isEqualTo(100);
                assertThat(jdbc.sql(
                                        "SELECT tracking_replay_ciphertext FROM customer_order WHERE order_number='RETENTION-1'")
                                .query(String.class)
                                .single())
                        .isNotNull();
            } finally {
                connection.rollback();
            }
        }
        applicationContext.getBean("checkoutReplayRetention", Runnable.class).run();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order WHERE tracking_replay_ciphertext IS NULL")
                        .query(Long.class)
                        .single())
                .isEqualTo(101);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order WHERE tracking_replay_ciphertext IS NOT NULL")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order")
                        .query(Long.class)
                        .single())
                .isEqualTo(102);
    }

    @Test
    void corruptReplayStorageCannotRotateTheTrackingCapabilityOrRepeatTheSale() throws Exception {
        var first = objectMapper.readTree(
                createOrder("corrupt-replay", "VM-004", 1).getResponse().getContentAsString());
        jdbc.sql("UPDATE customer_order SET tracking_replay_ciphertext=repeat('A',123)")
                .update();
        mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-Checkout-Session", "c".repeat(64))
                        .header("Idempotency-Key", "corrupt-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderPayload("VM-004", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(availableQuantity("VM-004")).isEqualTo(43);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        mockMvc.perform(get("/api/orders/{number}", first.get("orderNumber").asText())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .queryParam("trackingToken", first.get("trackingToken").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void malformedOrMissingGuestCapabilitiesCannotCreateCheckoutWork() throws Exception {
        for (String capability : List.of("", "a".repeat(63), "z".repeat(64))) {
            mockMvc.perform(post("/api/orders")
                            .header("X-V-Market-BFF-Secret", "local-bff-secret")
                            .header("X-Checkout-Session", capability)
                            .header("Idempotency-Key", "bad-guest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderPayload("VM-004", 1)))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("Idempotency-Key", "missing-guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderPayload("VM-004", 1)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(availableQuantity("VM-004")).isEqualTo(44);
    }

    @Test
    void expiredReplayCiphertextIsPurgedWithoutDeletingTheOrderKeyOrTrackingHash() throws Exception {
        var first = objectMapper.readTree(
                createOrder("retention-expired", "VM-004", 1).getResponse().getContentAsString());
        createOrder("retention-live", "VM-004", 1);
        jdbc.sql(
                        "UPDATE customer_order SET created_at=clock_timestamp()-INTERVAL '25 hours' WHERE order_number=:number")
                .param("number", first.get("orderNumber").asText())
                .update();
        applicationContext.getBean("checkoutReplayRetention", Runnable.class).run();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order WHERE tracking_replay_ciphertext IS NULL")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order WHERE tracking_replay_ciphertext IS NOT NULL")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order WHERE checkout_scope_hash IS NOT NULL")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        mockMvc.perform(get("/api/orders/{number}", first.get("orderNumber").asText())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .queryParam("trackingToken", first.get("trackingToken").asText()))
                .andExpect(status().isOk());
        assertThat(availableQuantity("VM-004")).isEqualTo(42);
    }

    @Test
    void aHistoricalUnscopedKeyRequiresReviewInsteadOfCreatingAnotherScopedOrder() throws Exception {
        var first = objectMapper.readTree(
                createOrder("old-seed", "VM-004", 1).getResponse().getContentAsString());
        jdbc.sql(
                        "UPDATE customer_order SET checkout_scope_hash=NULL,idempotency_key='legacy-browser-key',tracking_replay_ciphertext=NULL")
                .update();
        mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-Checkout-Session", "c".repeat(64))
                        .header("Idempotency-Key", "legacy-browser-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderPayload("VM-004", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(availableQuantity("VM-004")).isEqualTo(43);
        mockMvc.perform(get("/api/orders/{number}", first.get("orderNumber").asText())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .queryParam("trackingToken", first.get("trackingToken").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void anExpiredCheckoutKeyCannotCreateAnotherOrderOrInvalidateItsOriginalTrackingLink() throws Exception {
        var first = objectMapper.readTree(
                createOrder("expired-checkout", "VM-004", 1).getResponse().getContentAsString());
        jdbc.sql("UPDATE customer_order SET created_at=clock_timestamp()-INTERVAL '25 hours'")
                .update();
        mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-Checkout-Session", "c".repeat(64))
                        .header("Idempotency-Key", "expired-checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderPayload("VM-004", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mockMvc.perform(get("/api/orders/{number}", first.get("orderNumber").asText())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .queryParam("trackingToken", first.get("trackingToken").asText()))
                .andExpect(status().isOk());
        assertThat(availableQuantity("VM-004")).isEqualTo(43);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void trackingCapabilityIsIndependentOfTheCheckoutKeyButStableOnRetry() throws Exception {
        var first = objectMapper.readTree(
                createOrder("tracking-entropy", "VM-004", 1).getResponse().getContentAsString());
        String storedKey = jdbc.sql("SELECT idempotency_key FROM customer_order")
                .query(String.class)
                .single();
        String keyDerived = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(("tracking:" + storedKey).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(first.get("trackingToken").asText()).isNotEqualTo(keyDerived).matches("[0-9a-f]{64}");
        var retry = objectMapper.readTree(
                createOrder("tracking-entropy", "VM-004", 1).getResponse().getContentAsString());
        assertThat(retry.get("trackingToken").asText())
                .isEqualTo(first.get("trackingToken").asText());
        assertThat(availableQuantity("VM-004")).isEqualTo(43);
    }

    @Test
    void differentGuestCapabilitiesCannotReplayEachOthersCheckoutKey() throws Exception {
        var first = mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-Checkout-Session", "a".repeat(64))
                        .header("Idempotency-Key", "same-browser-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderPayload("VM-004", 1)))
                .andExpect(status().isCreated())
                .andReturn();
        var second = mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-Checkout-Session", "b".repeat(64))
                        .header("Idempotency-Key", "same-browser-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderPayload("VM-004", 1)))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(objectMapper
                        .readTree(first.getResponse().getContentAsString())
                        .get("orderNumber")
                        .asText())
                .isNotEqualTo(objectMapper
                        .readTree(second.getResponse().getContentAsString())
                        .get("orderNumber")
                        .asText());
        assertThat(availableQuantity("VM-004")).isEqualTo(42);
    }

    @Test
    void unavailableRedisDoesNotPreventDatabaseCatalogReadsOrCorrectCheckout() throws Exception {
        var brokenRedis = new StringRedisTemplate() {
            @Override
            public org.springframework.data.redis.core.ValueOperations<String, String> opsForValue() {
                throw new org.springframework.data.redis.RedisConnectionFailureException("injected unavailable cache");
            }
        };
        var cache = new dev.vmarket.catalog.CatalogCache(brokenRedis, objectMapper, metrics);
        var catalog = new dev.vmarket.catalog.CatalogQueryService(jdbc, cache);
        assertThat(catalog.findActiveProducts().getFirst().availableQuantity()).isEqualTo(32);
        createOrder("cache-outage-checkout", "VM-001", 1);
        assertThat(catalog.findActiveProducts().getFirst().availableQuantity()).isEqualTo(31);
    }

    @Test
    void catalogCacheContainsOnlyMetadataAndCannotKeepAnInactiveProductVisible() throws Exception {
        mockMvc.perform(get("/api/catalog/products")).andExpect(status().isOk());
        var cached = objectMapper.readTree(redis.opsForValue().get("vmarket:catalog:metadata:v2"));
        assertThat(cached.get(0).has("availableQuantity")).isFalse();
        assertThat(cached.get(0).get("sku").asText()).isEqualTo("VM-001");
        jdbc.sql("UPDATE product SET active=FALSE WHERE sku='VM-001'").update();
        try {
            var result = mockMvc.perform(get("/api/catalog/products"))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(objectMapper
                            .readTree(result.getResponse().getContentAsString())
                            .findValuesAsText("sku"))
                    .doesNotContain("VM-001");
        } finally {
            jdbc.sql("UPDATE product SET active=TRUE WHERE sku='VM-001'").update();
        }
    }

    @Test
    void theOneThousandRowBoundaryIsAcceptedWithoutExecutingInTheRequest() throws Exception {
        var csv = "sku,vendor_code,name,on_hand,price_minor\n" + "VM-001,NAMI,Bounded import,32,11000\n".repeat(1000);
        uploadAsyncCsv(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), "UTF-8")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.totalRows").value(1000))
                .andExpect(jsonPath("$.appliedRows").value(0))
                .andExpect(jsonPath("$.rejectedRows").value(1000));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM staged_import_row")
                        .query(Long.class)
                        .single())
                .isEqualTo(1000);
        assertThat(availableQuantity("VM-001")).isEqualTo(32);
    }

    @Test
    void malformedCp932AlsoRejectsTheWholeFileBeforeStaging() throws Exception {
        var csv = new java.io.ByteArrayOutputStream();
        csv.writeBytes("sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,broken-"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        csv.write(0x81);
        csv.write(0x20);
        csv.writeBytes(",32,11000\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        uploadAsyncCsv(csv.toByteArray(), "CP932")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMPORT"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void oversizedCsvHasAnExplicitPayloadLimitResponse() throws Exception {
        uploadAsyncCsv(new byte[5_000_001], "UTF-8")
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void byteIdenticalUploadsWithDifferentDeclaredEncodingsConflict() throws Exception {
        var csv = "sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,Encoding identity,32,11000\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        uploadAsyncCsv(csv, "UTF-8").andExpect(status().isAccepted());
        uploadAsyncCsv(csv, "CP932")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_CONFLICT"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aDuplicateUploadCannotBypassEncodingValidation() throws Exception {
        var csv = "sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,Same file,32,11000\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        uploadAsyncCsv(csv, "UTF-8").andExpect(status().isAccepted());
        uploadAsyncCsv(csv, "UTF-16")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMPORT"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void anImportOverOneThousandDataRowsIsRejectedBeforeStaging() throws Exception {
        var csv = "sku,vendor_code,name,on_hand,price_minor\n" + "VM-001,NAMI,Bounded import,32,11000\n".repeat(1001);
        uploadAsyncCsv(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), "UTF-8")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMPORT"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM staged_import_row")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void extraCsvValuesAreRejectedInsteadOfSilentlyDiscarded() throws Exception {
        var csv = "sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,Extra field,32,11000,unexpected\n";
        uploadAsyncCsv(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), "UTF-8")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMPORT"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void duplicateHeadersCannotSilentlyChooseOneOfTwoStockColumns() throws Exception {
        var csv = "sku,vendor_code,name,on_hand,price_minor,on_hand\nVM-001,NAMI,Ambiguous,32,11000,999\n";
        uploadAsyncCsv(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), "UTF-8")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMPORT"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void malformedUtf8IsRejectedBeforeAnyImportJobIsStaged() throws Exception {
        var csv = "sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,broken-?,32,11000\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < csv.length; i++) if (csv[i] == '?') csv[i] = (byte) 0xFF;
        uploadAsyncCsv(csv, "UTF-8")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMPORT"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM import_work_item")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private org.springframework.test.web.servlet.ResultActions uploadAsyncCsv(byte[] csv, String encoding)
            throws Exception {
        return mockMvc.perform(post("/api/ops/imports")
                .with(operatorRequest())
                .header("X-V-Market-BFF-Secret", "local-bff-secret")
                .header("X-V-Market-Ops-Secret", "local-development-only")
                .header("X-Source-Name", "contract-test.csv")
                .header("X-Source-Encoding", encoding)
                .header("Prefer", "respond-async")
                .contentType("text/csv")
                .content(csv));
    }

    @Test
    void aWarmCatalogCacheCannotHideStockConsumedByCheckout() throws Exception {
        mockMvc.perform(get("/api/catalog/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].availableQuantity").value(32));
        createOrder("stock-freshness-after-checkout", "VM-001", 1);
        mockMvc.perform(get("/api/catalog/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].availableQuantity").value(31));
    }

    @Test
    void unknownBatchMetadataIsBlockedRatherThanBlindlyRestarted() {
        var job = stageStockSnapshot(false);
        jdbc.sql("UPDATE import_work_item SET dispatch_requested=TRUE WHERE job_id=:job")
                .param("job", job)
                .update();
        var claim = importWork.claim(job);
        var execution = startMetadata(claim);
        jdbc.sql("UPDATE batch_job_execution SET status='UNKNOWN' WHERE job_execution_id=:id")
                .param("id", execution.getId())
                .update();
        jdbc.sql("UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE job_id=:job")
                .param("job", job)
                .update();
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        assertThat(jdbc.sql("SELECT state||':'||last_recovery_reason FROM import_work_item WHERE job_id=:job")
                        .param("job", job)
                        .query(String.class)
                        .single())
                .isEqualTo("BLOCKED:METADATA_MISMATCH_REQUIRES_REVIEW");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM batch_job_execution WHERE job_instance_id=:id")
                        .param("id", execution.getJobInstance().getInstanceId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(importWork.failIfOwned(claim)).isFalse();
        assertThat(availableQuantity("VM-001")).isEqualTo(32);
    }

    @Test
    void aRecoveryFailureDoesNotStarveOtherReadyJobs() {
        var expired = stageStockSnapshot(false);
        jdbc.sql("UPDATE import_work_item SET dispatch_requested=TRUE WHERE job_id=:job")
                .param("job", expired)
                .update();
        startMetadata(importWork.claim(expired));
        jdbc.sql("UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE job_id=:job")
                .param("job", expired)
                .update();
        var ready = stageStockSnapshot(false);
        jdbc.sql("UPDATE import_work_item SET dispatch_requested=TRUE WHERE job_id=:job")
                .param("job", ready)
                .update();
        jdbc.sql("""
                CREATE FUNCTION fail_recovery_candidate() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.recovery_count>OLD.recovery_count THEN RAISE EXCEPTION 'injected recovery failure'; END IF; RETURN NEW; END; $$
                """).update();
        try {
            jdbc.sql(
                            "CREATE TRIGGER fail_recovery_candidate BEFORE UPDATE ON import_work_item FOR EACH ROW EXECUTE FUNCTION fail_recovery_candidate()")
                    .update();
            org.assertj.core.api.Assertions.assertThatCode(() -> applicationContext
                            .getBean("importDispatcher", Runnable.class)
                            .run())
                    .doesNotThrowAnyException();
            assertThat(jdbc.sql("SELECT state FROM import_work_item WHERE job_id=:job")
                            .param("job", ready)
                            .query(String.class)
                            .single())
                    .isEqualTo("FINISHED");
            assertThat(jdbc.sql("SELECT state FROM import_work_item WHERE job_id=:job")
                            .param("job", expired)
                            .query(String.class)
                            .single())
                    .isEqualTo("CLAIMED");
            assertThat(availableQuantity("VM-001")).isEqualTo(40);
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS fail_recovery_candidate ON import_work_item")
                    .update();
            jdbc.sql("DROP FUNCTION fail_recovery_candidate()").update();
        }
    }

    @Test
    void repeatedOwnerLossStopsAfterThreeRecoveriesAndRequiresReview() {
        var job = stageStockSnapshot(false);
        jdbc.sql("UPDATE import_work_item SET dispatch_requested=TRUE WHERE job_id=:job")
                .param("job", job)
                .update();
        var recovery = applicationContext.getBean(dev.vmarket.migration.ImportRecoveryService.class);
        for (int attempt = 1; attempt <= 4; attempt++) {
            var claim = importWork.claim(job);
            assertThat(claim).isNotNull();
            jdbc.sql("UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE job_id=:job")
                    .param("job", job)
                    .update();
            assertThat(recovery.recoverOne()).isTrue();
            assertThat(jdbc.sql("SELECT state FROM import_work_item WHERE job_id=:job")
                            .param("job", job)
                            .query(String.class)
                            .single())
                    .isEqualTo(attempt <= 3 ? "READY" : "BLOCKED");
        }
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        assertThat(jdbc.sql("SELECT last_recovery_reason FROM import_work_item WHERE job_id=:job")
                        .param("job", job)
                        .query(String.class)
                        .single())
                .isEqualTo("RECOVERY_LIMIT_REQUIRES_REVIEW");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE reference_id=:job")
                        .param("job", job.toString())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(availableQuantity("VM-001")).isEqualTo(32);
    }

    @Test
    void recoveryAcknowledgesCompletedMetadataWithoutLaunchingAnotherExecution() {
        var job = stageStockSnapshot(false);
        jdbc.sql("UPDATE import_work_item SET dispatch_requested=TRUE WHERE job_id=:job")
                .param("job", job)
                .update();
        var claim = importWork.claim(job);
        var execution = startMetadata(claim);
        assertThat(importChunks.applyNextChunk(claim)).isEqualTo(1);
        assertThat(importChunks.applyNextChunk(claim)).isZero();
        var repository = applicationContext.getBean(
                "fencedImportRepository", org.springframework.batch.core.repository.JobRepository.class);
        applicationContext
                .getBean(dev.vmarket.migration.ImportExecutionScope.class)
                .run(claim, () -> {
                    var step = execution.getStepExecutions().iterator().next();
                    step.setStatus(org.springframework.batch.core.BatchStatus.COMPLETED);
                    step.setEndTime(java.time.LocalDateTime.now());
                    repository.update(step);
                    execution.setStatus(org.springframework.batch.core.BatchStatus.COMPLETED);
                    execution.setEndTime(java.time.LocalDateTime.now());
                    repository.update(execution);
                });
        jdbc.sql("UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE job_id=:job")
                .param("job", job)
                .update();
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        assertThat(jdbc.sql("SELECT state||':'||last_recovery_reason FROM import_work_item WHERE job_id=:job")
                        .param("job", job)
                        .query(String.class)
                        .single())
                .isEqualTo("FINISHED:COMPLETED_METADATA_ACKNOWLEDGED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM batch_job_execution WHERE job_instance_id=:id")
                        .param("id", execution.getJobInstance().getInstanceId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE reference_id=:job")
                        .param("job", job.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(availableQuantity("VM-001")).isEqualTo(40);
    }

    @Test
    void recoveryMetadataRetirementAndGenerationRevocationRollBackTogetherOnFailure() {
        var job = stageStockSnapshot(false);
        jdbc.sql("UPDATE import_work_item SET dispatch_requested=TRUE WHERE job_id=:job")
                .param("job", job)
                .update();
        var claim = importWork.claim(job);
        var old = startMetadata(claim);
        jdbc.sql("UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE job_id=:job")
                .param("job", job)
                .update();
        jdbc.sql("""
                CREATE FUNCTION fail_import_recovery() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.recovery_count>OLD.recovery_count THEN RAISE EXCEPTION 'injected recovery audit failure'; END IF; RETURN NEW; END; $$
                """).update();
        try {
            jdbc.sql(
                            "CREATE TRIGGER fail_import_recovery BEFORE UPDATE ON import_work_item FOR EACH ROW EXECUTE FUNCTION fail_import_recovery()")
                    .update();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> applicationContext
                            .getBean(dev.vmarket.migration.ImportRecoveryService.class)
                            .recoverOne())
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasMessageContaining("injected recovery audit failure");
            assertThat(jdbc.sql("SELECT status FROM batch_job_execution WHERE job_execution_id=:id")
                            .param("id", old.getId())
                            .query(String.class)
                            .single())
                    .isEqualTo("STARTED");
            assertThat(jdbc.sql("SELECT status FROM batch_step_execution WHERE job_execution_id=:id")
                            .param("id", old.getId())
                            .query(String.class)
                            .single())
                    .isEqualTo("STARTED");
            assertThat(jdbc.sql(
                                    "SELECT COUNT(*) FROM import_work_item WHERE job_id=:job AND state='CLAIMED' AND generation=1 AND recovery_count=0 AND claim_token IS NOT NULL")
                            .param("job", job)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS fail_import_recovery ON import_work_item")
                    .update();
            jdbc.sql("DROP FUNCTION fail_import_recovery()").update();
        }
        assertThat(applicationContext
                        .getBean(dev.vmarket.migration.ImportRecoveryService.class)
                        .recoverOne())
                .isTrue();
    }

    @Test
    void recoveryResumesThePendingRowAfterFiftyRealStockMovementsAlreadyCommitted() throws Exception {
        jdbc.sql("""
                INSERT INTO product(id,vendor_id,sku,name,category,description,price_minor,image_path,image_alt)
                SELECT md5('restart-test-' || n)::uuid,'10000000-0000-0000-0000-000000000001',
                       'RESTART-' || lpad(n::text,3,'0'),'Restart item','OFFICE','Test data',100,'/test.jpg','Test'
                FROM generate_series(1,51) n
                """).update();
        jdbc.sql(
                        "INSERT INTO inventory_stock(product_id,on_hand) SELECT id,100 FROM product WHERE sku LIKE 'RESTART-%'")
                .update();
        var csv = new StringBuilder("schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n");
        for (int n = 1; n <= 51; n++) csv.append("2,RESTART-%03d,NAMI,Recoverable item,101,200,0\n".formatted(n));
        var result = mockMvc.perform(post("/api/ops/imports")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .header("Prefer", "respond-async")
                        .header("X-Source-Name", "recover-51.csv")
                        .header("X-Source-Encoding", "UTF-8")
                        .contentType("text/csv")
                        .content(csv.toString()))
                .andExpect(status().isAccepted())
                .andReturn();
        var job = java.util.UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText());
        var claim = importWork.claim(job);
        var old = startMetadata(claim);
        assertThat(importChunks.applyNextChunk(claim)).isEqualTo(50);
        jdbc.sql("UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE job_id=:job")
                .param("job", job)
                .update();
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE reference_id=:job")
                        .param("job", job.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(51);
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM inventory_stock s JOIN product p ON p.id=s.product_id WHERE p.sku LIKE 'RESTART-%' AND s.on_hand=101 AND s.version=1")
                        .query(Long.class)
                        .single())
                .isEqualTo(51);
        assertThat(jdbc.sql("SELECT applied_rows FROM migration_job WHERE id=:job")
                        .param("job", job)
                        .query(Integer.class)
                        .single())
                .isEqualTo(51);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM batch_job_execution WHERE job_instance_id=:id")
                        .param("id", old.getJobInstance().getInstanceId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT state FROM import_work_item WHERE job_id=:job")
                        .param("job", job)
                        .query(String.class)
                        .single())
                .isEqualTo("FINISHED");
    }

    @Test
    void recoveryCanHandleAnotherCrashAfterClaimButBeforeANewExecutionIsCreated() {
        var job = stageStockSnapshot(false);
        jdbc.sql("UPDATE import_work_item SET dispatch_requested=TRUE WHERE job_id=:job")
                .param("job", job)
                .update();
        var first = importWork.claim(job);
        var old = startMetadata(first);
        jdbc.sql("UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE job_id=:job")
                .param("job", job)
                .update();
        var recovery = applicationContext.getBean(dev.vmarket.migration.ImportRecoveryService.class);
        assertThat(recovery.recoverOne()).isTrue();
        var second = importWork.claim(job);
        assertThat(second.generation()).isGreaterThan(first.generation());
        jdbc.sql("UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE job_id=:job")
                .param("job", job)
                .update();
        assertThat(recovery.recoverOne()).isTrue();
        assertThat(jdbc.sql("SELECT state FROM import_work_item WHERE job_id=:job")
                        .param("job", job)
                        .query(String.class)
                        .single())
                .isEqualTo("READY");
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        assertThat(availableQuantity("VM-001")).isEqualTo(40);
        assertThat(jdbc.sql("SELECT status FROM batch_job_execution WHERE job_execution_id=:id")
                        .param("id", old.getId())
                        .query(String.class)
                        .single())
                .isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT recovery_count FROM import_work_item WHERE job_id=:job")
                        .param("job", job)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void recoveryDoesNotStealALiveLeaseOrAJobRowHeldByAnInFlightTransaction() throws Exception {
        var job = stageStockSnapshot(false);
        jdbc.sql("UPDATE import_work_item SET dispatch_requested=TRUE WHERE job_id=:job")
                .param("job", job)
                .update();
        var claim = importWork.claim(job);
        var old = startMetadata(claim);
        var recovery = applicationContext.getBean(dev.vmarket.migration.ImportRecoveryService.class);
        assertThat(recovery.recoverOne()).isFalse();
        jdbc.sql("UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE job_id=:job")
                .param("job", job)
                .update();
        try (var connection = dataSource.getConnection();
                var pool = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM migration_job WHERE id=? FOR UPDATE")) {
                statement.setObject(1, job);
                statement.executeQuery().close();
                assertThat(pool.submit(recovery::recoverOne).get(2, java.util.concurrent.TimeUnit.SECONDS))
                        .isFalse();
                assertThat(jdbc.sql("SELECT status FROM batch_job_execution WHERE job_execution_id=:id")
                                .param("id", old.getId())
                                .query(String.class)
                                .single())
                        .isEqualTo("STARTED");
            } finally {
                connection.rollback();
            }
        }
        assertThat(recovery.recoverOne()).isTrue();
        assertThat(jdbc.sql("SELECT state FROM import_work_item WHERE job_id=:job")
                        .param("job", job)
                        .query(String.class)
                        .single())
                .isEqualTo("READY");
        assertThat(jdbc.sql("SELECT status FROM batch_job_execution WHERE job_execution_id=:id")
                        .param("id", old.getId())
                        .query(String.class)
                        .single())
                .isEqualTo("FAILED");
        assertThat(availableQuantity("VM-001")).isEqualTo(32);
    }

    @Test
    void recoveredWorkerCannotRewriteJobStepOrExecutionContextMetadata() {
        var job = stageStockSnapshot(false);
        jdbc.sql("UPDATE import_work_item SET dispatch_requested=TRUE WHERE job_id=:job")
                .param("job", job)
                .update();
        var oldClaim = importWork.claim(job);
        var old = startMetadata(oldClaim);
        var oldStep = old.getStepExecutions().iterator().next();
        jdbc.sql("UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE job_id=:job")
                .param("job", job)
                .update();
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        var repository = applicationContext.getBean(
                "fencedImportRepository", org.springframework.batch.core.repository.JobRepository.class);
        var scope = applicationContext.getBean(dev.vmarket.migration.ImportExecutionScope.class);
        scope.run(oldClaim, () -> {
            old.setStatus(org.springframework.batch.core.BatchStatus.COMPLETED);
            oldStep.setStatus(org.springframework.batch.core.BatchStatus.COMPLETED);
            oldStep.getExecutionContext().putString("recoveryProof", "stale-overwrite");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.update(old))
                    .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.update(oldStep))
                    .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.updateExecutionContext(oldStep))
                    .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> repository.createJobExecution("legacyCatalogImportJob", old.getJobParameters()))
                    .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
        });
        var explorer = applicationContext.getBean(org.springframework.batch.core.explore.JobExplorer.class);
        var persisted = explorer.getJobExecution(old.getId());
        assertThat(persisted.getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.FAILED);
        var persistedStep = persisted.getStepExecutions().iterator().next();
        assertThat(persistedStep.getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.FAILED);
        assertThat(persistedStep.getExecutionContext().getString("recoveryProof"))
                .isEqualTo("preserve-me");
        assertThat(importWork.failIfOwned(oldClaim)).isFalse();
        assertThat(jdbc.sql("SELECT state FROM import_work_item WHERE job_id=:job")
                        .param("job", job)
                        .query(String.class)
                        .single())
                .isEqualTo("FINISHED");
    }

    private org.springframework.batch.core.JobExecution startMetadata(dev.vmarket.migration.ImportWorkClaim claim) {
        var repository = applicationContext.getBean(
                "fencedImportRepository", org.springframework.batch.core.repository.JobRepository.class);
        var scope = applicationContext.getBean(dev.vmarket.migration.ImportExecutionScope.class);
        var result = new java.util.concurrent.atomic.AtomicReference<org.springframework.batch.core.JobExecution>();
        scope.run(claim, () -> {
            try {
                var execution = repository.createJobExecution(
                        "legacyCatalogImportJob",
                        new org.springframework.batch.core.JobParametersBuilder()
                                .addString("jobId", claim.jobId().toString())
                                .addLong("claimGeneration", claim.generation(), false)
                                .toJobParameters());
                execution.setStatus(org.springframework.batch.core.BatchStatus.STARTED);
                execution.setStartTime(java.time.LocalDateTime.now());
                repository.update(execution);
                var step = new org.springframework.batch.core.StepExecution("applyLegacyCatalogStep", execution);
                execution.addStepExecutions(java.util.List.of(step));
                repository.add(step);
                step.setStatus(org.springframework.batch.core.BatchStatus.STARTED);
                step.setStartTime(java.time.LocalDateTime.now());
                repository.update(step);
                step.getExecutionContext().putString("recoveryProof", "preserve-me");
                repository.updateExecutionContext(step);
                result.set(execution);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        return result.get();
    }

    @Test
    void dispatcherRecoversAnExpiredStartedExecutionWithoutRepeatingItsCommittedMovement() {
        var job = stageStockSnapshot(false);
        jdbc.sql("UPDATE import_work_item SET dispatch_requested=TRUE WHERE job_id=:job")
                .param("job", job)
                .update();
        var claim = importWork.claim(job);
        var old = startMetadata(claim);
        assertThat(importChunks.applyNextChunk(claim)).isEqualTo(1);
        jdbc.sql("UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE job_id=:job")
                .param("job", job)
                .update();
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        assertThat(jdbc.sql("SELECT state FROM import_work_item WHERE job_id=:job")
                        .param("job", job)
                        .query(String.class)
                        .single())
                .isEqualTo("FINISHED");
        assertThat(jdbc.sql("SELECT status FROM batch_job_execution WHERE job_execution_id=:id")
                        .param("id", old.getId())
                        .query(String.class)
                        .single())
                .isEqualTo("FAILED");
        assertThat(availableQuantity("VM-001")).isEqualTo(40);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE reference_id=:job")
                        .param("job", job.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM batch_job_execution_params WHERE parameter_name='jobId' AND parameter_value=:job")
                        .param("job", job.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void completedImportAlsoCompletesItsFencedBatchMetadataBeforeReleasingOwnership() throws Exception {
        var response = mockMvc.perform(post("/api/ops/imports")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .header("X-Source-Name", "fenced-complete.csv")
                        .header("X-Source-Encoding", "UTF-8")
                        .contentType("text/csv")
                        .content("sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,Fenced completion,32,11000\n"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn();
        String id = objectMapper
                .readTree(response.getResponse().getContentAsString())
                .get("id")
                .asText();
        assertThat(jdbc.sql("""
                SELECT e.status FROM batch_job_execution e JOIN batch_job_execution_params p ON p.job_execution_id=e.job_execution_id
                WHERE p.parameter_name='jobId' AND p.parameter_value=:job
                """).param("job", id).query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT state FROM import_work_item WHERE job_id=:job")
                        .param("job", java.util.UUID.fromString(id))
                        .query(String.class)
                        .single())
                .isEqualTo("FINISHED");
    }

    @Test
    void importMetadataCannotCreateAnExecutionWithoutItsCurrentWorkerScope() {
        assertThat(applicationContext.containsBean("fencedImportRepository")).isTrue();
        var repository = applicationContext.getBean(
                "fencedImportRepository", org.springframework.batch.core.repository.JobRepository.class);
        var job = stageStockSnapshot(false);
        var claim = importWork.claim(job);
        var parameters = new org.springframework.batch.core.JobParametersBuilder()
                .addString("jobId", job.toString())
                .addLong("claimGeneration", claim.generation(), false)
                .toJobParameters();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> repository.createJobExecution("legacyCatalogImportJob", parameters))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM batch_job_execution_params WHERE parameter_name='jobId' AND parameter_value=:job")
                        .param("job", job.toString())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void dispatchMigrationDoesNotEnqueueExistingSynchronousReadyWork() {
        var configuration = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("dispatch_upgrade")
                .defaultSchema("dispatch_upgrade");
        configuration.target("7").load().migrate();
        jdbc.sql("""
                INSERT INTO dispatch_upgrade.migration_job(id,source_name,file_checksum,encoding,status,total_rows,valid_rows)
                VALUES('00000000-0000-0000-0000-000000000801','old-ready.csv',repeat('f',64),'UTF-8','STAGED',1,1)
                """).update();
        jdbc.sql("INSERT INTO dispatch_upgrade.import_work_item(job_id) VALUES('00000000-0000-0000-0000-000000000801')")
                .update();
        configuration
                .target(org.flywaydb.core.api.MigrationVersion.LATEST)
                .load()
                .migrate();
        assertThat(jdbc.sql("SELECT state FROM dispatch_upgrade.import_work_item")
                        .query(String.class)
                        .single())
                .isEqualTo("READY");
        assertThat(jdbc.sql("SELECT dispatch_requested FROM dispatch_upgrade.import_work_item")
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(jdbc.sql("SELECT generation FROM dispatch_upgrade.import_work_item")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT status FROM dispatch_upgrade.migration_job")
                        .query(String.class)
                        .single())
                .isEqualTo("STAGED");
        assertThat(jdbc.sql(
                                "SELECT on_hand FROM dispatch_upgrade.inventory_stock WHERE product_id='20000000-0000-0000-0000-000000000001'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(32);
    }

    @Test
    void competingDispatchersConsumeAQueuedJobOnlyOnce() throws Exception {
        var job = stageStockSnapshot(false);
        jdbc.sql("UPDATE import_work_item SET dispatch_requested=TRUE WHERE job_id=:job")
                .param("job", job)
                .update();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task = () -> {
                assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                applicationContext.getBean("importDispatcher", Runnable.class).run();
                return true;
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(availableQuantity("VM-001")).isEqualTo(40);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE reference_id=:job")
                        .param("job", job.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT generation FROM import_work_item WHERE job_id=:job")
                        .param("job", job)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM batch_job_execution_params WHERE parameter_name='jobId' AND parameter_value=:job")
                        .param("job", job.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateAsyncUploadsCreateOneDurableJobInsteadOfFailingWithDuplicateChecksum() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<org.springframework.test.web.servlet.MvcResult> task = () -> {
                var authenticated = operatorRequest();
                assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                return mockMvc.perform(
                                post("/api/ops/imports")
                                        .with(authenticated)
                                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                        .header("X-V-Market-Ops-Secret", "local-development-only")
                                        .header("Prefer", "respond-async")
                                        .header("X-Source-Name", "duplicate-async.csv")
                                        .header("X-Source-Encoding", "UTF-8")
                                        .contentType("text/csv")
                                        .content(
                                                "sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,Duplicate async jacket,32,11000\n"))
                        .andExpect(status().isAccepted())
                        .andReturn();
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            String firstId = objectMapper
                    .readTree(first.get(20, java.util.concurrent.TimeUnit.SECONDS)
                            .getResponse()
                            .getContentAsString())
                    .get("id")
                    .asText();
            String secondId = objectMapper
                    .readTree(second.get(20, java.util.concurrent.TimeUnit.SECONDS)
                            .getResponse()
                            .getContentAsString())
                    .get("id")
                    .asText();
            assertThat(secondId).isEqualTo(firstId);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM import_work_item WHERE state='READY' AND dispatch_requested=TRUE")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM staged_import_row")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(availableQuantity("VM-001")).isEqualTo(32);
    }

    @Test
    void asyncRestartQueuesExistingFailedJobAndOnlyTheDispatcherAppliesIt() throws Exception {
        var job = stageStockSnapshot(false);
        jdbc.sql("UPDATE migration_job SET status='FAILED' WHERE id=:job")
                .param("job", job)
                .update();
        jdbc.sql("UPDATE import_work_item SET state='FAILED' WHERE job_id=:job")
                .param("job", job)
                .update();
        mockMvc.perform(post("/api/ops/imports/{id}/restart", job)
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .header("Prefer", "respond-async"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("STAGED"))
                .andExpect(jsonPath("$.appliedRows").value(0));
        assertThat(availableQuantity("VM-001")).isEqualTo(32);
        mockMvc.perform(post("/api/ops/imports/{id}/restart", job)
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .header("Prefer", "respond-async"))
                .andExpect(status().isConflict());
        applicationContext.getBean("importDispatcher", Runnable.class).run();
        assertThat(availableQuantity("VM-001")).isEqualTo(40);
        mockMvc.perform(post("/api/ops/imports/{id}/restart", job)
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .header("Prefer", "respond-async"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE reference_id=:job")
                        .param("job", job.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Autowired
    org.springframework.context.ApplicationContext applicationContext;

    @Test
    void dispatcherConsumesCommittedAsyncWorkButLeavesLegacyReadyWorkAlone() throws Exception {
        var legacy = stageStockSnapshot(false);
        var response = mockMvc.perform(
                        post("/api/ops/imports")
                                .with(operatorRequest())
                                .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                .header("X-V-Market-Ops-Secret", "local-development-only")
                                .header("Prefer", "respond-async")
                                .header("X-Source-Name", "dispatch.csv")
                                .header("X-Source-Encoding", "UTF-8")
                                .contentType("text/csv")
                                .content(
                                        "schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n2,VM-002,RIVERBYTE,Async desk,70,5700,0\n"))
                .andExpect(status().isAccepted())
                .andReturn();
        var id = java.util.UUID.fromString(objectMapper
                .readTree(response.getResponse().getContentAsString())
                .get("id")
                .asText());
        assertThat(applicationContext.containsBean("importDispatcher")).isTrue();
        var dispatcher = applicationContext.getBean("importDispatcher", Runnable.class);
        dispatcher.run();
        dispatcher.run();
        assertThat(availableQuantity("VM-002")).isEqualTo(70);
        assertThat(jdbc.sql("SELECT status FROM migration_job WHERE id=:job")
                        .param("job", id)
                        .query(String.class)
                        .single())
                .isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT state FROM import_work_item WHERE job_id=:job")
                        .param("job", legacy)
                        .query(String.class)
                        .single())
                .isEqualTo("READY");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE reference_id=:job")
                        .param("job", id.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void asynchronousUploadReturnsDurablyStagedWorkWithoutRunningItInTheRequest() throws Exception {
        var response = mockMvc.perform(
                        post("/api/ops/imports")
                                .with(operatorRequest())
                                .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                .header("X-V-Market-Ops-Secret", "local-development-only")
                                .header("Prefer", "respond-async")
                                .header("X-Source-Name", "async.csv")
                                .header("X-Source-Encoding", "UTF-8")
                                .contentType("text/csv")
                                .content(
                                        "schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n2,VM-001,NAMI,Async jacket,40,11000,0\n"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Preference-Applied", "respond-async"))
                .andExpect(jsonPath("$.status").value("STAGED"))
                .andExpect(jsonPath("$.appliedRows").value(0))
                .andReturn();
        var id = java.util.UUID.fromString(objectMapper
                .readTree(response.getResponse().getContentAsString())
                .get("id")
                .asText());
        assertThat(availableQuantity("VM-001")).isEqualTo(32);
        assertThat(jdbc.sql("SELECT state FROM import_work_item WHERE job_id=:job")
                        .param("job", id)
                        .query(String.class)
                        .single())
                .isEqualTo("READY");
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM batch_job_execution_params WHERE parameter_name='jobId' AND parameter_value=:job")
                        .param("job", id.toString())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aStagingFailureRollsBackTheJobAndItsDurableWorkTogether() throws Exception {
        jdbc.sql("""
                CREATE FUNCTION abort_work_staging() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected staging abort'; END; $$
                """).update();
        try {
            jdbc.sql(
                            "CREATE TRIGGER abort_staged_row BEFORE INSERT ON staged_import_row FOR EACH ROW EXECUTE FUNCTION abort_work_staging()")
                    .update();
            mockMvc.perform(
                            post("/api/ops/imports")
                                    .with(operatorRequest())
                                    .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                    .header("X-V-Market-Ops-Secret", "local-development-only")
                                    .header("X-Source-Name", "rollback-work.csv")
                                    .header("X-Source-Encoding", "UTF-8")
                                    .contentType("text/csv")
                                    .content(
                                            "sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,Atomic work jacket,32,11000\n"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
            assertThat(jdbc.sql("SELECT COUNT(*) FROM migration_job")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM import_work_item")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM staged_import_row")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(availableQuantity("VM-001")).isEqualTo(32);
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS abort_staged_row ON staged_import_row")
                    .update();
            jdbc.sql("DROP FUNCTION abort_work_staging()").update();
        }
    }

    @Test
    void workMigrationPreservesOldJobsWithoutInventingOwnersForActiveExecutions() {
        var configuration = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("work_upgrade")
                .defaultSchema("work_upgrade");
        configuration.target("6").load().migrate();
        jdbc.sql("""
                INSERT INTO work_upgrade.migration_job(id,source_name,file_checksum,encoding,status,total_rows,valid_rows,applied_rows)
                VALUES ('00000000-0000-0000-0000-000000000701','complete.csv',repeat('c',64),'UTF-8','COMPLETED',7,7,7),
                       ('00000000-0000-0000-0000-000000000702','failed.csv',repeat('d',64),'UTF-8','FAILED',7,7,2),
                       ('00000000-0000-0000-0000-000000000703','running.csv',repeat('e',64),'UTF-8','RUNNING',7,7,2)
                """).update();
        configuration
                .target(org.flywaydb.core.api.MigrationVersion.LATEST)
                .load()
                .migrate();
        assertThat(jdbc.sql("SELECT state FROM work_upgrade.import_work_item ORDER BY job_id")
                        .query(String.class)
                        .list())
                .containsExactly("FINISHED", "FAILED", "BLOCKED");
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM work_upgrade.import_work_item WHERE generation=0 AND claim_token IS NULL AND lease_until IS NULL")
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
        assertThat(jdbc.sql("SELECT applied_rows FROM work_upgrade.migration_job ORDER BY id")
                        .query(Integer.class)
                        .list())
                .containsExactly(7, 2, 2);
        assertThat(jdbc.sql("SELECT status FROM work_upgrade.migration_job ORDER BY id")
                        .query(String.class)
                        .list())
                .containsExactly("COMPLETED", "FAILED", "RUNNING");
        assertThat(jdbc.sql(
                                "SELECT on_hand FROM work_upgrade.inventory_stock WHERE product_id='20000000-0000-0000-0000-000000000001'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(32);
    }

    @Test
    void twoWorkersCompetingForReadyWorkProduceOneOwnerAndOneMovement() throws Exception {
        var job = stageStockSnapshot(false);
        var start = new java.util.concurrent.CountDownLatch(1);
        dev.vmarket.migration.ImportWorkClaim winner;
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<dev.vmarket.migration.ImportWorkClaim> task = () -> {
                assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                try {
                    return importWork.claim(job);
                } catch (dev.vmarket.migration.ImportConflictException conflict) {
                    return null;
                }
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            var claims = java.util.stream.Stream.of(
                            first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                            second.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            assertThat(claims).hasSize(1);
            winner = claims.getFirst();
        }
        assertThat(winner.generation()).isEqualTo(1);
        assertThat(importChunks.applyNextChunk(winner)).isEqualTo(1);
        assertThat(importChunks.applyNextChunk(winner)).isZero();
        assertThat(availableQuantity("VM-001")).isEqualTo(40);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE reference_id=:job")
                        .param("job", job.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void losingTheLeaseInsideAChunkRollsBackStockProofAndAfterCommitEffects() {
        var job = stageStockSnapshot(false);
        var claim = importWork.claim(job);
        double before = metrics.counter("vmarket.legacy.import.rows", "outcome", "applied")
                .count();
        redis.opsForValue().set("vmarket:catalog:metadata:v2", "lease-rollback-sentinel");
        jdbc.sql("""
                CREATE FUNCTION expire_lease_during_movement() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second'
                    WHERE job_id=NEW.reference_id::uuid;
                  RETURN NEW;
                END; $$
                """).update();
        try {
            jdbc.sql(
                            "CREATE TRIGGER expire_worker_lease AFTER INSERT ON inventory_movement FOR EACH ROW EXECUTE FUNCTION expire_lease_during_movement()")
                    .update();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> importChunks.applyNextChunk(claim))
                    .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
            assertThat(availableQuantity("VM-001")).isEqualTo(32);
            assertThat(jdbc.sql("SELECT price_minor FROM product WHERE sku='VM-001'")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(11000);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql(
                                    "SELECT COUNT(*) FROM staged_import_row WHERE job_id=:job AND (applied_at IS NOT NULL OR stock_movement_id IS NOT NULL)")
                            .param("job", job)
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql("SELECT applied_rows FROM migration_job WHERE id=:job")
                            .param("job", job)
                            .query(Integer.class)
                            .single())
                    .isZero();
            assertThat(metrics.counter("vmarket.legacy.import.rows", "outcome", "applied")
                            .count())
                    .isEqualTo(before);
            assertThat(redis.opsForValue().get("vmarket:catalog:metadata:v2")).isEqualTo("lease-rollback-sentinel");
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS expire_worker_lease ON inventory_movement")
                    .update();
            jdbc.sql("DROP FUNCTION expire_lease_during_movement()").update();
        }
    }

    @Test
    void expiredOwnershipCanBeFencedWithoutLettingTheOldWorkerAdoptOrFailTheNewGeneration() {
        var job = stageStockSnapshot(false);
        var old = importWork.claim(job);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importWork.claim(job))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
        jdbc.sql("UPDATE import_work_item SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE job_id=:job")
                .param("job", job)
                .update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importChunks.applyNextChunk(old))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
        assertThat(availableQuantity("VM-001")).isEqualTo(32);
        var current = importWork.takeOverExpired(job);
        assertThat(current.generation()).isEqualTo(old.generation() + 1);
        assertThat(current.token()).isNotEqualTo(old.token());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importWork.resolve(job, old.generation()))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> importChunks.applyNextChunk(old))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
        assertThat(importWork.failIfOwned(old)).isFalse();
        assertThat(jdbc.sql("SELECT status FROM migration_job WHERE id=:job")
                        .param("job", job)
                        .query(String.class)
                        .single())
                .isEqualTo("RUNNING");
        assertThat(importChunks.applyNextChunk(current)).isEqualTo(1);
        assertThat(importChunks.applyNextChunk(current)).isZero();
        assertThat(availableQuantity("VM-001")).isEqualTo(40);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE reference_id=:job")
                        .param("job", job.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(importWork.failIfOwned(old)).isFalse();
        assertThat(jdbc.sql("SELECT status FROM migration_job WHERE id=:job")
                        .param("job", job)
                        .query(String.class)
                        .single())
                .isEqualTo("COMPLETED");
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM import_work_item WHERE job_id=:job AND state='CLAIMED' AND generation=2 AND claim_token IS NOT NULL AND lease_until IS NOT NULL")
                        .param("job", job)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void workerTokensAreNotStoredInBatchParametersThatTheLauncherLogs() throws Exception {
        mockMvc.perform(
                        post("/api/ops/imports")
                                .with(operatorRequest())
                                .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                .header("X-V-Market-Ops-Secret", "local-development-only")
                                .header("X-Source-Name", "private-worker-token.csv")
                                .header("X-Source-Encoding", "UTF-8")
                                .contentType("text/csv")
                                .content(
                                        "sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,Private token jacket,32,11000\n"))
                .andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM batch_job_execution_params WHERE parameter_name='claimToken'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Autowired
    dev.vmarket.migration.ImportWorkService importWork;

    @Test
    void aChunkWithoutAWorkerClaimCannotChangeInventory() {
        var job = stageStockSnapshot(false);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> importChunks.applyNextChunk(new dev.vmarket.migration.ImportWorkClaim(job, null, 0)))
                .isInstanceOf(dev.vmarket.migration.ImportConflictException.class);
        assertThat(availableQuantity("VM-001")).isEqualTo(32);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void anUploadedImportHasADurableWorkRecordAndTerminalOutcome() throws Exception {
        var response = mockMvc.perform(post("/api/ops/imports")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .header("X-Source-Name", "durable-work.csv")
                        .header("X-Source-Encoding", "UTF-8")
                        .contentType("text/csv")
                        .content("sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,Durable jacket,32,11000\n"))
                .andExpect(status().isCreated())
                .andReturn();
        String job = objectMapper
                .readTree(response.getResponse().getContentAsString())
                .get("id")
                .asText();
        assertThat(jdbc.sql("SELECT to_regclass('public.import_work_item') IS NOT NULL")
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(jdbc.sql("SELECT state FROM import_work_item WHERE job_id=:job")
                        .param("job", java.util.UUID.fromString(job))
                        .query(String.class)
                        .single())
                .isEqualTo("FINISHED");
    }

    @Autowired
    javax.sql.DataSource dataSource;

    @Test
    void aSecondRestartIsRejectedPromptlyWhileTheFirstWorkerHoldsTheJobRow() throws Exception {
        var job = stageStockSnapshot(false);
        jdbc.sql("UPDATE migration_job SET status='FAILED' WHERE id=:job")
                .param("job", job)
                .update();
        jdbc.sql("UPDATE import_work_item SET state='FAILED' WHERE job_id=:job")
                .param("job", job)
                .update();
        jdbc.sql("""
                CREATE FUNCTION block_restart_movement() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_advisory_xact_lock(794381); RETURN NEW; END; $$
                """).update();
        try (var pool = Executors.newFixedThreadPool(2)) {
            jdbc.sql(
                            "CREATE TRIGGER restart_block BEFORE INSERT ON inventory_movement FOR EACH ROW EXECUTE FUNCTION block_restart_movement()")
                    .update();
            java.util.concurrent.Future<org.springframework.test.web.servlet.MvcResult> first;
            try (var blocker = dataSource.getConnection()) {
                blocker.setAutoCommit(false);
                int blockerPid;
                try (var statement = blocker.createStatement()) {
                    statement.execute("SELECT pg_advisory_xact_lock(794381)");
                    try (var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                        result.next();
                        blockerPid = result.getInt(1);
                    }
                }
                first = pool.submit(() -> mockMvc.perform(post("/api/ops/imports/{id}/restart", job)
                                .with(operatorRequest())
                                .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                .header("X-V-Market-Ops-Secret", "local-development-only"))
                        .andReturn());
                try {
                    org.awaitility.Awaitility.await()
                            .atMost(java.time.Duration.ofSeconds(10))
                            .until(() -> jdbc.sql(
                                            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE :pid=ANY(pg_blocking_pids(pid)))")
                                    .param("pid", blockerPid)
                                    .query(Boolean.class)
                                    .single());
                    var second = pool.submit(() -> mockMvc.perform(post("/api/ops/imports/{id}/restart", job)
                                    .with(operatorRequest())
                                    .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                    .header("X-V-Market-Ops-Secret", "local-development-only"))
                            .andReturn());
                    assertThat(second.get(3, java.util.concurrent.TimeUnit.SECONDS)
                                    .getResponse()
                                    .getStatus())
                            .isEqualTo(409);
                } finally {
                    blocker.rollback();
                }
            }
            assertThat(first.get(15, java.util.concurrent.TimeUnit.SECONDS)
                            .getResponse()
                            .getStatus())
                    .isEqualTo(200);
            assertThat(availableQuantity("VM-001")).isEqualTo(40);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE reference_id=:job")
                            .param("job", job.toString())
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS restart_block ON inventory_movement")
                    .update();
            jdbc.sql("DROP FUNCTION block_restart_movement()").update();
        }
    }

    @Autowired
    io.micrometer.prometheusmetrics.PrometheusMeterRegistry prometheus;

    @Test
    void batchObservationMetersDoNotCollideWithLegacyBatchMeters() throws Exception {
        var rejected = new java.util.concurrent.CopyOnWriteArrayList<String>();
        prometheus.config().onMeterRegistrationFailed((id, reason) -> rejected.add(id.getName() + ": " + reason));
        mockMvc.perform(post("/api/ops/imports")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .header("X-Source-Name", "meter-check.csv")
                        .header("X-Source-Encoding", "UTF-8")
                        .contentType("text/csv")
                        .content("sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,Meter jacket,32,11000\n"))
                .andExpect(status().isCreated());
        assertThat(rejected).isEmpty();
        assertThat(prometheus.scrape())
                .contains("vmarket_import_job_seconds_count", "vmarket_import_step_seconds_count");
    }

    @Test
    void aFailed120RowImportRestartsTheSameInstanceWithoutRepeatingCommittedMovements() throws Exception {
        jdbc.sql("""
                INSERT INTO product(id,vendor_id,sku,name,category,description,price_minor,image_path,image_alt)
                SELECT md5('restart-test-' || n)::uuid,'10000000-0000-0000-0000-000000000001',
                       'RESTART-' || lpad(n::text,3,'0'),'Restart item','OFFICE','Test data',100,'/test.jpg','Test'
                FROM generate_series(1,120) n
                """).update();
        jdbc.sql(
                        "INSERT INTO inventory_stock(product_id,on_hand) SELECT id,100 FROM product WHERE sku LIKE 'RESTART-%'")
                .update();
        var csv = new StringBuilder("schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n");
        for (int n = 1; n <= 120; n++) csv.append("2,RESTART-%03d,NAMI,Imported item,101,200,0\n".formatted(n));
        jdbc.sql("""
                CREATE FUNCTION fail_after_first_chunk() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.product_id=md5('restart-test-51')::uuid THEN RAISE EXCEPTION 'failure after fifty committed rows'; END IF;
                  RETURN NEW;
                END; $$
                """).update();
        String jobId;
        try {
            jdbc.sql(
                            "CREATE TRIGGER restart_chunk_failure BEFORE INSERT ON inventory_movement FOR EACH ROW EXECUTE FUNCTION fail_after_first_chunk()")
                    .update();
            var result = mockMvc.perform(post("/api/ops/imports")
                            .with(operatorRequest())
                            .header("X-V-Market-BFF-Secret", "local-bff-secret")
                            .header("X-V-Market-Ops-Secret", "local-development-only")
                            .header("X-Source-Name", "restart-120.csv")
                            .header("X-Source-Encoding", "UTF-8")
                            .contentType("text/csv")
                            .content(csv.toString()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.appliedRows").value(50))
                    .andReturn();
            jobId = objectMapper
                    .readTree(result.getResponse().getContentAsString())
                    .get("id")
                    .asText();
            assertThat(jdbc.sql("""
                    SELECT s.commit_count FROM batch_step_execution s JOIN batch_job_execution_params p
                    ON p.job_execution_id=s.job_execution_id WHERE p.parameter_name='jobId' AND p.parameter_value=:job
                    """).param("job", jobId).query(Long.class).single())
                    .isEqualTo(1);
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS restart_chunk_failure ON inventory_movement")
                    .update();
            jdbc.sql("DROP FUNCTION fail_after_first_chunk()").update();
        }
        for (int attempt = 0; attempt < 2; attempt++)
            mockMvc.perform(post("/api/ops/imports/{id}/restart", jobId)
                            .with(operatorRequest())
                            .header("X-V-Market-BFF-Secret", "local-bff-secret")
                            .header("X-V-Market-Ops-Secret", "local-development-only"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(jobId))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.appliedRows").value(120));
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM inventory_movement WHERE reference_id=:job AND movement_type='LEGACY_IMPORT'")
                        .param("job", jobId)
                        .query(Long.class)
                        .single())
                .isEqualTo(120);
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM inventory_stock s JOIN product p ON p.id=s.product_id WHERE p.sku LIKE 'RESTART-%' AND s.version=1 AND s.on_hand=101")
                        .query(Long.class)
                        .single())
                .isEqualTo(120);
        assertThat(jdbc.sql("""
                SELECT COUNT(DISTINCT e.job_instance_id) FROM batch_job_execution e JOIN batch_job_execution_params p
                ON p.job_execution_id=e.job_execution_id WHERE p.parameter_name='jobId' AND p.parameter_value=:job
                """).param("job", jobId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM batch_job_execution_params WHERE parameter_name='jobId' AND parameter_value=:job")
                        .param("job", jobId)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void aFailedBatchExecutionIsReportedAsFailedInsteadOfLeavingAStagedJob() throws Exception {
        jdbc.sql("""
                CREATE FUNCTION fail_restart_test_movement() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected restart test failure'; END; $$
                """).update();
        try {
            jdbc.sql(
                            "CREATE TRIGGER restart_test_failure BEFORE INSERT ON inventory_movement FOR EACH ROW EXECUTE FUNCTION fail_restart_test_movement()")
                    .update();
            mockMvc.perform(
                            post("/api/ops/imports")
                                    .with(operatorRequest())
                                    .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                    .header("X-V-Market-Ops-Secret", "local-development-only")
                                    .header("X-Source-Name", "restart-failure.csv")
                                    .header("X-Source-Encoding", "UTF-8")
                                    .contentType("text/csv")
                                    .content(
                                            "schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n2,VM-001,NAMI,Retry jacket,40,11000,0\n"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("FAILED"));
            assertThat(availableQuantity("VM-001")).isEqualTo(32);
            assertThat(jdbc.sql("SELECT status FROM batch_job_execution ORDER BY job_execution_id DESC LIMIT 1")
                            .query(String.class)
                            .single())
                    .isEqualTo("FAILED");
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS restart_test_failure ON inventory_movement")
                    .update();
            jdbc.sql("DROP FUNCTION fail_restart_test_movement()").update();
        }
    }

    @Test
    void checkoutAndStagedSnapshotOnlyProduceTheTwoLegalSerializedOutcomes() throws Exception {
        var job = stageStockSnapshot(false);
        var claim = importWork.claim(job);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var importing = pool.submit(() -> {
                assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                return importChunks.applyNextChunk(claim);
            });
            var selling = pool.submit(() -> {
                assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                return createOrder("snapshot-checkout-race", "VM-001", 1)
                        .getResponse()
                        .getStatus();
            });
            start.countDown();
            assertThat(importing.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(selling.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(201);
        }
        int applied = jdbc.sql("SELECT applied_rows FROM migration_job WHERE id=:job")
                .param("job", job)
                .query(Integer.class)
                .single();
        assertThat(applied).isIn(0, 1);
        assertThat(availableQuantity("VM-001")).isEqualTo(applied == 1 ? 39 : 31);
        assertThat(jdbc.sql("SELECT rejected_rows FROM migration_job WHERE id=:job")
                        .param("job", job)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1 - applied);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE movement_type='LEGACY_IMPORT'")
                        .query(Long.class)
                        .single())
                .isEqualTo(applied);
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM staged_import_row WHERE job_id=:job AND stock_movement_id IS NOT NULL AND applied_at IS NOT NULL AND applied_stock_version=1")
                        .param("job", job)
                        .query(Long.class)
                        .single())
                .isEqualTo(applied);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_order")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void stockPolicyMigrationPreservesHistoryWithoutInventingVersionProof() {
        var configuration = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("stock_upgrade")
                .defaultSchema("stock_upgrade");
        configuration.target("4").load().migrate();
        jdbc.sql("""
                UPDATE stock_upgrade.inventory_stock SET on_hand=25,version=9
                WHERE product_id='20000000-0000-0000-0000-000000000001'
                """).update();
        jdbc.sql("""
                INSERT INTO stock_upgrade.migration_job(id,source_name,file_checksum,encoding,status,total_rows,valid_rows,applied_rows)
                VALUES('00000000-0000-0000-0000-000000000501','historical.csv',repeat('b',64),'UTF-8','COMPLETED',1,1,1)
                """).update();
        jdbc.sql("""
                INSERT INTO stock_upgrade.staged_import_row(job_id,row_number,external_sku,product_name,vendor_code,on_hand,price_minor,valid,applied_at)
                VALUES('00000000-0000-0000-0000-000000000501',2,'VM-001','Historical','NAMI',32,11000,TRUE,NOW())
                """).update();
        jdbc.sql("""
                INSERT INTO stock_upgrade.inventory_movement(id,product_id,movement_type,quantity_delta,reason,reference_id)
                VALUES('00000000-0000-0000-0000-000000000502','20000000-0000-0000-0000-000000000001','LEGACY_IMPORT',0,'Historical import','00000000-0000-0000-0000-000000000501')
                """).update();
        jdbc.sql("""
                INSERT INTO stock_upgrade.reconciliation_run(id,migration_job_id,status,mismatch_count)
                VALUES('00000000-0000-0000-0000-000000000503','00000000-0000-0000-0000-000000000501','COMPLETED',0)
                """).update();
        configuration
                .target(org.flywaydb.core.api.MigrationVersion.LATEST)
                .load()
                .migrate();
        assertThat(jdbc.sql(
                                "SELECT on_hand FROM stock_upgrade.inventory_stock WHERE product_id='20000000-0000-0000-0000-000000000001'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(25);
        assertThat(jdbc.sql(
                                "SELECT version FROM stock_upgrade.inventory_stock WHERE product_id='20000000-0000-0000-0000-000000000001'")
                        .query(Long.class)
                        .single())
                .isEqualTo(9);
        assertThat(jdbc.sql("SELECT import_mode FROM stock_upgrade.migration_job")
                        .query(String.class)
                        .single())
                .isEqualTo("LEGACY_UNVERIFIED");
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM stock_upgrade.staged_import_row WHERE expected_stock_version IS NULL AND applied_stock_version IS NULL AND applied_on_hand IS NULL AND stock_movement_id IS NULL")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stock_upgrade.inventory_movement")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT stock_not_verified_rows FROM stock_upgrade.reconciliation_run")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Autowired
    dev.vmarket.migration.ImportChunkService importChunks;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry metrics;

    private java.util.UUID stageStockSnapshot(boolean twoRows) {
        var job = java.util.UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO migration_job(id,source_name,file_checksum,encoding,status,total_rows,valid_rows,import_mode)
                VALUES(:id,'staged-test.csv',:hash,'UTF-8','STAGED',:rows,:rows,'VERSIONED_STOCK')
                """)
                .param("id", job)
                .param("hash", job.toString().replace("-", "").repeat(2))
                .param("rows", twoRows ? 2 : 1)
                .update();
        jdbc.sql("INSERT INTO import_work_item(job_id) VALUES(:job)")
                .param("job", job)
                .update();
        jdbc.sql("""
                INSERT INTO staged_import_row(job_id,row_number,external_sku,product_name,vendor_code,on_hand,price_minor,valid,expected_stock_version)
                VALUES(:job,2,'VM-001','Snapshot jacket','NAMI',40,11200,TRUE,0)
                """).param("job", job).update();
        if (twoRows) jdbc.sql("""
                INSERT INTO staged_import_row(job_id,row_number,external_sku,product_name,vendor_code,on_hand,price_minor,valid,expected_stock_version)
                VALUES(:job,3,'VM-002','Snapshot organizer','RIVERBYTE',70,5900,TRUE,0)
                """).param("job", job).update();
        return job;
    }

    @Test
    void movementFailureRollsBackTheWholeChunkIncludingProofMetricsAndInvalidation() {
        var job = stageStockSnapshot(true);
        var claim = importWork.claim(job);
        var applied = metrics.counter("vmarket.legacy.import.rows", "outcome", "applied");
        double before = applied.count();
        redis.opsForValue().set("vmarket:catalog:metadata:v2", "rollback-sentinel");
        jdbc.sql("""
                CREATE FUNCTION fail_second_import_movement() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.movement_type='LEGACY_IMPORT' AND NEW.product_id='20000000-0000-0000-0000-000000000002'::uuid THEN
                    RAISE EXCEPTION 'injected second-row movement failure';
                  END IF;
                  RETURN NEW;
                END; $$
                """).update();
        try {
            jdbc.sql(
                            "CREATE TRIGGER test_import_failure BEFORE INSERT ON inventory_movement FOR EACH ROW EXECUTE FUNCTION fail_second_import_movement()")
                    .update();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> importChunks.applyNextChunk(claim))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasMessageContaining("injected second-row movement failure");
            assertThat(availableQuantity("VM-001")).isEqualTo(32);
            assertThat(availableQuantity("VM-002")).isEqualTo(58);
            assertThat(jdbc.sql("SELECT price_minor FROM product WHERE sku='VM-001'")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(11000);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE movement_type='LEGACY_IMPORT'")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql(
                                    "SELECT COUNT(*) FROM staged_import_row WHERE job_id=:job AND (applied_at IS NOT NULL OR stock_movement_id IS NOT NULL OR applied_product_version IS NOT NULL)")
                            .param("job", job)
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql("SELECT applied_rows FROM migration_job WHERE id=:job")
                            .param("job", job)
                            .query(Integer.class)
                            .single())
                    .isZero();
            assertThat(applied.count()).isEqualTo(before);
            assertThat(redis.opsForValue().get("vmarket:catalog:metadata:v2")).isEqualTo("rollback-sentinel");
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS test_import_failure ON inventory_movement")
                    .update();
            jdbc.sql("DROP FUNCTION fail_second_import_movement()").update();
        }
    }

    @Test
    void aNegativeStockVersionIsQuarantinedInsteadOfFailingTheWholeUpload() throws Exception {
        mockMvc.perform(
                        post("/api/ops/imports")
                                .with(operatorRequest())
                                .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                .header("X-V-Market-Ops-Secret", "local-development-only")
                                .header("X-Source-Name", "negative-version.csv")
                                .header("X-Source-Encoding", "UTF-8")
                                .contentType("text/csv")
                                .content(
                                        "schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n2,VM-001,NAMI,Invalid version,999,100,-1\n"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rejectedRows").value(1));
        assertThat(availableQuantity("VM-001")).isEqualTo(32);
    }

    @Test
    void unversionedLegacyFilesOnlyChangeMetadataAndCannotClaimStockVerification() throws Exception {
        var result = mockMvc.perform(post("/api/ops/imports")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .header("X-Source-Name", "metadata-only.csv")
                        .header("X-Source-Encoding", "UTF-8")
                        .contentType("text/csv")
                        .content("sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,Updated metadata,9999,11200\n"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importMode").value("METADATA_ONLY"))
                .andReturn();
        assertThat(availableQuantity("VM-001")).isEqualTo(32);
        assertThat(jdbc.sql("SELECT price_minor FROM product WHERE sku='VM-001'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(11200);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE movement_type='LEGACY_IMPORT'")
                        .query(Long.class)
                        .single())
                .isZero();
        String id = objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText();
        mockMvc.perform(post("/api/ops/reconciliations")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"migrationJobId\":\"" + id + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stockNotVerifiedRows").value(1))
                .andExpect(jsonPath("$.mismatchCount").value(0));
    }

    @Test
    void aLegitimateSaleAfterImportIsChangedSinceImportNotCorruption() throws Exception {
        var imported = mockMvc.perform(
                        post("/api/ops/imports")
                                .with(operatorRequest())
                                .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                .header("X-V-Market-Ops-Secret", "local-development-only")
                                .header("X-Source-Name", "sale-after-import.csv")
                                .header("X-Source-Encoding", "UTF-8")
                                .contentType("text/csv")
                                .content(
                                        "schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n2,VM-007,ORBIT,Magnetic cable dock,80,3600,0\n"))
                .andExpect(status().isCreated())
                .andReturn();
        String jobId = objectMapper
                .readTree(imported.getResponse().getContentAsString())
                .get("id")
                .asText();
        mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("Idempotency-Key", "sale-after-import")
                        .header("X-Checkout-Session", "c".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderPayload("VM-007", 2)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/ops/reconciliations")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"migrationJobId\":\"" + jobId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mismatchCount").value(0))
                .andExpect(jsonPath("$.changedSinceImportRows").value(1))
                .andExpect(jsonPath("$.stockNotVerifiedRows").value(1));
    }

    @Test
    void operatorsCanExportCurrentVersionsButAnonymousCallersCannot() throws Exception {
        String path = "/api/ops/inventory/snapshot";
        mockMvc.perform(get(path)
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only"))
                .andExpect(status().isUnauthorized());
        var result = mockMvc.perform(get(path)
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        assertThat(result.getContentAsString())
                .contains("schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version")
                .contains("2,VM-001,NAMI,AeroKnit travel jacket,32,11000,0");
    }

    @Test
    void duplicateCanonicalSkusInOneSnapshotAreAllQuarantined() throws Exception {
        mockMvc.perform(
                        post("/api/ops/imports")
                                .with(operatorRequest())
                                .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                .header("X-V-Market-Ops-Secret", "local-development-only")
                                .header("X-Source-Name", "duplicate-stock.csv")
                                .header("X-Source-Encoding", "UTF-8")
                                .contentType("text/csv")
                                .content(
                                        "schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n2,VM-001,NAMI,First,71,100,0\n2,vm-001,NAMI,Second,72,200,0\n"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appliedRows").value(0))
                .andExpect(jsonPath("$.rejectedRows").value(2));
        assertThat(jdbc.sql(
                                "SELECT on_hand FROM inventory_stock WHERE product_id=(SELECT id FROM product WHERE sku='VM-001')")
                        .query(Integer.class)
                        .single())
                .isEqualTo(32);
    }

    @Test
    void anOldVersionedStockSnapshotCannotRestoreAlreadySoldInventory() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("Idempotency-Key", "sale-before-old-snapshot")
                        .header("X-Checkout-Session", "c".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"customer":{"name":"Snapshot buyer","email":"snapshot@example.test","postalCode":"100-0001","prefecture":"Tokyo","city":"Chiyoda","addressLine":"1-1","paymentMethod":"COD","privacyAccepted":true},"lines":[{"sku":"VM-001","quantity":1}]}
                            """))
                .andExpect(status().isCreated());
        var result = mockMvc.perform(
                        post("/api/ops/imports")
                                .with(operatorRequest())
                                .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                .header("X-V-Market-Ops-Secret", "local-development-only")
                                .header("X-Source-Name", "old-snapshot.csv")
                                .header("X-Source-Encoding", "UTF-8")
                                .contentType("text/csv")
                                .content(
                                        "schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n2,VM-001,NAMI,Old snapshot name,32,100,0\n"))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(jdbc.sql(
                                "SELECT on_hand FROM inventory_stock WHERE product_id=(SELECT id FROM product WHERE sku='VM-001')")
                        .query(Integer.class)
                        .single())
                .isEqualTo(31);
        assertThat(jdbc.sql("SELECT price_minor FROM product WHERE sku='VM-001'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(11000);
        assertThat(objectMapper
                        .readTree(result.getResponse().getContentAsString())
                        .get("rejectedRows")
                        .asInt())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM inventory_movement WHERE movement_type='LEGACY_IMPORT'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Autowired
    org.springframework.session.jdbc.JdbcIndexedSessionRepository sessionRepository;

    @Test
    void concurrentSessionAttributeInsertionDoesNotFailARequest() {
        verifyConcurrentSessionAttributes(sessionRepository);
    }

    private <S extends org.springframework.session.Session> void verifyConcurrentSessionAttributes(
            org.springframework.session.SessionRepository<S> repository) {
        var session = repository.createSession();
        repository.save(session);
        var requestOne = repository.findById(session.getId());
        var requestTwo = repository.findById(session.getId());
        requestOne.setAttribute("concurrent-csrf", "one");
        requestTwo.setAttribute("concurrent-csrf", "two");
        repository.save(requestOne);
        repository.save(requestTwo);
        org.assertj.core.api.Assertions.assertThat(
                        (String) repository.findById(session.getId()).getAttribute("concurrent-csrf"))
                .isEqualTo("two");
    }

    @Test
    void operationsRequireAUserSessionEvenWithTrustedServiceCredentials() throws Exception {
        mockMvc.perform(get("/api/ops/imports")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/ops/reconciliations")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("vmarket.import-worker.enabled", () -> false);
        registry.add("auth.bootstrap.username", () -> "operator@example.test");
        registry.add(
                "auth.bootstrap.password-hash",
                () -> new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .encode("test-operator-password"));
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Test
    void repeatedFailedLoginsAreRateLimited() throws Exception {
        var bootstrap = mockMvc.perform(get("/api/auth/session").header("X-V-Market-BFF-Secret", "local-bff-secret"))
                .andExpect(status().isOk())
                .andReturn();
        String csrf = objectMapper
                .readTree(bootstrap.getResponse().getContentAsString())
                .get("csrfToken")
                .asText();
        var cookie = bootstrap.getResponse().getCookie("VMARKET_SESSION");
        for (int attempt = 0; attempt < 11; attempt++) {
            mockMvc.perform(post("/api/auth/login")
                            .header("X-V-Market-BFF-Secret", "local-bff-secret")
                            .header("X-CSRF-TOKEN", csrf)
                            .cookie(cookie)
                            .param("username", "unknown@example.test")
                            .param("password", "incorrect"))
                    .andExpect(attempt < 10 ? status().isUnauthorized() : status().isTooManyRequests());
        }
    }

    @Test
    void disablingAnOperatorRevokesAnExistingSession() throws Exception {
        var cookie = loginOperator();
        jdbc.sql("UPDATE auth_account SET enabled=FALSE WHERE username='operator@example.test'")
                .update();
        try {
            mockMvc.perform(get("/api/ops/imports")
                            .header("X-V-Market-BFF-Secret", "local-bff-secret")
                            .header("X-V-Market-Ops-Secret", "local-development-only")
                            .cookie(cookie))
                    .andExpect(status().isUnauthorized());
        } finally {
            jdbc.sql("UPDATE auth_account SET enabled=TRUE WHERE username='operator@example.test'")
                    .update();
        }
    }

    private jakarta.servlet.http.Cookie loginOperator() throws Exception {
        var bootstrap = mockMvc.perform(get("/api/auth/session").header("X-V-Market-BFF-Secret", "local-bff-secret"))
                .andExpect(status().isOk())
                .andReturn();
        String csrf = objectMapper
                .readTree(bootstrap.getResponse().getContentAsString())
                .get("csrfToken")
                .asText();
        return mockMvc.perform(post("/api/auth/login")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-CSRF-TOKEN", csrf)
                        .cookie(bootstrap.getResponse().getCookie("VMARKET_SESSION"))
                        .param("username", "operator@example.test")
                        .param("password", "test-operator-password"))
                .andExpect(status().isNoContent())
                .andReturn()
                .getResponse()
                .getCookie("VMARKET_SESSION");
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor operatorRequest() throws Exception {
        var cookie = loginOperator();
        var session = mockMvc.perform(
                        get("/api/auth/session").cookie(cookie).header("X-V-Market-BFF-Secret", "local-bff-secret"))
                .andExpect(status().isOk())
                .andReturn();
        String csrf = objectMapper
                .readTree(session.getResponse().getContentAsString())
                .get("csrfToken")
                .asText();
        return request -> {
            request.setCookies(cookie);
            request.addHeader("X-CSRF-TOKEN", csrf);
            return request;
        };
    }

    @Test
    void sessionsEnforceRoleCsrfAndLogout() throws Exception {
        var cookie = loginOperator();
        mockMvc.perform(post("/api/ops/reconciliations")
                        .cookie(cookie)
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        jdbc.sql("UPDATE auth_account SET role='VIEWER' WHERE username='operator@example.test'")
                .update();
        try {
            mockMvc.perform(get("/api/ops/imports")
                            .cookie(cookie)
                            .header("X-V-Market-BFF-Secret", "local-bff-secret")
                            .header("X-V-Market-Ops-Secret", "local-development-only"))
                    .andExpect(status().isForbidden());
        } finally {
            jdbc.sql("UPDATE auth_account SET role='OPERATOR' WHERE username='operator@example.test'")
                    .update();
        }
        var session = mockMvc.perform(
                        get("/api/auth/session").cookie(cookie).header("X-V-Market-BFF-Secret", "local-bff-secret"))
                .andExpect(status().isOk())
                .andReturn();
        String csrf = objectMapper
                .readTree(session.getResponse().getContentAsString())
                .get("csrfToken")
                .asText();
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(cookie)
                        .header("X-CSRF-TOKEN", csrf)
                        .header("X-V-Market-BFF-Secret", "local-bff-secret"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/ops/imports").cookie(cookie).header("X-V-Market-BFF-Secret", "local-bff-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void operatorLoginRotatesTheSessionAndAuthorizesOperations() throws Exception {
        var bootstrap = mockMvc.perform(get("/api/auth/session").header("X-V-Market-BFF-Secret", "local-bff-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andReturn();
        String csrf = objectMapper
                .readTree(bootstrap.getResponse().getContentAsString())
                .get("csrfToken")
                .asText();
        var beforeLogin = bootstrap.getResponse().getCookie("VMARKET_SESSION");
        assertThat(beforeLogin).isNotNull();
        var login = mockMvc.perform(post("/api/auth/login")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-CSRF-TOKEN", csrf)
                        .cookie(beforeLogin)
                        .param("username", "operator@example.test")
                        .param("password", "test-operator-password"))
                .andExpect(status().isNoContent())
                .andReturn();
        var authenticated = login.getResponse().getCookie("VMARKET_SESSION");
        assertThat(authenticated).isNotNull();
        assertThat(authenticated.getValue()).isNotEqualTo(beforeLogin.getValue());
        mockMvc.perform(get("/api/ops/imports")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .cookie(authenticated))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM spring_session WHERE principal_name='operator@example.test'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void clearCatalogCache() {
        // Remove only synthetic rows owned by the 120-row restart integration test; DB is disposable.
        jdbc.sql("DELETE FROM inventory_stock WHERE product_id IN (SELECT id FROM product WHERE sku LIKE 'RESTART-%')")
                .update();
        jdbc.sql("DELETE FROM product WHERE sku LIKE 'RESTART-%'").update();
        redis.delete("vmarket:catalog:metadata:v2");
    }

    @Test
    void contextProvidesCatalogApi() {
        assertThat(mockMvc).isNotNull();
    }

    @Test
    void mutationsRejectRequestsThatBypassTheCloudflareBff() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "bypass-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void catalogComesFromTheCanonicalDatabase() throws Exception {
        mockMvc.perform(get("/api/catalog/products"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$[0].sku").value("VM-001"))
                .andExpect(jsonPath("$[0].availableQuantity").value(32));
    }

    @Test
    void catalogResponseIsCachedInRedis() throws Exception {
        redis.delete("vmarket:catalog:metadata:v2");
        mockMvc.perform(get("/api/catalog/products")).andExpect(status().isOk());
        assertThat(redis.hasKey("vmarket:catalog:metadata:v2")).isTrue();
    }

    @Test
    void checkoutCreatesATrackedOrderAndConsumesInventoryAtomically() throws Exception {
        var payload = """
                {
                  "customer": {
                    "name": "Yuki Tanaka",
                    "email": "yuki@example.jp",
                    "postalCode": "100-0001",
                    "prefecture": "Tokyo",
                    "city": "Chiyoda-ku",
                    "addressLine": "Chiyoda 1-1",
                    "paymentMethod": "COD",
                    "privacyAccepted": true
                  },
                  "lines": [{"sku": "VM-003", "quantity": 2}]
                }
                """;

        mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("Idempotency-Key", "checkout-test-001")
                        .header("X-Checkout-Session", "c".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").isNotEmpty())
                .andExpect(jsonPath("$.trackingToken").isNotEmpty())
                .andExpect(jsonPath("$.grandTotalMinor").value(16848));

        var available = jdbc.sql("""
                        SELECT s.on_hand - s.reserved
                        FROM inventory_stock s JOIN product p ON p.id = s.product_id
                        WHERE p.sku = 'VM-003'
                        """).query(Integer.class).single();
        assertThat(available).isEqualTo(16);
    }

    @Test
    void idempotentCheckoutReturnsTheSameOrderWithoutDoubleDecrementingStock() throws Exception {
        var first = objectMapper.readTree(
                createOrder("idempotency-proof-001", "VM-004", 1).getResponse().getContentAsString());
        var retry = objectMapper.readTree(
                createOrder("idempotency-proof-001", "VM-004", 1).getResponse().getContentAsString());

        assertThat(retry.get("orderNumber").asText())
                .isEqualTo(first.get("orderNumber").asText());
        assertThat(availableQuantity("VM-004")).isEqualTo(43);
    }

    @Test
    void concurrentCheckoutCannotOversellInventory() throws Exception {
        Callable<Integer> request = () -> mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header(
                                "Idempotency-Key",
                                "oversell-" + Thread.currentThread().threadId())
                        .header("X-Checkout-Session", "c".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderPayload("VM-005", 20)))
                .andReturn()
                .getResponse()
                .getStatus();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var statuses = executor.invokeAll(List.of(request, request)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .sorted()
                    .toList();
            assertThat(statuses).containsExactly(201, 409);
            assertThat(availableQuantity("VM-005")).isEqualTo(7);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void simultaneousDuplicateCheckoutReturnsOneOrderAndConsumesStockOnce() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        Callable<String> request = () -> {
            start.await();
            return createOrder("same-key-concurrent", "VM-004", 1).getResponse().getContentAsString();
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            assertThat(objectMapper.readTree(first.get()).get("orderNumber").asText())
                    .isEqualTo(objectMapper
                            .readTree(second.get())
                            .get("orderNumber")
                            .asText());
        }
        assertThat(availableQuantity("VM-004")).isEqualTo(43);
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM customer_order WHERE checkout_scope_hash IS NOT NULL AND idempotency_key<>'same-key-concurrent'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void rejectsReusingACheckoutKeyForDifferentQuantities() throws Exception {
        createOrder("mismatched-checkout", "VM-004", 1);
        mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("Idempotency-Key", "mismatched-checkout")
                        .header("X-Checkout-Session", "c".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderPayload("VM-004", 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(availableQuantity("VM-004")).isEqualTo(43);
    }

    @Test
    void fingerprintMigrationPreservesAnExistingLegacyOrder() {
        var configuration = org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("checkout_upgrade")
                .defaultSchema("checkout_upgrade");
        configuration.target("2").load().migrate();
        jdbc.sql("""
                INSERT INTO checkout_upgrade.customer_order
                    (id, order_number, tracking_token_hash, idempotency_key, customer_name, customer_email,
                     postal_code, prefecture, city, address_line, payment_method,
                     subtotal_minor, shipping_minor, tax_minor, grand_total_minor)
                VALUES ('00000000-0000-0000-0000-000000000999', 'LEGACY-ORDER', repeat('a',64),
                    'legacy-key', 'Legacy Customer', 'legacy@example.test', '100-0001', 'Tokyo', 'Tokyo',
                    '1-1', 'COD', 1000, 900, 80, 1980)
                """).update();
        configuration
                .target(org.flywaydb.core.api.MigrationVersion.LATEST)
                .load()
                .migrate();
        assertThat(jdbc.sql(
                                "SELECT grand_total_minor FROM checkout_upgrade.customer_order WHERE order_number='LEGACY-ORDER'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1980);
        assertThat(jdbc.sql(
                                "SELECT COUNT(*) FROM checkout_upgrade.customer_order WHERE order_number='LEGACY-ORDER' AND request_hash IS NULL AND checkout_scope_hash IS NULL AND tracking_replay_ciphertext IS NULL AND idempotency_key='legacy-key' AND tracking_token_hash=repeat('a',64)")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void customerCanTrackAnOrderOnlyWithItsOpaqueToken() throws Exception {
        var created = mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("Idempotency-Key", "tracking-test-001")
                        .header("X-Checkout-Session", "c".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customer": {
                                    "name": "Aoi Sato", "email": "aoi@example.jp",
                                    "postalCode": "150-0001", "prefecture": "Tokyo",
                                    "city": "Shibuya-ku", "addressLine": "Jingumae 1-1",
                                    "paymentMethod": "INVOICE", "privacyAccepted": true
                                  },
                                  "lines": [{"sku": "VM-008", "quantity": 1}]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        var body = objectMapper.readTree(created.getResponse().getContentAsString());

        mockMvc.perform(get("/api/orders/{orderNumber}", body.get("orderNumber").asText())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .queryParam("trackingToken", body.get("trackingToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fulfillmentStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.items[0].sku").value("VM-008"));
    }

    @Test
    void operatorCanAdvanceFulfillmentWithAnAuditedTransition() throws Exception {
        var created = createOrder("ops-order-test-001", "VM-004", 1);
        var body = objectMapper.readTree(created.getResponse().getContentAsString());
        var orderNumber = body.get("orderNumber").asText();

        mockMvc.perform(patch("/api/ops/orders/{orderNumber}/fulfillment", orderNumber)
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PROCESSING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fulfillmentStatus").value("PROCESSING"));

        var auditCount = jdbc.sql("""
                        SELECT COUNT(*) FROM audit_event
                        WHERE action = 'FULFILLMENT_STATUS_CHANGED' AND aggregate_id = :orderNumber
                        """)
                .param("orderNumber", orderNumber)
                .query(Long.class)
                .single();
        assertThat(auditCount).isEqualTo(1);
        assertThat(jdbc.sql(
                                "SELECT actor FROM audit_event WHERE aggregate_id=:number AND action='FULFILLMENT_STATUS_CHANGED'")
                        .param("number", orderNumber)
                        .query(String.class)
                        .single())
                .isEqualTo("operator@example.test");
    }

    private org.springframework.test.web.servlet.MvcResult createOrder(String idempotencyKey, String sku, int quantity)
            throws Exception {
        return mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Checkout-Session", "c".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderPayload(sku, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private String orderPayload(String sku, int quantity) {
        return """
                {
                  "customer": {
                    "name": "Hana Ito", "email": "hana@example.jp",
                    "postalCode": "530-0001", "prefecture": "Osaka",
                    "city": "Kita-ku", "addressLine": "Umeda 1-1",
                    "paymentMethod": "COD", "privacyAccepted": true
                  },
                  "lines": [{"sku": "%s", "quantity": %d}]
                }
                """.formatted(sku, quantity);
    }

    private int availableQuantity(String sku) {
        return jdbc.sql("""
                        SELECT s.on_hand - s.reserved FROM inventory_stock s
                        JOIN product p ON p.id = s.product_id WHERE p.sku = :sku
                        """).param("sku", sku).query(Integer.class).single();
    }

    @Test
    void legacyImportAppliesValidRowsAndQuarantinesInvalidRows() throws Exception {
        var csv = """
                schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version
                2,VM-002,RIVERBYTE,Modular desk organizer,61,5700,0
                2,VM-404,NAMI,Unknown legacy item,-2,100,0
                """;

        mockMvc.perform(post("/api/ops/imports")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .header("X-Source-Name", "legacy-stock-2026-08-09.csv")
                        .header("X-Source-Encoding", "UTF-8")
                        .contentType("text/csv")
                        .content(csv))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED_WITH_ERRORS"))
                .andExpect(jsonPath("$.appliedRows").value(1))
                .andExpect(jsonPath("$.rejectedRows").value(1));

        var quantity = jdbc.sql("""
                        SELECT s.on_hand FROM inventory_stock s
                        JOIN product p ON p.id = s.product_id WHERE p.sku = 'VM-002'
                        """).query(Integer.class).single();
        assertThat(quantity).isEqualTo(61);
    }

    @Test
    void legacyImportDecodesJapaneseCp932Feeds() throws Exception {
        var csv = "sku,vendor_code,name,on_hand,price_minor\nVM-001,NAMI,軽量トラベルジャケット,33,11200\n";

        mockMvc.perform(post("/api/ops/imports")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .header("X-Source-Name", "japanese-catalog-cp932.csv")
                        .header("X-Source-Encoding", "CP932")
                        .contentType("text/csv")
                        .content(csv.getBytes(Charset.forName("windows-31j"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        var productName = jdbc.sql("SELECT name FROM product WHERE sku = 'VM-001'")
                .query(String.class)
                .single();
        assertThat(productName).isEqualTo("軽量トラベルジャケット");
    }

    @Test
    void reconciliationMakesPostMigrationDriftVisible() throws Exception {
        var importResult = mockMvc.perform(
                        post("/api/ops/imports")
                                .with(operatorRequest())
                                .header("X-V-Market-BFF-Secret", "local-bff-secret")
                                .header("X-V-Market-Ops-Secret", "local-development-only")
                                .header("X-Source-Name", "legacy-reconciliation.csv")
                                .header("X-Source-Encoding", "UTF-8")
                                .contentType("text/csv")
                                .content(
                                        "schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n2,VM-007,ORBIT,Magnetic cable dock,80,3600,0\n"))
                .andExpect(status().isCreated())
                .andReturn();
        String jobId = objectMapper
                .readTree(importResult.getResponse().getContentAsString())
                .get("id")
                .asText();

        jdbc.sql("""
                        UPDATE inventory_stock SET on_hand = 79
                        WHERE product_id = (SELECT id FROM product WHERE sku = 'VM-007')
                        """).update();

        mockMvc.perform(post("/api/ops/reconciliations")
                        .with(operatorRequest())
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"migrationJobId\":\"" + jobId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mismatchCount").value(1))
                .andExpect(jsonPath("$.discrepancies[0].type").value("QUANTITY_MISMATCH"));
    }
}
