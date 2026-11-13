package devpilot.Backend.service.indexing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import devpilot.Backend.entity.IndexStatus;
import devpilot.Backend.entity.Repository;
import devpilot.Backend.exceptions.BadRequestException;
import devpilot.Backend.exceptions.NotFoundException;
import devpilot.Backend.repository.RepositoryRepository;
import devpilot.Backend.service.UserService;
import devpilot.Backend.service.ai.GeminiRateLimiter;
import devpilot.Backend.service.ai.RagSettings;
import devpilot.Backend.service.github.GitHubRateLimiter;
import devpilot.Backend.service.github.GithubApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {

    private static final int VECTOR_BATCH_SIZE = 16;
    private static final int PROGRESS_EVERY_N_FILES = 3;

    private final RepositoryRepository repositoryRepository;
    private final UserService userService;
    private final GithubApiClient gitHubApiClient;
    private final CodeFileFilter fileFilter;
    private final CodeChunker codeChunker;
    private final GitHubRateLimiter gitHubRateLimiter;
    private final GeminiRateLimiter geminiRateLimiter;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.indexing.max-file-bytes:102400}")
    private long maxFileBytes;

    public record FileEntry(String path, String sha, long size) {}

    public Repository startIndexing(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        if (repo.getIndexStatus() == IndexStatus.INDEXING) {
            throw new BadRequestException("Repository is already being indexed");
        }

        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        return repositoryRepository.save(repo);
    }

    @Async("indexingExecutor")
    public void indexAsync(UUID repoId, UUID userId) {
        try {
            doIndex(repoId, userId);
        } catch (Exception ex) {
            log.error("[INDEXING] Unhandled failure for repo {}", repoId, ex);
            markFailed(repoId, ex.getMessage());
        }
    }

    private void doIndex(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        String token = userService.decryptAccessToken(userService.requiredById(userId));
        String repoIdStr = repoId.toString();

        log.info("[INDEXING START] Repository: {} ({})", repo.getFullName(), repoId);

        // 1. Fetch file tree from GitHub
        Map<String, Object> tree = gitHubApiClient.getRepoTree(
                token, repo.getOwner(), repo.getName(), repo.getDefaultBranch());
        List<FileEntry> eligibleEntries = listIndexableFiles(tree);

        log.info("[INDEXING] Discovered {} eligible source files in {}", eligibleEntries.size(), repo.getFullName());

        // 2. Query existing indexed files from vector_store to enable incremental caching
        Map<String, String> existingFileShas = getExistingIndexedFiles(repoIdStr);
        Set<String> currentRepoPaths = eligibleEntries.stream().map(FileEntry::path).collect(Collectors.toSet());

        // 3. Remove obsolete vectors for files deleted from GitHub
        int deletedVectorsCount = cleanupDeletedFiles(repoIdStr, existingFileShas.keySet(), currentRepoPaths);
        if (deletedVectorsCount > 0) {
            log.info("[INDEXING DELETED] Cleaned up obsolete vectors for {} deleted files in {}", deletedVectorsCount, repo.getFullName());
        }

        // 4. Partition files into unchanged vs modified/new
        List<FileEntry> filesToProcess = new ArrayList<>();
        int unchangedCount = 0;
        for (FileEntry entry : eligibleEntries) {
            String existingSha = existingFileShas.get(entry.path());
            if (existingSha != null && !existingSha.isBlank() && existingSha.equals(entry.sha())) {
                unchangedCount++;
            } else {
                filesToProcess.add(entry);
            }
        }

        log.info("[INDEXING CACHE] {} files unchanged, {} files to chunk and embed in {}",
                unchangedCount, filesToProcess.size(), repo.getFullName());

        int initialChunks = countExistingChunks(repoIdStr);
        updateProgress(repoId, eligibleEntries.size(), unchangedCount, initialChunks, IndexStatus.INDEXING, null);

        // 5. Index modified/new files with batching and error resilience
        List<Document> currentBatch = new ArrayList<>();
        int processedCount = unchangedCount;
        int totalChunks = initialChunks;
        Map<String, String> failedFiles = new HashMap<>();

        for (FileEntry entry : filesToProcess) {
            String path = entry.path();
            try {
                // Remove outdated chunks for this file before re-embedding
                if (existingFileShas.containsKey(path)) {
                    deleteVectorsForFile(repoIdStr, path);
                }

                gitHubRateLimiter.pause();
                String content = gitHubApiClient.getFileContent(token, repo.getOwner(), repo.getName(), path);
                List<Document> chunks = codeChunker.chunkFile(repoIdStr, path, entry.sha(), content);

                if (!chunks.isEmpty()) {
                    currentBatch.addAll(chunks);
                    totalChunks += chunks.size();

                    if (currentBatch.size() >= VECTOR_BATCH_SIZE) {
                        persistBatchWithFallback(currentBatch, failedFiles);
                        currentBatch.clear();
                    }
                }
            } catch (Exception ex) {
                log.warn("[INDEXING SKIPPED] Error processing file {} in {}: {}", path, repo.getFullName(), ex.getMessage());
                failedFiles.put(path, ex.getMessage());
            }

            processedCount++;
            if (processedCount % PROGRESS_EVERY_N_FILES == 0 || processedCount == eligibleEntries.size()) {
                updateProgress(repoId, eligibleEntries.size(), processedCount, totalChunks, IndexStatus.INDEXING, null);
            }
        }

        // Persist any remaining chunks in final batch
        if (!currentBatch.isEmpty()) {
            persistBatchWithFallback(currentBatch, failedFiles);
            currentBatch.clear();
        }

        // 6. Complete indexing and record final state
        int finalTotalChunks = countExistingChunks(repoIdStr);
        if (filesToProcess.isEmpty() && unchangedCount == 0 && eligibleEntries.isEmpty()) {
            markReady(repoId, 0, 0, 0, repo.getFullName(), null);
        } else if (!failedFiles.isEmpty() && failedFiles.size() == filesToProcess.size() && unchangedCount == 0) {
            markFailed(repoId, "Failed to index files: " + formatFailedFiles(failedFiles));
        } else {
            String warning = failedFiles.isEmpty() ? null : "Indexed with " + failedFiles.size() + " failed files: " + formatFailedFiles(failedFiles);
            markReady(repoId, eligibleEntries.size(), processedCount, finalTotalChunks, repo.getFullName(), warning);
        }
    }

    private void persistBatchWithFallback(List<Document> batch, Map<String, String> failedFiles) {
        try {
            vectorStore.add(batch);
        } catch (Exception batchEx) {
            log.warn("[INDEXING BATCH RETRY] Batch of {} chunks failed ({}). Retrying chunk-by-chunk...",
                    batch.size(), batchEx.getMessage());

            // Fallback: embed chunks individually so a single problematic chunk doesn't abort the entire batch
            for (Document doc : batch) {
                String filePath = String.valueOf(doc.getMetadata().get("filePath"));
                try {
                    vectorStore.add(List.of(doc));
                } catch (Exception docEx) {
                    log.error("[INDEXING CHUNK FAILED] Could not persist chunk for {}: {}", filePath, docEx.getMessage());
                    failedFiles.put(filePath, docEx.getMessage());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<FileEntry> listIndexableFiles(Map<String, Object> tree) {
        if (tree == null || tree.get("tree") == null) {
            return List.of();
        }

        List<Map<String, Object>> entries = (List<Map<String, Object>>) tree.get("tree");
        return entries.stream()
                .filter(entry -> "blob".equals(String.valueOf(entry.get("type"))))
                .filter(entry -> {
                    String path = String.valueOf(entry.get("path"));
                    long size = entry.get("size") instanceof Number n ? n.longValue() : 0L;
                    return fileFilter.isEligible(path, size, maxFileBytes);
                })
                .map(entry -> {
                    String path = String.valueOf(entry.get("path"));
                    String sha = String.valueOf(entry.get("sha"));
                    long size = entry.get("size") instanceof Number n ? n.longValue() : 0L;
                    return new FileEntry(path, sha, size);
                })
                .toList();
    }

    private Map<String, String> getExistingIndexedFiles(String repoId) {
        Map<String, String> files = new HashMap<>();
        try {
            String sql = """
                    SELECT DISTINCT metadata->>'filePath' AS file_path,
                                    COALESCE(metadata->>'fileSha', '') AS file_sha
                    FROM public.vector_store
                    WHERE metadata->>'repoId' = ?
                      AND metadata->>'filePath' IS NOT NULL
                    """;
            jdbcTemplate.query(sql, rs -> {
                String path = rs.getString("file_path");
                String sha = rs.getString("file_sha");
                if (path != null && !path.isBlank()) {
                    files.put(path, sha);
                }
            }, repoId);
        } catch (Exception ex) {
            log.warn("[INDEXING CACHE] Could not query vector_store for repo {}: {}", repoId, ex.getMessage());
        }
        return files;
    }

    private int cleanupDeletedFiles(String repoId, Set<String> indexedPaths, Set<String> currentRepoPaths) {
        int count = 0;
        for (String path : indexedPaths) {
            if (!currentRepoPaths.contains(path)) {
                deleteVectorsForFile(repoId, path);
                count++;
            }
        }
        return count;
    }

    private void deleteVectorsForFile(String repoId, String filePath) {
        try {
            String sql = "DELETE FROM public.vector_store WHERE metadata->>'repoId' = ? AND metadata->>'filePath' = ?";
            jdbcTemplate.update(sql, repoId, filePath);
        } catch (Exception ex) {
            log.warn("[INDEXING] Could not delete vectors for file {} in repo {}: {}", filePath, repoId, ex.getMessage());
        }
    }

    private int countExistingChunks(String repoId) {
        try {
            String sql = "SELECT COUNT(*) FROM public.vector_store WHERE metadata->>'repoId' = ?";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, repoId);
            return count != null ? count : 0;
        } catch (Exception ex) {
            return 0;
        }
    }

    private String formatFailedFiles(Map<String, String> failedFiles) {
        return failedFiles.entrySet().stream()
                .limit(5)
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("; "));
    }

    @Transactional
    public void updateProgress(
            UUID repoId,
            int total,
            int processed,
            int chunks,
            IndexStatus status,
            String error) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setFilesTotal(total);
            repo.setFilesProcessed(processed);
            repo.setChunkCount(chunks);
            repo.setIndexStatus(status);
            if (error != null) {
                repo.setErrorMessage(error.length() > 2000 ? error.substring(0, 2000) : error);
            }
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }

    @Transactional
    public void markReady(UUID repoId, int totalFiles, int processedFiles, int totalChunks, String fullName, String warning) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.READY);
            repo.setFilesTotal(totalFiles);
            repo.setFilesProcessed(processedFiles);
            repo.setChunkCount(totalChunks);
            repo.setIndexedAt(Instant.now());
            repo.setErrorMessage(warning != null && warning.length() > 2000 ? warning.substring(0, 2000) : warning);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
        log.info("[INDEXING FINISHED] Indexed {} files ({} chunks) for {} (warning: {})",
                processedFiles, totalChunks, fullName, warning);
    }

    @Transactional
    public void markFailed(UUID repoId, String message) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.FAILED);
            repo.setErrorMessage(message != null && message.length() > 2000
                    ? message.substring(0, 2000)
                    : message);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }
}
