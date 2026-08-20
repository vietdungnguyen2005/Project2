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
@Sql(scripts = "/reset-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VMarketApplicationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
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
        redis.delete("vmarket:catalog:v1");
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
        redis.delete("vmarket:catalog:v1");
        mockMvc.perform(get("/api/catalog/products")).andExpect(status().isOk());
        assertThat(redis.hasKey("vmarket:catalog:v1")).isTrue();
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
    void customerCanTrackAnOrderOnlyWithItsOpaqueToken() throws Exception {
        var created = mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("Idempotency-Key", "tracking-test-001")
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
    }

    private org.springframework.test.web.servlet.MvcResult createOrder(String idempotencyKey, String sku, int quantity)
            throws Exception {
        return mockMvc.perform(post("/api/orders")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("Idempotency-Key", idempotencyKey)
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
                sku,vendor_code,name,on_hand,price_minor
                VM-002,RIVERBYTE,Modular desk organizer,61,5700
                VM-404,NAMI,Unknown legacy item,-2,100
                """;

        mockMvc.perform(post("/api/ops/imports")
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
        var importResult = mockMvc.perform(post("/api/ops/imports")
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .header("X-Source-Name", "legacy-reconciliation.csv")
                        .header("X-Source-Encoding", "UTF-8")
                        .contentType("text/csv")
                        .content(
                                "sku,vendor_code,name,on_hand,price_minor\nVM-007,ORBIT,Magnetic cable dock,80,3600\n"))
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
                        .header("X-V-Market-BFF-Secret", "local-bff-secret")
                        .header("X-V-Market-Ops-Secret", "local-development-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"migrationJobId\":\"" + jobId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mismatchCount").value(1))
                .andExpect(jsonPath("$.discrepancies[0].type").value("QUANTITY_MISMATCH"));
    }
}
