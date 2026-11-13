package devpilot.Backend.service.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class CodeChunkerTest {

    private CodeChunker codeChunker;

    @BeforeEach
    void setUp() {
        CodeFileFilter filter = new CodeFileFilter();
        codeChunker = new CodeChunker(800, filter);
    }

    @Test
    @DisplayName("Should generate deterministic chunk IDs and include fileSha in metadata")
    void shouldGenerateDeterministicChunkIds() {
        String repoId = "11111111-1111-1111-1111-111111111111";
        String filePath = "src/main/java/App.java";
        String fileSha = "abc123456789";
        String content = "public class App { public static void main(String[] args) { System.out.println(\"Hello\"); } }";

        List<Document> chunksRun1 = codeChunker.chunkFile(repoId, filePath, fileSha, content);
        List<Document> chunksRun2 = codeChunker.chunkFile(repoId, filePath, fileSha, content);

        assertThat(chunksRun1).isNotEmpty();
        assertThat(chunksRun1).hasSameSizeAs(chunksRun2);

        // Verify deterministic IDs
        for (int i = 0; i < chunksRun1.size(); i++) {
            Document doc1 = chunksRun1.get(i);
            Document doc2 = chunksRun2.get(i);
            assertThat(doc1.getId()).isEqualTo(doc2.getId());
            assertThat(doc1.getMetadata().get("fileSha")).isEqualTo(fileSha);
            assertThat(doc1.getMetadata().get("filePath")).isEqualTo(filePath);
            assertThat(doc1.getMetadata().get("parentDocId")).isNotNull();
            assertThat(doc1.getMetadata().get("chunkIndex")).isEqualTo(i);
        }
    }
}

