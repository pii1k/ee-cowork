package io.autocrypt.jwlee.cowork.web.service;

import io.autocrypt.jwlee.cowork.web.model.Task;
import io.autocrypt.jwlee.cowork.web.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class TaskService {
    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional
    public Task createTask(String agentId, String agentName, String userId) {
        Task task = new Task();
        task.setAgentId(agentId);
        task.setAgentName(agentName);
        task.setUserId(userId);
        return taskRepository.save(task);
    }

    @Transactional
    public void updateStatus(String taskId, String status) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(status);
            taskRepository.save(task);
        });
    }

    @Transactional
    public void completeTask(String taskId, String result) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus("COMPLETED");
            task.setResult(result);
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        });
    }

    @Transactional
    public void failTask(String taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus("FAILED");
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
        });
    }

    public List<Task> getTasksByUser(String userId) {
        return taskRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Optional<Task> getTask(String taskId) {
        return taskRepository.findById(taskId);
    }
}
