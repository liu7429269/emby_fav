package com.emby.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 批量收藏/取消收藏异步任务服务，仿 BatchTagService 结构。
 */
@Slf4j
@Service
public class BatchFavoriteService {

    private final ConcurrentHashMap<String, BatchFavoriteTask> tasks = new ConcurrentHashMap<>();
    private final EmbyApiService embyApiService;

    public BatchFavoriteService(EmbyApiService embyApiService) {
        this.embyApiService = embyApiService;
    }

    public String startBatch(List<String> itemIds, boolean favorite, String token, String userId) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        BatchFavoriteTask task = new BatchFavoriteTask();
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
                    if (favorite) {
                        embyApiService.markFavorite(token, userId, itemId);
                    } else {
                        embyApiService.unmarkFavorite(token, userId, itemId);
                    }
                    done++;
                    task.setDone(done);
                } catch (Exception e) {
                    log.error("批量收藏失败 {} fav={}", itemId, favorite, e);
                    failed++;
                    task.setFailed(failed);
                    task.getFailedItems().add(itemId + "（" + e.getMessage() + "）");
                }
            }
            task.setStatus(failed == 0 ? "done" : (done == 0 ? "failed" : "partial"));
            task.setCurrentName(null);
            if (failed == 0) {
                task.setMessage("已完成：" + (favorite ? "收藏 " : "取消收藏 ") + itemIds.size() + " 项");
            } else {
                task.setMessage("完成（含失败）：成功 " + done + "，失败 " + failed);
            }
        }, "batchfav-" + taskId);
        worker.setDaemon(true);
        worker.start();
        return taskId;
    }

    public BatchFavoriteTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    @Data
    public static class BatchFavoriteTask {
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
