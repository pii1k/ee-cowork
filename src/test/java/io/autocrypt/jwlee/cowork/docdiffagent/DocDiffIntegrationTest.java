package io.autocrypt.jwlee.cowork.docdiffagent;

import com.embabel.agent.api.common.Ai;
import io.autocrypt.jwlee.cowork.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Direct invocation test for DocDiffAgent to verify logic and result quality.
 * Bypasses AgentInvocation discovery issues.
 */
class DocDiffIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DocDiffAgent docDiffAgent;

    @Autowired
    private Ai ai;

    @Test
    void testManualDocDiffFlow() throws IOException {
        String sourceVer = "0.3.4";
        String sourcePath = "0.3.4.md";
        String targetVer = "4.0.0";
        String targetPath = "4.0.0.md";

        System.out.println("🚀 Starting Manual DocDiff Flow...");

        DocVersion sourceDoc = new DocVersion(sourceVer, sourcePath);
        DocVersion targetDoc = new DocVersion(targetVer, targetPath);

        // 1. TOC Extraction
        System.out.println("--- Step 1: Extracting TOCs ---");
        TOCResult sourceTOC = docDiffAgent.extractTOC(sourceDoc);
        TOCResult targetTOC = docDiffAgent.extractTOC(targetDoc);
        System.out.println("Source TOC entries: " + sourceTOC.entries().size());
        System.out.println("Target TOC entries: " + targetTOC.entries().size());

        // 2. Mapping TOCs (LLM)
        System.out.println("--- Step 2: Mapping TOCs via LLM ---");
        TOCMapResult mapResult = docDiffAgent.mapTOCs(sourceTOC, targetTOC, sourceDoc, targetDoc, ai);
        System.out.println("Mapped (Modified): " + mapResult.modified().size());
        System.out.println("Added: " + mapResult.added().size());
        System.out.println("Removed: " + mapResult.removed().size());

        // 3. Analyze Content Changes (Sample - 상위 3개만 수행하여 속도 조절)
        System.out.println("--- Step 3: Analyzing Section Contents (Partial) ---");
        List<SectionDiff> sectionDiffs = new ArrayList<>();
        int limit = 3;
        for (int i = 0; i < Math.min(limit, mapResult.modified().size()); i++) {
            MappedSection mapped = mapResult.modified().get(i);
            System.out.println("Analyzing: " + mapped.target().title());
            SectionDiff diff = docDiffAgent.analyzeSectionContent(mapped, sourceDoc, targetDoc, ai);
            sectionDiffs.add(diff);
        }

        // 4. Synthesize Final Report
        System.out.println("--- Step 4: Synthesizing Final Report ---");
        DocDiffReport report = docDiffAgent.synthesizeFinalReport(mapResult, sectionDiffs, ai);

        assertNotNull(report);
        System.out.println("\n====================================================");
        System.out.println("📊 FINAL DOC DIFF REPORT (MANUAL FLOW)");
        System.out.println("====================================================");
        System.out.println(report.content());
        System.out.println("====================================================\n");
    }
}
