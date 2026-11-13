package devpilot.Backend.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import tools.jackson.databind.json.JsonMapper;

class CodeContextRetrieverTest {

    private VectorStore mockVectorStore;
    private CitationMapper citationMapper;
    private CodeContextRetriever retriever;

    @BeforeEach
    void setUp() {
        mockVectorStore = mock(VectorStore.class);
        citationMapper = new CitationMapper(new JsonMapper());
        retriever = new CodeContextRetriever(mockVectorStore, citationMapper);
    }

    @Test
    @DisplayName("Should return NO_MATCHES when similarity search returns empty")
    void shouldHandleEmptyResults() {
        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RetrievedContext result = retriever.retrieve(UUID.randomUUID(), "How does authentication work?");

        assertThat(result.contextText()).isEqualTo(CodeContextRetriever.NO_MATCHES);
        assertThat(result.citations()).isEmpty();
    }

    @Test
    @DisplayName("Should format context and map citations when chunks are found")
    void shouldFormatContextAndCitations() {
        UUID repoId = UUID.randomUUID();
        Document doc1 = new Document("doc1", "class AuthController { ... }", Map.of(
                "filePath", "src/AuthController.java",
                "language", "Java"
        ));
        when(mockVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc1));

        RetrievedContext result = retriever.retrieve(repoId, "auth");

        assertThat(result.contextText()).contains("class AuthController");
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).filePath()).isEqualTo("src/AuthController.java");
    }
}

