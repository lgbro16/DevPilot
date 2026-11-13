package devpilot.Backend.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationConvention;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.retry.RetryTemplate;

import devpilot.Backend.service.ai.DimensionEnforcingEmbeddingModel;
import devpilot.Backend.service.ai.GeminiRateLimiter;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Explicit configuration for Spring AI Google GenAI models and PgVector integration.
 * Enforces 1536-dimensional embeddings for Gemini Embedding 2 to maintain strict
 * PostgreSQL pgvector schema compatibility.
 */
@Configuration
@Slf4j
public class AiConfig {

    public static final int EMBEDDING_DIMENSION = 1536;

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(
            GoogleGenAiEmbeddingConnectionDetails connectionDetails,
            GeminiRateLimiter rateLimiter,
            @Value("${spring.ai.google.genai.embedding.text.model:gemini-embedding-2}") String model,
            @Value("${spring.ai.google.genai.embedding.text.dimensions:1536}") int dimensions,
            ObjectProvider<RetryTemplate> retryTemplateProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<EmbeddingModelObservationConvention> observationConventionProvider) {

        log.info("Configuring primary GoogleGenAiTextEmbeddingModel: model='{}', target dimensions={}", model, dimensions);

        GoogleGenAiTextEmbeddingOptions options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(model)
                .dimensions(dimensions)
                .build();

        RetryTemplate retryTemplate = retryTemplateProvider.getIfUnique(() -> org.springframework.ai.retry.RetryUtils.DEFAULT_RETRY_TEMPLATE);
        ObservationRegistry observationRegistry = observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP);

        GoogleGenAiTextEmbeddingModel rawModel = new GoogleGenAiTextEmbeddingModel(
                connectionDetails,
                options,
                retryTemplate,
                observationRegistry
        );

        observationConventionProvider.ifAvailable(rawModel::setObservationConvention);

        DimensionEnforcingEmbeddingModel enforcingModel = new DimensionEnforcingEmbeddingModel(rawModel, rateLimiter, dimensions);
        log.info("Wrapped embedding model in DimensionEnforcingEmbeddingModel with strict {} dimension enforcement", dimensions);
        return enforcingModel;
    }
}

