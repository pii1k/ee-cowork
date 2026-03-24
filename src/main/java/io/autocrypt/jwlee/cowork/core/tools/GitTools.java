package io.autocrypt.jwlee.cowork.core.tools;

import com.embabel.agent.api.annotation.LlmTool;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/**
 * Git operations for managing the Obsidian vault.
 */
@Component
public class GitTools {

    private final String vaultPath;
    private final String repoUrl;
    private final Terminal terminal;

    public GitTools(@Value("${app.obsidian.vault-path}") String vaultPath,
                    @Value("${app.obsidian.repo-url}") String repoUrl,
                    Terminal terminal) {
        this.vaultPath = vaultPath;
        this.repoUrl = repoUrl;
        this.terminal = terminal;
    }

    @LlmTool(description = "Clones the Obsidian vault if it doesn't exist.")
    public String cloneIfNeeded() throws IOException, InterruptedException {
        Path path = Paths.get(vaultPath);
        if (Files.exists(path)) {
            return "Vault already exists at " + vaultPath;
        }
        logInfo("Cloning repository: " + repoUrl + " to " + vaultPath);
        runCommand("git clone --depth 1 " + repoUrl + " " + vaultPath, new File("."));
        return "Successfully cloned " + repoUrl;
    }

    @LlmTool(description = "Synchronizes the local vault with the remote repository using a reset-hard strategy.")
    public String syncVault() throws IOException, InterruptedException {
        cloneIfNeeded();
        File vaultDir = new File(vaultPath);
        logInfo("Synchronizing vault: git fetch, reset --hard, pull");
        runCommand("git fetch origin", vaultDir);
        runCommand("git reset --hard origin/master", vaultDir);
        runCommand("git pull origin master", vaultDir);
        return "Vault synchronized successfully.";
    }

    @LlmTool(description = "Commits and pushes changes in the vault to the remote repository.")
    public String commitAndPush(String message) throws IOException, InterruptedException {
        File vaultDir = new File(vaultPath);
        logInfo("Pushing changes: " + message);
        runCommand("git add .", vaultDir);
        runCommand("git commit -m \"" + message + "\"", vaultDir);
        runCommand("git push origin master", vaultDir);
        return "Changes pushed successfully with message: " + message;
    }

    private void runCommand(String command, File workingDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.directory(workingDir);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String output = reader.lines().collect(Collectors.joining("\n"));
            if (process.waitFor() != 0) {
                logError("Command failed: " + command + "\nOutput: " + output);
                throw new RuntimeException("Git command failed: " + command);
            }
        }
    }

    private void logInfo(String message) {
        terminal.writer().println(new AttributedString("Git: " + message, 
                AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)).toAnsi());
        terminal.writer().flush();
    }

    private void logError(String message) {
        terminal.writer().println(new AttributedString("Git Error: " + message, 
                AttributedStyle.DEFAULT.foreground(AttributedStyle.RED)).toAnsi());
        terminal.writer().flush();
    }
}
