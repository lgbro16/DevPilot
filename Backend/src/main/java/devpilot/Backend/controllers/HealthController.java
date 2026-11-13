package devpilot.Backend.controllers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import devpilot.Backend.config.AiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;

    @Value("${spring.ai.google.genai.api-key:}")
    private String geminiApiKey;

    @Value("${spring.ai.google.genai.chat.model:gemini-3.5-flash-lite}")
    private String chatModel;

    @Value("${spring.ai.google.genai.embedding.text.model:gemini-embedding-2}")
    private String embeddingModelName;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getHealth() {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> components = new HashMap<>();
        boolean allHealthy = true;

        // 1. PostgreSQL Database Check
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            components.put("database", Map.of(
                    "status", result != null && result == 1 ? "UP" : "DOWN",
                    "details", "PostgreSQL connection active"));
        } catch (Exception ex) {
            allHealthy = false;
            components.put("database", Map.of(
                    "status", "DOWN",
                    "error", ex.getMessage()));
        }

        // 2. pgvector Extension Check
        try {
            String vectorVersion = jdbcTemplate.queryForObject(
                    "SELECT installed_version FROM pg_available_extensions WHERE name = 'vector'",
                    String.class);
            boolean installed = vectorVersion != null && !vectorVersion.isBlank();
            if (!installed) allHealthy = false;
            components.put("pgvector", Map.of(
                    "status", installed ? "UP" : "DOWN",
                    "version", installed ? vectorVersion : "NOT_INSTALLED"));
        } catch (Exception ex) {
            allHealthy = false;
            components.put("pgvector", Map.of(
                    "status", "DOWN",
                    "error", ex.getMessage()));
        }

        // 3. vector_store Schema Check
        try {
            String query = """
                    SELECT atttypmod
                    FROM pg_attribute
                    WHERE attrelid = 'public.vector_store'::regclass
                      AND attname = 'embedding'
                      AND NOT attisdropped
                    """;
            Integer typmod = jdbcTemplate.query(query, rs -> rs.next() ? rs.getInt(1) : null);
            components.put("vectorStoreTable", Map.of(
                    "status", typmod != null ? "UP" : "PENDING_INITIALIZATION",
                    "dimension", typmod != null ? typmod : AiConfig.EMBEDDING_DIMENSION,
                    "expectedDimension", AiConfig.EMBEDDING_DIMENSION));
        } catch (Exception ex) {
            components.put("vectorStoreTable", Map.of(
                    "status", "PENDING_INITIALIZATION",
                    "details", "Table will be auto-created upon first vector write"));
        }

        // 4. Gemini Configuration & Embedding Model Check
        boolean apiKeyConfigured = geminiApiKey != null && !geminiApiKey.isBlank() && !geminiApiKey.contains("dummy");
        components.put("geminiChat", Map.of(
                "status", apiKeyConfigured ? "UP" : "UNCONFIGURED_KEY",
                "model", chatModel));

        components.put("geminiEmbedding", Map.of(
                "status", apiKeyConfigured ? "UP" : "UNCONFIGURED_KEY",
                "model", embeddingModelName,
                "enforcedDimensions", embeddingModel.dimensions()));

        response.put("status", allHealthy ? "UP" : "DEGRADED");
        response.put("components", components);
        response.put("timestamp", Instant.now().toString());

        return ResponseEntity.ok(response);
    }
}

