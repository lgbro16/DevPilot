package devpilot.Backend.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import devpilot.Backend.dto.CitationDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CodeContextRetriever {
    public static final String NO_MATCHES = "(no matching code chunks found in repository)";
    private static final int MAX_CONTEXT_CHARACTERS = 14_000;

    private final VectorStore vectorStore;
    private final CitationMapper citationMapper;

    public RetrievedContext retrieve(UUID repositoryId, String question) {
        if (question == null || question.isBlank()) {
            return new RetrievedContext(List.of(), NO_MATCHES);
        }

        var filter = new FilterExpressionBuilder()
                .eq(RagSettings.METADATA_REPO_ID, repositoryId.toString())
                .build();

        var search = SearchRequest.builder()
                .query(question)
                .topK(RagSettings.TOP_K_CHUNKS)
                .filterExpression(filter)
                .build();

        List<Document> documents;
        try {
            documents = vectorStore.similaritySearch(search);
        } catch (Exception ex) {
            log.error("[RAG] Similarity search failed for repo {}: {}", repositoryId, ex.getMessage());
            return new RetrievedContext(List.of(), NO_MATCHES);
        }

        if (documents == null || documents.isEmpty()) {
            log.info("[RAG] No vector matches found for question '{}' in repo {}", question, repositoryId);
            return new RetrievedContext(List.of(), NO_MATCHES);
        }

        List<CitationDto> citations = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();
        int totalChars = 0;

        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) continue;

            // Prevent context overflow
            if (totalChars + text.length() > MAX_CONTEXT_CHARACTERS && totalChars > 0) {
                log.info("[RAG] Context budget reached ({} chars). Truncating further chunk additions.", totalChars);
                break;
            }

            if (contextBuilder.length() > 0) {
                contextBuilder.append("\n\n---\n\n");
            }
            contextBuilder.append(text);
            totalChars += text.length();

            CitationDto citation = citationMapper.fromDocument(doc);
            if (citation != null && !citations.contains(citation)) {
                citations.add(citation);
            }
        }

        String contextText = contextBuilder.length() > 0 ? contextBuilder.toString() : NO_MATCHES;
        log.info("[RAG] Retrieved {} relevant chunks ({} chars, {} citations) for repo {}",
                citations.size(), totalChars, citations.size(), repositoryId);

        return new RetrievedContext(citations, contextText);
    }
}
