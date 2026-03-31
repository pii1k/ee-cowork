package io.autocrypt.jwlee.cowork.web.core.tools;

import com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader;
import com.embabel.agent.rag.ingestion.policy.NeverRefreshExistingDocumentContentPolicy;
import com.embabel.agent.rag.ingestion.transform.AddTitlesChunkTransformer;
import com.embabel.agent.rag.lucene.LuceneSearchOperations;
import com.embabel.agent.rag.model.Chunk;
import com.embabel.common.ai.model.ModelProvider;
import com.embabel.common.ai.model.ModelSelectionCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Component
public class LocalRagTools {

    private static final Logger logger = LoggerFactory.getLogger(LocalRagTools.class);
    private static final String RAG_ROOT = "data/local-rag/";

    private final ModelProvider modelProvider;
    private final Map<String, LuceneSearchOperations> activeInstances = new ConcurrentHashMap<>();

    public LocalRagTools(ModelProvider modelProvider) {
        this.modelProvider = modelProvider;
    }

    private Path resolveIndexPath(String ragName) {
        return Paths.get(RAG_ROOT, ragName).toAbsolutePath().normalize();
    }

    public synchronized LuceneSearchOperations getOrOpenMemoryInstance(String ragName) throws IOException {
        String key = "mem:" + ragName;
        if (activeInstances.containsKey(key)) {
            return activeInstances.get(key);
        }

        var embeddingService = modelProvider.getEmbeddingService(ModelSelectionCriteria.getAuto());
        var lucene = LuceneSearchOperations
                .withName(ragName)
                .withEmbeddingService(embeddingService)
                .withChunkTransformer(AddTitlesChunkTransformer.INSTANCE)
                .build();

        activeInstances.put(key, lucene);
        return lucene;
    }

    public synchronized LuceneSearchOperations getOrOpenInstance(String ragName) throws IOException {
        return getOrOpenInstance(ragName, resolveIndexPath(ragName));
    }

    public synchronized LuceneSearchOperations getOrOpenInstance(String ragName, Path indexPath) throws IOException {
        String key = indexPath.toAbsolutePath().normalize().toString();
        if (activeInstances.containsKey(key)) {
            return activeInstances.get(key);
        }

        Files.createDirectories(indexPath);
        var embeddingService = modelProvider.getEmbeddingService(ModelSelectionCriteria.getAuto());
        
        var builder = LuceneSearchOperations
                .withName(ragName)
                .withEmbeddingService(embeddingService)
                .withIndexPath(indexPath)
                .withChunkTransformer(AddTitlesChunkTransformer.INSTANCE);
        
        boolean isExistingIndex = false;
        if (Files.exists(indexPath)) {
            try (var stream = Files.list(indexPath)) {
                isExistingIndex = stream.anyMatch(p -> p.getFileName().toString().startsWith("segments"));
            }
        }
        
        var lucene = isExistingIndex ? builder.buildAndLoadChunks() : builder.build();
        
        activeInstances.put(key, lucene);
        return lucene;
    }

    public synchronized void closeMemoryInstance(String ragName) {
        String key = "mem:" + ragName;
        var lucene = activeInstances.remove(key);
        if (lucene != null) {
            try {
                lucene.close();
            } catch (Exception e) {
                logger.error("Error closing memory Lucene instance {}", ragName, e);
            }
        }
    }

    public synchronized void closeInstance(String ragName) {
        closeInstanceByPath(resolveIndexPath(ragName));
    }

    public synchronized void closeInstanceByPath(Path indexPath) {
        String key = indexPath.toAbsolutePath().normalize().toString();
        var lucene = activeInstances.remove(key);
        if (lucene != null) {
            try {
                lucene.close();
            } catch (Exception e) {
                logger.error("Error closing Lucene instance at {}", indexPath, e);
            }
        }
    }

    public String ingestUrlToMemory(String location, String ragName) {
        try {
            var uri = location.startsWith("http://") || location.startsWith("https://")
                    ? location
                    : Path.of(location).toAbsolutePath().toUri().toString();

            var lucene = getOrOpenMemoryInstance(ragName);
            var ingested = NeverRefreshExistingDocumentContentPolicy.INSTANCE
                    .ingestUriIfNeeded(lucene, new TikaHierarchicalContentReader(), uri);

            return ingested != null ? "SUCCESS: Ingested document ID " + ingested.getId() : "Document already exists.";
        } catch (Exception e) {
            logger.error("In-memory ingestion failed for {} in {}", location, ragName, e);
            return "ERROR: In-memory ingestion failed: " + e.getMessage();
        }
    }

    public String ingestUrl(String location, String ragName) {
        return ingestUrlAt(location, ragName, resolveIndexPath(ragName));
    }

    public String ingestUrlAt(String location, String ragName, Path indexPath) {
        try {
            var uri = location.startsWith("http://") || location.startsWith("https://")
                    ? location
                    : Path.of(location).toAbsolutePath().toUri().toString();

            var lucene = getOrOpenInstance(ragName, indexPath);
            var ingested = NeverRefreshExistingDocumentContentPolicy.INSTANCE
                    .ingestUriIfNeeded(lucene, new TikaHierarchicalContentReader(), uri);

            return ingested != null ? "SUCCESS: Ingested document ID " + ingested.getId() : "Document already exists.";
        } catch (Exception e) {
            logger.error("Ingestion failed for {} at {}", location, indexPath, e);
            return "ERROR: Ingestion failed: " + e.getMessage();
        }
    }

    public String ingestDirectoryToMemory(String directoryPath, String ragName) {
        try {
            Path dir = Path.of(directoryPath).toAbsolutePath();
            if (!Files.isDirectory(dir)) return "Error: Not a directory.";

            var lucene = getOrOpenMemoryInstance(ragName);
            int count = 0;
            try (Stream<Path> paths = Files.walk(dir)) {
                List<Path> files = paths.filter(Files::isRegularFile).toList();
                for (Path file : files) {
                    var uri = file.toUri().toString();
                    if (NeverRefreshExistingDocumentContentPolicy.INSTANCE.ingestUriIfNeeded(
                            lucene, new TikaHierarchicalContentReader(), uri) != null) {
                        count++;
                    }
                }
            }
            return "SUCCESS: Ingested " + count + " documents into memory.";
        } catch (Exception e) {
            logger.error("In-memory directory ingestion failed for {} in {}", directoryPath, ragName, e);
            return "ERROR: In-memory directory ingestion failed: " + e.getMessage();
        }
    }

    public String ingestDirectory(String directoryPath, String ragName) {
        return ingestDirectoryAt(directoryPath, ragName, resolveIndexPath(ragName));
    }

    public String ingestDirectoryAt(String directoryPath, String ragName, Path indexPath) {
        try {
            Path dir = Path.of(directoryPath).toAbsolutePath();
            if (!Files.isDirectory(dir)) return "Error: Not a directory.";

            var lucene = getOrOpenInstance(ragName, indexPath);
            int count = 0;
            try (Stream<Path> paths = Files.walk(dir)) {
                List<Path> files = paths.filter(Files::isRegularFile).toList();
                for (Path file : files) {
                    var uri = file.toUri().toString();
                    if (NeverRefreshExistingDocumentContentPolicy.INSTANCE.ingestUriIfNeeded(
                            lucene, new TikaHierarchicalContentReader(), uri) != null) {
                        count++;
                    }
                }
            }
            return "SUCCESS: Ingested " + count + " documents.";
        } catch (Exception e) {
            logger.error("Directory ingestion failed for {} at {}", directoryPath, indexPath, e);
            return "ERROR: Directory ingestion failed: " + e.getMessage();
        }
    }

    public void zap(String ragName) {
        zapAt(resolveIndexPath(ragName));
    }

    public void zapAt(Path indexPath) {
        try {
            closeInstanceByPath(indexPath);
            deleteRecursively(indexPath);
        } catch (Exception e) {
            logger.error("Zap failed at {}", indexPath, e);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            logger.error("Failed to delete: {}", p, e);
                        }
                    });
            }
        }
    }
}
