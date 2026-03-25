package io.autocrypt.jwlee.cowork.agents.docsummary;

import io.autocrypt.jwlee.cowork.core.hitl.ApplicationContextHolder;
import io.autocrypt.jwlee.cowork.core.hitl.NotificationEvent;
import io.autocrypt.jwlee.cowork.core.tools.CoreFileTools;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class AnkiService {

    private final CoreFileTools fileTools;

    public AnkiService(CoreFileTools fileTools) {
        this.fileTools = fileTools;
    }

    public String generateAnkiCsv(String workspaceName, List<DocSummaryAgent.DefinedTerm> terms) throws IOException {
        StringBuilder csv = new StringBuilder();
        for (DocSummaryAgent.DefinedTerm term : terms) {
            csv.append(escapeCsv(term.term())).append(",").append(escapeCsv(term.definition())).append("\n");
        }

        String filename = String.format("anki/%s_cards_ko.csv", workspaceName);
        String savedPath = fileTools.saveGeneratedContent(filename, csv.toString());

        // PUBLISH NOTIFICATION
        ApplicationContextHolder.getPublisher().publishEvent(
            new NotificationEvent("Anki 생성 완료", 
                String.format("'%s' 문서에서 %d개의 카드가 생성되었습니다.", workspaceName, terms.size()))
        );

        return savedPath;
    }

    private String escapeCsv(String text) {
        if (text == null) return "";
        String escaped = text.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
