package com.emby.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 批量已阅/未读异步任务服务，仿 BatchFavoriteService 结构。
 */
@Slf4j
@Service
public class BatchPlayedService {

    private final ConcurrentHashMap<String, BatchPlayedTask> tasks = new ConcurrentHashMap<>();
    private final EmbyApiService embyApiService;

    public BatchPlayedService(EmbyApiService embyApiService) {
        this.embyApiService = embyApiService;
    }

    public String startBatch(List<String> itemIds, boolean played, String token, String userId) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        BatchPlayedTask task = new BatchPlayedTask();
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
                    if (played) {
                        embyApiService.markPlayed(token, userId, itemId);
                    } else {
                        embyApiService.markUnplayed(token, userId, itemId);
                    }
                    done++;
                    task.setDone(done);
                } catch (Exception e) {
                    log.error("批量已阅失败 {} played={}", itemId, played, e);
                    failed++;
                    task.setFailed(failed);
                    task.getFailedItems().add(itemId + "（" + e.getMessage() + "）");
                }
            }
            task.setStatus(failed == 0 ? "done" : (done == 0 ? "failed" : "partial"));
            task.setCurrentName(null);
            if (failed == 0) {
                task.setMessage("已完成：" + (played ? "已阅 " : "未读 ") + itemIds.size() + " 项");
            } else {
                task.setMessage("完成（含失败）：成功 " + done + "，失败 " + failed);
            }
        }, "batchplayed-" + taskId);
        worker.setDaemon(true);
        worker.start();
        return taskId;
    }

    public BatchPlayedTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    @Data
    public static class BatchPlayedTask {
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
