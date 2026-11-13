package devpilot.Backend.service.indexing;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import devpilot.Backend.service.ai.RagSettings;

@Component
public class CodeChunker {
    private final TokenTextSplitter splitter;
    private final CodeFileFilter fileFilter;

    public CodeChunker(
            @Value("${app.indexing.chunk-size:800}") int chunkSize,
            CodeFileFilter fileFilter) {
        int chunkTokens = Math.max(50, chunkSize / 4);
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkTokens)
                .build();
        this.fileFilter = fileFilter;
    }

    public List<Document> chunkFile(String repoId, String filePath, String fileSha, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String language = fileFilter.detectLanguage(filePath);
        String header = "// File: " + filePath + "\n";
        String parentDocId = UUID.nameUUIDFromBytes((repoId + ":" + filePath).getBytes(StandardCharsets.UTF_8)).toString();

        Document source = new Document(header + content, baseMetadata(repoId, filePath, fileSha, language, parentDocId));
        List<Document> split = splitter.apply(List.of(source));

        return IntStream.range(0, split.size())
                .mapToObj(i -> withChunkIndex(split.get(i), repoId, filePath, fileSha, language, parentDocId, i, split.size()))
                .toList();
    }

    public List<Document> chunkFile(String repoId, String filePath, String content) {
        return chunkFile(repoId, filePath, "", content);
    }

    private static Map<String, Object> baseMetadata(String repoId, String filePath, String fileSha, String language, String parentDocId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(RagSettings.METADATA_REPO_ID, repoId);
        metadata.put("filePath", filePath);
        metadata.put("fileSha", fileSha != null ? fileSha : "");
        metadata.put("language", language);
        metadata.put("parentDocId", parentDocId);
        return metadata;
    }

    private static Document withChunkIndex(
            Document chunk,
            String repoId,
            String filePath,
            String fileSha,
            String language,
            String parentDocId,
            int chunkIndex,
            int totalChunks) {
        Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
        metadata.put(RagSettings.METADATA_REPO_ID, repoId);
        metadata.put("filePath", filePath);
        metadata.put("fileSha", fileSha != null ? fileSha : "");
        metadata.put("language", language);
        metadata.put("parentDocId", parentDocId);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("totalChunks", totalChunks);

        // Generate deterministic document ID so re-indexing or re-chunking upserts cleanly via ON CONFLICT (id) DO UPDATE
        String docId = UUID.nameUUIDFromBytes(
                (repoId + ":" + filePath + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8)
        ).toString();

        return new Document(docId, chunk.getText(), metadata);
    }
}
