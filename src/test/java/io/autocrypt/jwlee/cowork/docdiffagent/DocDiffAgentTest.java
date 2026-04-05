package io.autocrypt.jwlee.cowork.docdiffagent;

import io.autocrypt.jwlee.cowork.core.prompts.PromptProvider;
import io.autocrypt.jwlee.cowork.core.tools.BashTool;
import io.autocrypt.jwlee.cowork.core.tools.CoreWorkspaceProvider;
import io.autocrypt.jwlee.cowork.core.tools.CoworkLogger;
import io.autocrypt.jwlee.cowork.core.tools.FileReadTool;
import io.autocrypt.jwlee.cowork.core.tools.FileWriteTool;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class DocDiffAgentTest {

    @Test
    void testExtractTOC() throws IOException {
        BashTool bashTool = Mockito.mock(BashTool.class);
        FileReadTool fileReadTool = Mockito.mock(FileReadTool.class);
        FileWriteTool fileWriteTool = Mockito.mock(FileWriteTool.class);
        CoreWorkspaceProvider workspaceProvider = Mockito.mock(CoreWorkspaceProvider.class);
        CoworkLogger logger = Mockito.mock(CoworkLogger.class);
        PromptProvider promptProvider = Mockito.mock(PromptProvider.class);

        DocDiffAgent agent = new DocDiffAgent(bashTool, fileReadTool, fileWriteTool, workspaceProvider, logger, promptProvider);

        // Mock grep output in BashTool JSON format
        String mockStdout = "10:# 1. Overview\n20:## 1.1 Glossary\n30:# 2. Getting Started\n";
        String mockJson = "{\"exitCode\":0,\"stdout\":\"" + mockStdout.replace("\n", "\\n") + "\",\"message\":\"success\"}";
        
        when(bashTool.execute(anyString())).thenReturn(mockJson);

        List<TOCEntry> entries = agent.extractTOC(new DocVersion("1.0", "test.md")).entries();

        assertEquals(3, entries.size());
        
        assertEquals("1. Overview", entries.get(0).title());
        assertEquals(1, entries.get(0).level());
        assertEquals(10, entries.get(0).startLine());
        assertEquals(19, entries.get(0).endLine());

        assertEquals("1.1 Glossary", entries.get(1).title());
        assertEquals(2, entries.get(1).level());
        assertEquals(20, entries.get(1).startLine());
        assertEquals(29, entries.get(1).endLine());

        assertEquals("2. Getting Started", entries.get(2).title());
        assertEquals(1, entries.get(2).level());
        assertEquals(30, entries.get(2).startLine());
        assertEquals(Integer.MAX_VALUE, entries.get(2).endLine());
    }
}
