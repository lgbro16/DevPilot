package devpilot.Backend.service.ai;

import java.util.Arrays;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Decorator around an EmbeddingModel that enforces:
 * 1. Strict vector dimension matching (1536 dimensions).
 * 2. Automatic rate-limiting and retry on HTTP 429 / transient failures.
 * 3. Prevention of vector truncation or silent zero-padding.
 */
@Slf4j
public class DimensionEnforcingEmbeddingModel implements EmbeddingModel {

    public static final int EXPECTED_DIMENSIONS = 1536;

    private final EmbeddingModel delegate;
    private final GeminiRateLimiter rateLimiter;
    private final int expectedDimensions;

    public DimensionEnforcingEmbeddingModel(EmbeddingModel delegate, GeminiRateLimiter rateLimiter, int expectedDimensions) {
        this.delegate = delegate;
        this.rateLimiter = rateLimiter;
        this.expectedDimensions = expectedDimensions;
    }

    public DimensionEnforcingEmbeddingModel(EmbeddingModel delegate, GeminiRateLimiter rateLimiter) {
        this(delegate, rateLimiter, EXPECTED_DIMENSIONS);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        try {
            return rateLimiter.executeWithRetry("EmbeddingModel.call", () -> {
                EmbeddingResponse response = delegate.call(request);
                validateResponse(response);
                return response;
            });
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate embeddings: " + ex.getMessage(), ex);
        }
    }

    @Override
    public float[] embed(String text) {
        try {
            return rateLimiter.executeWithRetry("EmbeddingModel.embed(text)", () -> {
                float[] vector = delegate.embed(text);
                validateVector(vector, "single text embedding");
                return vector;
            });
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate embedding for text: " + ex.getMessage(), ex);
        }
    }

    @Override
    public float[] embed(Document document) {
        try {
            return rateLimiter.executeWithRetry("EmbeddingModel.embed(document)", () -> {
                float[] vector = delegate.embed(document);
                validateVector(vector, "document embedding");
                return vector;
            });
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate embedding for document: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        try {
            return rateLimiter.executeWithRetry("EmbeddingModel.embed(texts)", () -> {
                List<float[]> vectors = delegate.embed(texts);
                for (int i = 0; i < vectors.size(); i++) {
                    validateVector(vectors.get(i), "text embedding at index " + i);
                }
                return vectors;
            });
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate embeddings for texts: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        try {
            return rateLimiter.executeWithRetry("EmbeddingModel.embed(documents, options, batchingStrategy)", () -> {
                List<float[]> vectors = delegate.embed(documents, options, batchingStrategy);
                for (int i = 0; i < vectors.size(); i++) {
                    validateVector(vectors.get(i), "document embedding at index " + i);
                }
                return vectors;
            });
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate embeddings for batch: " + ex.getMessage(), ex);
        }
    }

    @Override
    public EmbeddingResponse embedForResponse(List<String> texts) {
        try {
            return rateLimiter.executeWithRetry("EmbeddingModel.embedForResponse", () -> {
                EmbeddingResponse response = delegate.embedForResponse(texts);
                validateResponse(response);
                return response;
            });
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate embedding response: " + ex.getMessage(), ex);
        }
    }

    @Override
    public String getEmbeddingContent(Document document) {
        return delegate.getEmbeddingContent(document);
    }

    @Override
    public int dimensions() {
        return this.expectedDimensions;
    }

    private void validateResponse(EmbeddingResponse response) {
        if (response == null || response.getResults() == null) return;
        List<Embedding> results = response.getResults();
        for (int i = 0; i < results.size(); i++) {
            Embedding embedding = results.get(i);
            validateVector(embedding.getOutput(), "response result at index " + i);
        }
    }

    private void validateVector(float[] vector, String context) {
        if (vector == null) {
            throw new IllegalStateException("Embedding vector is null in " + context);
        }
        if (vector.length != expectedDimensions) {
            String error = String.format(
                    "CRITICAL EMBEDDING DIMENSION MISMATCH in %s: expected %d dimensions, but model returned %d. "
                    + "Vector insertion aborted to preserve pgvector schema integrity.",
                    context, expectedDimensions, vector.length);
            log.error(error);
            throw new IllegalStateException(error);
        }
    }
}
