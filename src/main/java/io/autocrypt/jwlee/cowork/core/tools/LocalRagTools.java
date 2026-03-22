package io.autocrypt.jwlee.cowork.core.tools;

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

/**
 * Core RAG tools providing managed ingestion and search operations.
 * Manages Lucene instances to prevent 'Lock held' errors.
 */
@Component
public class LocalRagTools {

    private static final Logger logger = LoggerFactory.getLogger(LocalRagTools.class);
    private static final String RAG_ROOT = "output/local-rag/";

    private final ModelProvider modelProvider;
    private final Map<String, LuceneSearchOperations> activeInstances = new ConcurrentHashMap<>();

    public LocalRagTools(ModelProvider modelProvider) {
        this.modelProvider = modelProvider;
    }

    private Path resolveIndexPath(String ragName) {
        return Paths.get(RAG_ROOT, ragName).toAbsolutePath().normalize();
    }

    /**
     * Gets an existing Lucene instance or creates a new one.
     */
    public synchronized LuceneSearchOperations getOrOpenInstance(String ragName) throws IOException {
        if (activeInstances.containsKey(ragName)) {
            return activeInstances.get(ragName);
        }

        Path indexPath = resolveIndexPath(ragName);
        Files.createDirectories(indexPath);
        var embeddingService = modelProvider.getEmbeddingService(ModelSelectionCriteria.getAuto());
        
        var lucene = LuceneSearchOperations
                .withName(ragName)
                .withEmbeddingService(embeddingService)
                .withIndexPath(indexPath)
                .withChunkTransformer(AddTitlesChunkTransformer.INSTANCE)
                .buildAndLoadChunks();
        
        activeInstances.put(ragName, lucene);
        return lucene;
    }

    /**
     * Closes a managed Lucene instance.
     */
    public synchronized void closeInstance(String ragName) {
        var lucene = activeInstances.remove(ragName);
        if (lucene != null) {
            try {
                lucene.close();
            } catch (Exception e) {
                logger.error("Error closing Lucene instance for {}", ragName, e);
            }
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

    /**
     * Ingests a URL or file into a specified RAG index.
     */
    public String ingestUrl(String location, String ragName) {
        try {
            var uri = location.startsWith("http://") || location.startsWith("https://")
                    ? location
                    : Path.of(location).toAbsolutePath().toUri().toString();

            var lucene = getOrOpenInstance(ragName);
            var ingested = NeverRefreshExistingDocumentContentPolicy.INSTANCE
                    .ingestUriIfNeeded(lucene, new TikaHierarchicalContentReader(), uri);

            return ingested != null ? "SUCCESS: Ingested document ID " + ingested.getId() : "Document already exists.";
        } catch (Exception e) {
            logger.error("Ingestion failed for {}", location, e);
            return "ERROR: Ingestion failed: " + e.getMessage();
        }
    }

    /**
     * Ingests all files in a directory into a specified RAG index.
     */
    public String ingestDirectory(String directoryPath, String ragName) {
        try {
            Path dir = Path.of(directoryPath).toAbsolutePath();
            if (!Files.isDirectory(dir)) return "Error: Not a directory.";

            var lucene = getOrOpenInstance(ragName);
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
            logger.error("Directory ingestion failed for {}", directoryPath, e);
            return "ERROR: Directory ingestion failed: " + e.getMessage();
        }
    }

    /**
     * Clears and deletes the specified RAG index.
     */
    public void zap(String ragName) {
        try {
            closeInstance(ragName);
            Path indexPath = resolveIndexPath(ragName);
            deleteRecursively(indexPath);
        } catch (Exception e) {
            logger.error("Zap failed for {}", ragName, e);
        }
    }

    /**
     * Returns the list of chunks in the specified RAG index (for debugging).
     */
    public String chunks(String ragName) {
        try {
            var lucene = getOrOpenInstance(ragName);
            var all = lucene.findAll(Chunk.class);
            StringBuilder sb = new StringBuilder("Chunks in " + ragName + ":\n");
            for (var chunk : all) {
                sb.append("- ").append(chunk.getId()).append(": ").append(chunk.getText()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "ERROR: Could not retrieve chunks: " + e.getMessage();
        }
    }

    /**
     * Returns information about the specified RAG index.
     */
    public String info(String ragName) {
        try {
            var lucene = getOrOpenInstance(ragName);
            return lucene.info().toString();
        } catch (Exception e) {
            return "ERROR: Could not retrieve info: " + e.getMessage();
        }
    }
}
