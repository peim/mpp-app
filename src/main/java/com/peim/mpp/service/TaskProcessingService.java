package com.peim.mpp.service;

import com.peim.mpp.model.Task;
import com.peim.mpp.service.dao.TaskService;
import com.peim.mpp.service.hash.HashAlgorithm;
import com.peim.mpp.service.hash.HashAlgorithmFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service("taskProcessingService")
public class TaskProcessingService {

    @Autowired
    private TaskService taskService;

    private ThreadPoolTaskExecutor executor;
    private ConcurrentHashMap<Task, CompletableFuture<String>> processingTasks =
            new ConcurrentHashMap<>();

    public void execute(Task task, DeferredResult<ResponseEntity<Task>> deferredResult) {
        HashAlgorithm algorithm = HashAlgorithmFactory.of(task.getAlgo());

        if (getExecutor().getActiveCount() == getExecutor().getMaxPoolSize()) {
            task.setStatus("WAITING");
            task.setDescription(null);
        }

        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> {
                    task.setStatus("PROCESSING");
                    task.setDescription(null);
                    return algorithm.hash(task.getSrc());
                },
                getExecutor()
        );

        processingTasks.put(task, future);

        future.whenComplete((success, failure) -> {
            try {
                if (failure != null) {
                    task.setStatus("FAILURE");
                    task.setDescription(failure.getMessage());
                } else {
                    task.setStatus("SUCCESS");
                    task.setDescription(success);
                }
                taskService.updateTask(task);
                deferredResult.setResult(ResponseEntity.ok(task));
            } catch (Exception e) {
                deferredResult.setErrorResult(ResponseEntity.badRequest());
                e.printStackTrace();
            }
        });
    }

    public void cancel(Task task, DeferredResult<ResponseEntity<Task>> deferredResult) {
        boolean isCanceled = processingTasks.get(task).cancel(true);
        if (isCanceled) {
            task.setStatus("CANCELED");
            task.setDescription("Task has been canceled");
            taskService.updateTask(task);
        }
        deferredResult.setResult(ResponseEntity.ok(task));
    }

    private ThreadPoolTaskExecutor getExecutor() {
        if (executor == null) {
            executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(2);
            executor.setQueueCapacity(20);
            executor.initialize();
        }
        return executor;
    }
}
