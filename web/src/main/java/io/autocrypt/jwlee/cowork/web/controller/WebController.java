package io.autocrypt.jwlee.cowork.web.controller;

import io.autocrypt.jwlee.cowork.web.agent.Agent;
import io.autocrypt.jwlee.cowork.web.model.Task;
import io.autocrypt.jwlee.cowork.web.service.TaskService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class WebController {
    private final List<Agent> agents;
    private final TaskService taskService;

    public WebController(List<Agent> agents, TaskService taskService) {
        this.agents = agents;
        this.taskService = taskService;
    }

    @GetMapping("/")
    public String index(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        model.addAttribute("agents", agents);
        model.addAttribute("username", userDetails.getUsername());
        return "marketplace";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/agents/{agentId}")
    public String agentWorkspace(@PathVariable String agentId, Model model, @AuthenticationPrincipal UserDetails userDetails) {
        Agent agent = agents.stream()
                .filter(a -> a.getId().equals(agentId))
                .findFirst()
                .orElseThrow();
        model.addAttribute("agent", agent);
        model.addAttribute("username", userDetails.getUsername());
        return "agents/" + agentId;
    }

    @PostMapping("/agents/{agentId}/run")
    @ResponseBody
    public String runAgent(@PathVariable String agentId, 
                           @RequestParam java.util.Map<String, String> params, 
                           @RequestParam(value = "tech_ref_file", required = false) java.util.List<org.springframework.web.multipart.MultipartFile> techFiles,
                           @RequestParam(value = "product_spec_file", required = false) java.util.List<org.springframework.web.multipart.MultipartFile> productFiles,
                           @AuthenticationPrincipal UserDetails userDetails) {
        Agent agent = agents.stream()
                .filter(a -> a.getId().equals(agentId))
                .findFirst()
                .orElseThrow();
        
        Task task = taskService.createTask(agentId, agent.getName(), userDetails.getUsername());
        taskService.updateStatus(task.getId(), "RUNNING");
        
        java.util.Map<String, String> agentParams = new java.util.HashMap<>(params);
        agentParams.put("taskId", task.getId());

        try {
            java.nio.file.Path uploadDir = java.nio.file.Paths.get("data/uploads/" + task.getId());
            java.nio.file.Files.createDirectories(uploadDir);

            // tech_ref 다중 파일 처리
            if (techFiles != null && !techFiles.isEmpty()) {
                java.util.List<String> techPaths = new java.util.ArrayList<>();
                for (org.springframework.web.multipart.MultipartFile file : techFiles) {
                    if (!file.isEmpty()) {
                        java.nio.file.Path filePath = uploadDir.resolve("tech_" + file.getOriginalFilename());
                        file.transferTo(filePath);
                        techPaths.add(filePath.toString());
                    }
                }
                if (!techPaths.isEmpty()) {
                    agentParams.put("tech_ref_file", String.join(",", techPaths));
                }
            }

            // product_spec 다중 파일 처리
            if (productFiles != null && !productFiles.isEmpty()) {
                java.util.List<String> productPaths = new java.util.ArrayList<>();
                for (org.springframework.web.multipart.MultipartFile file : productFiles) {
                    if (!file.isEmpty()) {
                        java.nio.file.Path filePath = uploadDir.resolve("prod_" + file.getOriginalFilename());
                        file.transferTo(filePath);
                        productPaths.add(filePath.toString());
                    }
                }
                if (!productPaths.isEmpty()) {
                    agentParams.put("product_spec_file", String.join(",", productPaths));
                }
            }
        } catch (java.io.IOException e) {
            taskService.failTask(task.getId());
            return "<div class='alert alert-danger'>File upload failed: " + e.getMessage() + "</div>";
        }

        agent.execute(agentParams).thenAccept(result -> {
            taskService.completeTask(task.getId(), result);
            try {
                org.springframework.util.FileSystemUtils.deleteRecursively(java.nio.file.Paths.get("data/uploads/" + task.getId()));
            } catch (Exception ex) {}
        }).exceptionally(ex -> {
            taskService.failTask(task.getId());
            return null;
        });

        return "<div hx-get='/tasks/" + task.getId() + "/status' hx-trigger='every 2s' hx-swap='outerHTML'>Processing...</div>";
    }

    @GetMapping("/tasks")
    public String taskHistory(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        List<Task> tasks = taskService.getTasksByUser(userDetails.getUsername());
        model.addAttribute("tasks", tasks);
        model.addAttribute("username", userDetails.getUsername());
        return "task_history";
    }

    @GetMapping("/tasks/{taskId}/status")
    @ResponseBody
    public String getTaskStatus(@PathVariable String taskId) {
        return taskService.getTask(taskId)
                .map(task -> {
                    if ("COMPLETED".equals(task.getStatus())) {
                        String successHtml = "<div class='flex items-center gap-2 text-blue-600 font-bold animate-bounce'>" +
                                "<span class='material-symbols-outlined'>check_circle</span> Task Completed!</div>";
                        String oobHtml = "<section id='right-panel' hx-swap-oob='true' class='lg:col-span-6 space-y-8 animate-in fade-in duration-500'>" +
                                "<div class='bg-blue-50/50 p-8 rounded-xl border border-blue-100 flex flex-col items-center justify-center text-center min-h-[400px]'>" +
                                "<div class='w-16 h-16 bg-blue-100 rounded-full flex items-center justify-center mb-6 text-blue-600'>" +
                                "<span class='material-symbols-outlined text-4xl'>check_circle</span></div>" +
                                "<h3 class='text-2xl font-bold text-[#191c1d] mb-2'>Agent Task Completed!</h3>" +
                                "<p class='text-[#424655] mb-8 max-w-sm'>에이전트 작업이 완료되었습니다. 결과물을 확인하세요.</p>" +
                                "<a href='/tasks/" + taskId + "' class='primary-gradient text-white px-8 py-3 rounded-md font-bold shadow-lg hover:shadow-xl active:scale-[0.98] transition-all flex items-center justify-center gap-2'>" +
                                "<span class='material-symbols-outlined'>description</span> View Full Result</a></div></section>";
                        return successHtml + oobHtml;
                    } else if ("FAILED".equals(task.getStatus())) {
                        return "<div class='flex items-center gap-2 text-red-600 font-bold'>" +
                                "<span class='material-symbols-outlined'>error</span> Task Failed.</div>";
                    } else {
                        return "<div hx-get='/tasks/" + taskId + "/status' hx-trigger='every 2s' hx-swap='outerHTML' class='flex flex-col items-center gap-2'>" +
                                "<div class='flex items-center gap-2 text-blue-600 font-medium'>" +
                                "<span class='material-symbols-outlined animate-spin'>autorenew</span> Status: RUNNING...</div>" +
                                "<p class='text-[11px] text-slate-400 italic'>분석이 오래 걸릴 수 있습니다. 잠시 나갔다 오셔도 안전하게 진행됩니다.</p></div>";
                    }
                })
                .orElse("Task not found");
    }

    @GetMapping("/tasks/{taskId}")
    public String viewTask(@PathVariable String taskId, Model model, @AuthenticationPrincipal UserDetails userDetails) {
        Task task = taskService.getTask(taskId).orElseThrow();
        model.addAttribute("task", task);
        model.addAttribute("username", userDetails.getUsername());
        return "task_detail";
    }

    @GetMapping("/tasks/{taskId}/view")
    public String viewSlides(@PathVariable String taskId, Model model) {
        Task task = taskService.getTask(taskId).orElseThrow();
        model.addAttribute("task", task);
        return "slide_viewer";
    }
}
