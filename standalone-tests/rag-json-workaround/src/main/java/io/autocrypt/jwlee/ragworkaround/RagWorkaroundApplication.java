package io.autocrypt.jwlee.ragworkaround;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.rag.ingestion.policy.NeverRefreshExistingDocumentContentPolicy;
import com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader;
import com.embabel.agent.rag.lucene.LuceneSearchOperations;
import com.embabel.agent.rag.service.SearchOperations;
import com.embabel.common.ai.model.ModelProvider;
import com.embabel.common.ai.model.ModelSelectionCriteria;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@SpringBootApplication
public class RagWorkaroundApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagWorkaroundApplication.class, args);
    }

    @Bean
    public SearchOperations luceneSearch(ModelProvider modelProvider) {
        var embeddingService = modelProvider.getEmbeddingService(
                ModelSelectionCriteria.getAuto());
                
        return LuceneSearchOperations
                .withName("test-docs")
                .withEmbeddingService(embeddingService)
                .withIndexPath(Paths.get("target/lucene-index"))
                .buildAndLoadChunks();
    }

    @Bean
    public JsonSafeToolishRag jsonSafeToolishRag(SearchOperations luceneSearch) {
        return new JsonSafeToolishRag("docs", "Test documentation about weekly reports", luceneSearch);
    }

    @Bean
    public CommandLineRunner runner(AgentPlatform platform, SearchOperations ragStore) {
        return args -> {
            System.out.println("Starting Ingestion...");
            // Resolve relative to where mvn spring-boot:run is executed (standalone-tests/rag-json-workaround)
            Path outputFile = Paths.get("../../output/weekly-report.20260320.md").toAbsolutePath().normalize();
            if (Files.exists(outputFile)) {
                System.out.println("Ingesting: " + outputFile);
                NeverRefreshExistingDocumentContentPolicy.INSTANCE
                        .ingestUriIfNeeded((com.embabel.agent.rag.store.ChunkingContentElementRepository) ragStore, new TikaHierarchicalContentReader(), outputFile.toUri().toString());
            } else {
                System.out.println("WARNING: " + outputFile + " does not exist.");
            }

            System.out.println("Ingestion complete. Starting Search...");
            
            var invocation = AgentInvocation.create(platform, String.class);
            var result = invocation.invoke(new UserInput("이동의자유 BE FE 진척도는 어때?"));
            
            System.out.println("\n\n==== RESULT ====");
            System.out.println(result);
            System.out.println("================");
            
            System.exit(0);
        };
    }
}
