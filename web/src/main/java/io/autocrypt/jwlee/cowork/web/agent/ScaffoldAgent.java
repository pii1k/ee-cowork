package io.autocrypt.jwlee.cowork.web.agent;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class ScaffoldAgent implements Agent {
    @Override
    public String getId() { return "scaffold"; }

    @Override
    public String getName() { return "기본 에이전트 (Scaffold)"; }

    @Override
    public String getDescription() { return "에이전트 구현을 위한 기초 템플릿입니다."; }

    @Override
    public String getRole() { return "Developer"; }

    @Override
    @Async
    public CompletableFuture<String> execute(Map<String, String> params) {
        try {
            // Simulate long-running AI task
            Thread.sleep(5000); 
            
            String title = params.getOrDefault("title", "No Title");
            String content = params.getOrDefault("content", "No Content");

            StringBuilder report = new StringBuilder();
            report.append("# ").append(title).append(" 리포트\n\n");
            report.append("## 개요\n");
            report.append(content).append("\n\n");
            report.append("--- \n");
            report.append("Scaffold Agent에 의해 생성됨.");

            return CompletableFuture.completedFuture(report.toString());
        } catch (InterruptedException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
