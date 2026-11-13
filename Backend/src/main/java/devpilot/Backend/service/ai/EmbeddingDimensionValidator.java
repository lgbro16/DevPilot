package devpilot.Backend.service.ai;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import devpilot.Backend.config.AiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Startup diagnostic runner that verifies embedding model and PostgreSQL pgvector
 * dimension compatibility before serving requests.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmbeddingDimensionValidator implements ApplicationRunner {

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;

    @Value("${spring.ai.google.genai.embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${app.embedding.validate-on-startup:true}")
    private boolean validateOnStartup;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[DIAGNOSTIC] Starting DevPilot Embedding and PgVector validation...");

        // 1. Verify PostgreSQL pgvector schema compatibility if database is reachable
        verifyDatabaseVectorSchema();

        // 2. Perform live embedding dimension test if enabled and API key is present
        if (!validateOnStartup) {
            log.info("[DIAGNOSTIC] Startup live embedding validation is disabled via configuration.");
            return;
        }

        if (embeddingApiKey == null || embeddingApiKey.isBlank() || embeddingApiKey.contains("dummy")) {
            log.warn("[DIAGNOSTIC] Gemini API key not configured or using dummy key. Skipping live embedding probe on startup.");
            return;
        }

        try {
            log.info("[DIAGNOSTIC] Probing Gemini Embedding API for dimension verification...");
            float[] testEmbedding = embeddingModel.embed("DevPilot dimension test");
            if (testEmbedding == null || testEmbedding.length != AiConfig.EMBEDDING_DIMENSION) {
                int actual = testEmbedding == null ? 0 : testEmbedding.length;
                String error = String.format(
                        "CRITICAL STARTUP ERROR: Embedding model returned %d dimensions, but pgvector schema expects %d! "
                        + "Application startup aborted to prevent database corruption.",
                        actual, AiConfig.EMBEDDING_DIMENSION);
                log.error(error);
                throw new IllegalStateException(error);
            }
            log.info("[DIAGNOSTIC] PASSED: Gemini Embedding 2 returned exactly {} dimensions matching pgvector schema.", testEmbedding.length);
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Exception ex) {
            log.warn("[DIAGNOSTIC] Could not complete live startup embedding probe: {}. DimensionEnforcingEmbeddingModel will enforce {} dimensions on all runtime requests.",
                    ex.getMessage(), AiConfig.EMBEDDING_DIMENSION);
        }
    }

    private void verifyDatabaseVectorSchema() {
        try {
            // Check if vector_store table exists and query the dimension of its embedding column
            String query = """
                    SELECT atttypmod
                    FROM pg_attribute
                    WHERE attrelid = 'public.vector_store'::regclass
                      AND attname = 'embedding'
                      AND NOT attisdropped
                    """;
            Integer typmod = jdbcTemplate.query(query, rs -> rs.next() ? rs.getInt(1) : null);
            if (typmod != null) {
                if (typmod != AiConfig.EMBEDDING_DIMENSION) {
                    log.error("[DIAGNOSTIC] PostgreSQL vector_store.embedding column has dimension {}, expected {}!",
                            typmod, AiConfig.EMBEDDING_DIMENSION);
                } else {
                    log.info("[DIAGNOSTIC] PASSED: PostgreSQL public.vector_store table verified with vector({}).", typmod);
                }
            } else {
                log.info("[DIAGNOSTIC] public.vector_store table does not exist yet; will be initialized on first vector operation.");
            }
        } catch (Exception ex) {
            log.debug("[DIAGNOSTIC] Database schema probe note: {}", ex.getMessage());
        }
    }
}

