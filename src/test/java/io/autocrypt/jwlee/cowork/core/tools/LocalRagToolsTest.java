package io.autocrypt.jwlee.cowork.core.tools;

import com.embabel.agent.rag.lucene.LuceneSearchOperations;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.ModelProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class LocalRagToolsTest {

    private LocalRagTools localRagTools;
    private ModelProvider modelProvider;
    private EmbeddingService embeddingService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        modelProvider = Mockito.mock(ModelProvider.class);
        embeddingService = Mockito.mock(EmbeddingService.class);
        when(modelProvider.getEmbeddingService(any())).thenReturn(embeddingService);
        localRagTools = new LocalRagTools(modelProvider);
    }

    @AfterEach
    void tearDown() {
        // Ensure all instances are closed to avoid file locks in tests
        // Note: In real scenarios, we'd iterate over active instances if exposed, 
        // but for tests, we'll manually close what we opened if needed.
    }

    @Test
    void shouldOpenInstanceAtDefaultPath() throws IOException {
        LuceneSearchOperations instance = localRagTools.getOrOpenInstance("test-rag");
        
        assertThat(instance).isNotNull();
        assertThat(instance.getName()).isEqualTo("test-rag");
        
        // Cleanup
        localRagTools.closeInstance("test-rag");
    }

    @Test
    void shouldOpenInstanceAtSpecificPath() throws IOException {
        Path specificPath = tempDir.resolve("my-custom-rag");
        LuceneSearchOperations instance = localRagTools.getOrOpenInstance("custom-name", specificPath);
        
        assertThat(instance).isNotNull();
        // Path should be created
        assertThat(Files.exists(specificPath)).isTrue();
        
        // Cleanup
        localRagTools.closeInstanceByPath(specificPath);
    }

    @Test
    void shouldReturnSameInstanceForSamePath() throws IOException {
        Path path = tempDir.resolve("shared-rag");
        
        LuceneSearchOperations first = localRagTools.getOrOpenInstance("rag-1", path);
        LuceneSearchOperations second = localRagTools.getOrOpenInstance("rag-2", path);
        
        assertThat(first).isSameAs(second);
        
        localRagTools.closeInstanceByPath(path);
    }

    @Test
    void shouldReturnDifferentInstancesForDifferentPaths() throws IOException {
        Path path1 = tempDir.resolve("rag1");
        Path path2 = tempDir.resolve("rag2");
        
        LuceneSearchOperations instance1 = localRagTools.getOrOpenInstance("common-name", path1);
        LuceneSearchOperations instance2 = localRagTools.getOrOpenInstance("common-name", path2);
        
        assertThat(instance1).isNotSameAs(instance2);
        
        localRagTools.closeInstanceByPath(path1);
        localRagTools.closeInstanceByPath(path2);
    }

    @Test
    void shouldIngestFileAtSpecificPath() throws IOException {
        Path indexPath = tempDir.resolve("ingest-test");
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hello V2X world");
        
        String result = localRagTools.ingestUrlAt(testFile.toString(), "test-rag", indexPath);
        
        assertThat(result).contains("SUCCESS");
        
        localRagTools.closeInstanceByPath(indexPath);
    }

    @Test
    void shouldZapInstanceAndPath() throws IOException {
        Path indexPath = tempDir.resolve("zap-test");
        localRagTools.getOrOpenInstance("to-be-zapped", indexPath);
        assertThat(Files.exists(indexPath)).isTrue();
        
        localRagTools.zapAt(indexPath);
        
        assertThat(Files.exists(indexPath)).isFalse();
    }

    @Test
    void shouldMaintainBackwardCompatibilityWithDefaultPath() throws IOException {
        // Default path is output/local-rag/legacy-rag
        String ragName = "legacy-rag";
        localRagTools.getOrOpenInstance(ragName);
        
        Path expectedPath = Path.of("output/local-rag/").resolve(ragName).toAbsolutePath().normalize();
        assertThat(Files.exists(expectedPath)).isTrue();
        
        localRagTools.zap(ragName);
        assertThat(Files.exists(expectedPath)).isFalse();
    }
}
