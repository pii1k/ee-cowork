package io.autocrypt.eeTeam.cowork.cvereportagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.embabel.agent.core.AgentPlatform;
import io.autocrypt.eeTeam.cowork.cvereportagent.domain.CveReportRequest;
import io.autocrypt.eeTeam.cowork.cvereportagent.domain.CveReportResult;
import io.autocrypt.jwlee.cowork.core.commands.BaseAgentCommand;
import org.springframework.shell.standard.ShellCommandGroup;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

@ShellComponent
@ShellCommandGroup("SBOM Command")
public class CveReportCommand extends BaseAgentCommand {

    private final ObjectMapper objectMapper;

    public CveReportCommand(AgentPlatform agentPlatform, ObjectMapper objectMapper) {
        super(agentPlatform);
        this.objectMapper = objectMapper;
    }

    @ShellMethod(value = "Generate a CVE applicability report from an SBOM file.", key = "cve-report")
    public String generateReport(
            @ShellOption(help = "Path to CycloneDX SBOM JSON.") String sbom,
            @ShellOption(help = "Product name.") String product,
            @ShellOption(help = "Product version.", defaultValue = "unknown") String version,
            @ShellOption(help = "Optional build directory for later evidence gathering.", defaultValue = "") String buildDir,
            @ShellOption(help = "Path to license map.", defaultValue = "sbom/license_map.json") String licenseMap,
            @ShellOption(help = "Output format preference.", defaultValue = "all") String format,
            @ShellOption(help = "Optional analyst notes. Example: 'patched:CVE-2024-0001'", defaultValue = "") String notes,
            @ShellOption(help = "Run deterministic input validation only, without invoking the agent.", defaultValue = "false") boolean preflightOnly,
            @ShellOption(value = {"-p", "--show-prompts"}, help = "Show LLM prompts.", defaultValue = "false") boolean showPrompts,
            @ShellOption(value = {"-r", "--show-responses"}, help = "Show LLM responses.", defaultValue = "false") boolean showResponses
    ) throws ExecutionException, InterruptedException, IOException {
        logger.info("CveReportCommand", "Starting CVE report generation for product: " + product);

        CveReportRequest request = new CveReportRequest(
                sbom,
                product,
                version,
                buildDir,
                licenseMap,
                format,
                notes
        );

        CveReportPreflightSupport.PreflightResult preflight =
                CveReportPreflightSupport.runPreflightChecks(request, objectMapper);
        if (!preflight.ok()) {
            return preflight.render();
        }
        if (preflightOnly) {
            return preflight.render();
        }

        CveReportResult result = invokeAgent(
                CveReportResult.class,
                getOptions(showPrompts, showResponses),
                request
        );

        return result.markdownReport() + "\n\n"
                + "Artifacts:\n"
                + "- Markdown: " + result.markdownReportPath() + "\n"
                + "- JSON: " + result.jsonReportPath() + "\n"
                + "- CSV: " + result.csvReportPath() + "\n"
                + "- Summary: " + result.summary();
    }
}
