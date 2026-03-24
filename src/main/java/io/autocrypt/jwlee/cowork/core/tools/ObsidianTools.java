package io.autocrypt.jwlee.cowork.core.tools;

import com.embabel.agent.api.annotation.LlmTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Specialized tools for Obsidian vault manipulation.
 */
@Component
public class ObsidianTools {

    private final String vaultPath;

    public ObsidianTools(@Value("${app.obsidian.vault-path}") String vaultPath) {
        this.vaultPath = vaultPath;
    }

    @LlmTool(description = "Finds the most recent daily note in the vault. Returns the relative path.")
    public String findMostRecentDailyNote() throws IOException {
        Path dailyNotesDir = Paths.get(vaultPath, "Calendar", "Daily notes");
        if (!Files.exists(dailyNotesDir)) return "ERROR: Daily notes directory not found.";

        try (Stream<Path> stream = Files.walk(dailyNotesDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .max(Path::compareTo)
                    .map(p -> Paths.get(vaultPath).relativize(p).toString())
                    .orElse("ERROR: No daily notes found.");
        }
    }

    @LlmTool(description = "Extracts unfinished tasks (marked with - [ ]) from a markdown file.")
    public List<String> extractUnfinishedTasks(String relativePath) throws IOException {
        Path filePath = Paths.get(vaultPath).resolve(relativePath);
        if (!Files.exists(filePath)) return Collections.emptyList();

        String content = Files.readString(filePath);
        Pattern pattern = Pattern.compile("^- \\[ \\].*$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);

        return matcher.results()
                .map(java.util.regex.MatchResult::group)
                .collect(Collectors.toList());
    }

    @LlmTool(description = "Writes a note to the specified relative path in the vault.")
    public String writeVaultNote(String relativePath, String content) throws IOException {
        Path filePath = Paths.get(vaultPath).resolve(relativePath);
        if (filePath.getParent() != null) Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content);
        return "Note written to: " + relativePath;
    }

    @LlmTool(description = "Checks if a note exists at the specified relative path in the vault.")
    public boolean checkNoteExists(String relativePath) {
        Path filePath = Paths.get(vaultPath).resolve(relativePath);
        return Files.exists(filePath);
    }
}
