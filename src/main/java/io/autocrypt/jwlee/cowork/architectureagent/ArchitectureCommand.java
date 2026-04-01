package io.autocrypt.jwlee.cowork.architectureagent;

import com.embabel.agent.core.AgentPlatform;
import io.autocrypt.jwlee.cowork.architectureagent.domain.ArchitectureReport;
import io.autocrypt.jwlee.cowork.architectureagent.domain.ArchitectureRequest;
import io.autocrypt.jwlee.cowork.core.commands.BaseAgentCommand;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * CLI Command for architecture analysis.
 * Extends BaseAgentCommand to support standard debug flags (-p, -r).
 */
@ShellComponent
public class ArchitectureCommand extends BaseAgentCommand {

    public ArchitectureCommand(AgentPlatform agentPlatform) {
        super(agentPlatform);
    }

    @ShellMethod(key = "arch-analyze", value = "Analyze the architecture of a given codebase path.")
    public String analyze(
            @ShellOption(defaultValue = ".", help = "Path to analyze") String path,
            @ShellOption(defaultValue = "General Java project", help = "Context or introduction of the project") String context,
            @ShellOption(value = {"-p", "--show-prompts"}, help = "Show LLM prompts", defaultValue = "false") boolean showPrompts,
            @ShellOption(value = {"-r", "--show-responses"}, help = "Show LLM responses", defaultValue = "false") boolean showResponses
    ) throws java.util.concurrent.ExecutionException, InterruptedException {
        var request = new ArchitectureRequest(path, context);
        
        System.out.println("🚀 Starting rigorous architecture analysis for: " + path);
        System.out.println("   (This may take a minute as the agent reads and verifies internal code dependencies...)");

        // Use standard invocation from BaseAgentCommand to support -p, -r flags
        // Metrics reporting is now automated in BaseAgentCommand
        ArchitectureReport report = invokeAgent(
                ArchitectureReport.class,
                getOptions(showPrompts, showResponses),
                request
        );
        
        return formatReport(report);
    }

    private String formatReport(ArchitectureReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n==================================================\n");
        sb.append("🏗️ ARCHITECTURE ANALYSIS REPORT\n");
        sb.append("==================================================\n\n");
        
        sb.append("📝 SUMMARY:\n").append(report.summary()).append("\n\n");
        sb.append("🛠️ TECH STACK:\n").append(report.technicalStack()).append("\n\n");
        sb.append("🧩 MODULES:\n");
        for (var module : report.modules()) {
            sb.append("- ").append(module.name()).append(" (").append(module.path()).append(")\n");
            sb.append("  * Responsibility: ").append(module.responsibility()).append("\n");
            sb.append("  * Dependencies: ").append(String.join(", ", module.dependencies())).append("\n");
        }
        
        sb.append("\n🚪 ENTRY POINTS:\n").append(String.join(", ", report.entryPoints())).append("\n\n");
        sb.append("📐 PATTERN: ").append(report.architecturePattern()).append("\n\n");
        sb.append("📜 KEY CONVENTIONS:\n");
        for (var conv : report.keyConventions()) {
            sb.append("- ").append(conv).append("\n");
        }
        
        sb.append("\n💡 RECOMMENDATIONS:\n").append(report.recommendations()).append("\n");
        sb.append("==================================================\n");
        
        return sb.toString();
    }
}
