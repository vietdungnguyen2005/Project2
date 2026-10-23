package dev.vmarket.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationProfileService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public IntegrationProfileService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Profile(
            String profileId,
            int version,
            String encoding,
            String vendorCode,
            Map<String, String> mapping,
            String skuNormalization,
            boolean active,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {
        @com.fasterxml.jackson.annotation.JsonProperty
        public List<String> requiredFields() {
            return List.of("sku", "name", "on_hand");
        }

        @com.fasterxml.jackson.annotation.JsonProperty
        public Map<String, String> normalization() {
            return Map.of("sku", skuNormalization, "name", "TRIM", "on_hand", "TRIM_INTEGER");
        }

        @com.fasterxml.jackson.annotation.JsonProperty
        public Map<String, ?> validation() {
            return Map.of(
                    "sku",
                    Map.of("type", "string", "minLength", 1, "maxLength", 64),
                    "name",
                    Map.of("type", "string", "minLength", 1, "maxLength", 160),
                    "on_hand",
                    Map.of("type", "integer", "min", 0, "max", 1_000_000));
        }
    }

    public record Definition(
            String profileId,
            int version,
            String encoding,
            String vendorCode,
            Map<String, String> mapping,
            String skuNormalization) {}

    private static final String SELECT = "SELECT * FROM integration_profile";

    public Profile find(String id, int version, boolean lock) {
        return jdbc.sql(SELECT + " WHERE profile_id=:id AND version=:version" + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .param("version", version)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new InvalidImportException("Profile version does not exist."));
    }

    public Profile requireActive(String id, int version, boolean lock) {
        Profile profile = find(id, version, lock);
        if (!profile.active())
            throw new ImportConflictException(
                    "Profile version is inactive. Select an active version and preview again.");
        return profile;
    }

    @Transactional
    public Profile publish(Definition d, String actor) {
        if (d.profileId() == null
                || !d.profileId().matches("[a-z][a-z0-9-]{0,47}")
                || d.version() < 1
                || !List.of("UTF-8", "CP932").contains(d.encoding() == null ? "" : d.encoding())
                || !List.of("TRIM", "TRIM_UPPER").contains(d.skuNormalization() == null ? "" : d.skuNormalization())
                || d.mapping() == null
                || !d.mapping().keySet().equals(java.util.Set.of("sku", "name", "on_hand"))
                || d.mapping().values().stream()
                        .anyMatch(v -> v == null
                                || v.isBlank()
                                || v.length() > 80
                                || !v.equals(v.trim())
                                || v.chars().anyMatch(Character::isISOControl))
                || new java.util.HashSet<>(d.mapping().values()).size() != 3) {
            throw new InvalidImportException(
                    "Use a valid profile id/version, UTF-8 or CP932, TRIM or TRIM_UPPER, and three distinct sku/name/on_hand source headers (1–80 characters).");
        }
        if (d.vendorCode() == null
                || !jdbc.sql("SELECT EXISTS(SELECT 1 FROM vendor WHERE code=:code AND active=TRUE)")
                        .param("code", d.vendorCode())
                        .query(Boolean.class)
                        .single()) throw new InvalidImportException("Select an existing active canonical vendor code.");
        int inserted = jdbc.sql("""
                INSERT INTO integration_profile(profile_id,version,encoding,vendor_code,mapping,sku_normalization,created_by,updated_by)
                VALUES(:id,:version,:encoding,:vendor,CAST(:mapping AS jsonb),:rule,:actor,:actor)
                ON CONFLICT (profile_id,version) DO NOTHING
                """)
                .param("id", d.profileId())
                .param("version", d.version())
                .param("encoding", d.encoding())
                .param("vendor", d.vendorCode())
                .param("mapping", writeJson(d.mapping()))
                .param("rule", d.skuNormalization())
                .param("actor", actor)
                .update();
        if (inserted != 1) throw new ImportConflictException("Profile versions are immutable. Publish a new version.");
        audit(d.profileId(), d.version(), actor, "PROFILE_PUBLISHED", Map.of("active", false));
        return find(d.profileId(), d.version(), false);
    }

    @Transactional
    public Profile activate(String id, int version, boolean active, String actor) {
        var profile = find(id, version, true);
        if (profile.active() != active) {
            jdbc.sql(
                            "UPDATE integration_profile SET active=:active,updated_by=:actor,updated_at=NOW() WHERE profile_id=:id AND version=:version")
                    .param("id", id)
                    .param("version", version)
                    .param("active", active)
                    .param("actor", actor)
                    .update();
            audit(id, version, actor, "PROFILE_ACTIVATION_CHANGED", Map.of("active", active));
        }
        return find(id, version, false);
    }

    void audit(String id, int version, String actor, String action, Map<String, ?> details) {
        jdbc.sql("""
                INSERT INTO audit_event(id,actor,action,aggregate_type,aggregate_id,details)
                VALUES(:event,:actor,:action,'INTEGRATION_PROFILE',:id,CAST(:details AS jsonb))
                """)
                .param("event", java.util.UUID.randomUUID())
                .param("actor", actor)
                .param("action", action)
                .param("id", id + ":" + version)
                .param("details", writeJson(details))
                .update();
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Profile serialization failed", failure);
        }
    }

    public List<Profile> recent() {
        return jdbc.sql(SELECT + " ORDER BY profile_id,version DESC LIMIT 100")
                .query(this::map)
                .list();
    }

    private Profile map(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new Profile(
                rs.getString("profile_id"),
                rs.getInt("version"),
                rs.getString("encoding"),
                rs.getString("vendor_code"),
                readMapping(rs.getString("mapping")),
                rs.getString("sku_normalization"),
                rs.getBoolean("active"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("updated_by"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Map<String, String> readMapping(String value) {
        try {
            return Map.copyOf(json.readValue(value, new TypeReference<Map<String, String>>() {}));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Stored profile mapping is invalid", failure);
        }
    }
}
