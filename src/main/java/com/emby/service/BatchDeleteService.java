package com.emby.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 批量删除异步任务服务，仿 BatchFavoriteService 结构。
 */
@Slf4j
@Service
public class BatchDeleteService {

    private final ConcurrentHashMap<String, BatchDeleteTask> tasks = new ConcurrentHashMap<>();
    private final EmbyApiService embyApiService;

    public BatchDeleteService(EmbyApiService embyApiService) {
        this.embyApiService = embyApiService;
    }

    public String startBatch(List<String> itemIds, String token) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        BatchDeleteTask task = new BatchDeleteTask();
        task.setTaskId(taskId);
        task.setTotal(itemIds.size());
        task.setDone(0);
        task.setFailed(0);
        task.setStatus("running");
        task.setFailedItems(new ArrayList<>());
        tasks.put(taskId, task);

        Thread worker = new Thread(() -> {
            int done = 0, failed = 0;
            for (String itemId : itemIds) {
                task.setCurrentName(itemId);
                try {
                    embyApiService.deleteItem(token, itemId);
                    done++;
                    task.setDone(done);
                } catch (Exception e) {
                    log.error("批量删除失败 {}", itemId, e);
                    failed++;
                    task.setFailed(failed);
                    task.getFailedItems().add(itemId + "（" + e.getMessage() + "）");
                }
            }
            task.setStatus(failed == 0 ? "done" : (done == 0 ? "failed" : "partial"));
            task.setCurrentName(null);
            if (failed == 0) {
                task.setMessage("已完成：删除 " + itemIds.size() + " 项");
            } else {
                task.setMessage("完成（含失败）：成功 " + done + "，失败 " + failed);
            }
        }, "batchdel-" + taskId);
        worker.setDaemon(true);
        worker.start();
        return taskId;
    }

    public BatchDeleteTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    @Data
    public static class BatchDeleteTask {
        private String taskId;
        private int total;
        private int done;
        private int failed;
        private String status;
        private String currentName;
        private String message;
        private List<String> failedItems;
    }
}
