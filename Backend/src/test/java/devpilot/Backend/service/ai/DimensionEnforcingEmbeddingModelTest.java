package devpilot.Backend.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

class DimensionEnforcingEmbeddingModelTest {

    private EmbeddingModel mockDelegate;
    private GeminiRateLimiter rateLimiter;
    private DimensionEnforcingEmbeddingModel model;

    @BeforeEach
    void setUp() {
        mockDelegate = mock(EmbeddingModel.class);
        rateLimiter = new GeminiRateLimiter(60);
        model = new DimensionEnforcingEmbeddingModel(mockDelegate, rateLimiter, 1536);
    }

    @Test
    @DisplayName("Should return 1536 when dimensions() is called")
    void shouldReport1536Dimensions() {
        assertThat(model.dimensions()).isEqualTo(1536);
    }

    @Test
    @DisplayName("Should pass when model returns exactly 1536 dimensions")
    void shouldPassWhenVectorIs1536() {
        float[] vector1536 = new float[1536];
        when(mockDelegate.embed("test text")).thenReturn(vector1536);

        float[] result = model.embed("test text");
        assertThat(result).hasSize(1536);
    }

    @Test
    @DisplayName("Should throw IllegalStateException when model returns 3072 dimensions instead of 1536")
    void shouldFailWhenVectorIs3072() {
        float[] vector3072 = new float[3072];
        when(mockDelegate.embed("test text")).thenReturn(vector3072);

        assertThatThrownBy(() -> model.embed("test text"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 1536 dimensions, but model returned 3072");
    }

    @Test
    @DisplayName("Should validate all vectors in batch response")
    void shouldValidateBatchEmbeddingResponse() {
        float[] valid = new float[1536];
        float[] invalid = new float[3072];

        Embedding e1 = new Embedding(valid, 0);
        Embedding e2 = new Embedding(invalid, 1);
        EmbeddingResponse response = new EmbeddingResponse(List.of(e1, e2));

        when(mockDelegate.call(any(EmbeddingRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> model.call(new EmbeddingRequest(List.of("t1", "t2"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 1536 dimensions, but model returned 3072");
    }
}

