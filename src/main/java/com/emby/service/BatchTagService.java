package com.emby.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 批量打标签异步任务服务，仿 FileMoveService 结构。
 * 对一组 itemId 逐个应用一组标签名，循环调用 EmbyApiService.addTag。
 */
@Slf4j
@Service
public class BatchTagService {

    private final ConcurrentHashMap<String, BatchTagTask> tasks = new ConcurrentHashMap<>();
    private final EmbyApiService embyApiService;

    public BatchTagService(EmbyApiService embyApiService) {
        this.embyApiService = embyApiService;
    }

    /**
     * 启动批量打标签任务。
     * @param itemIds  目标媒体 itemId 列表
     * @param tagNames 标签名列表（会去重）
     * @param token   Emby 访问令牌
     * @param userId  Emby 用户 ID
     * @return taskId
     */
    public String startBatch(List<String> itemIds, List<String> tagNames,
                             String token, String userId) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 标签去重保序
        Set<String> uniqueTags = new LinkedHashSet<>();
        if (tagNames != null) {
            for (String t : tagNames) {
                if (t == null) continue;
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) uniqueTags.add(trimmed);
            }
        }
        List<String> tags = new ArrayList<>(uniqueTags);

        BatchTagTask task = new BatchTagTask();
        task.setTaskId(taskId);
        task.setTotal(itemIds.size() * tags.size());
        task.setDone(0);
        task.setFailed(0);
        task.setStatus("running");
        task.setFailedItems(new ArrayList<>());
        tasks.put(taskId, task);

        Thread worker = new Thread(() -> {
            int done = 0, failed = 0;
            for (String itemId : itemIds) {
                for (String tag : tags) {
                    task.setCurrentName(itemId + " ← " + tag);
                    try {
                        embyApiService.addTag(token, userId, itemId, tag);
                        done++;
                        task.setDone(done);
                    } catch (Exception e) {
                        log.error("批量打标签失败 {} {}", itemId, tag, e);
                        failed++;
                        task.setFailed(failed);
                        task.getFailedItems().add(itemId + " ← " + tag + "（" + e.getMessage() + "）");
                    }
                }
            }
            task.setStatus(failed == 0 ? "done" : (done == 0 ? "failed" : "partial"));
            task.setCurrentName(null);
            if (failed == 0) {
                task.setMessage("已完成：为 " + itemIds.size() + " 项应用 " + tags.size() + " 个标签");
            } else {
                task.setMessage("完成（含失败）：成功 " + done + "，失败 " + failed);
            }
        }, "batchtag-" + taskId);
        worker.setDaemon(true);
        worker.start();

        return taskId;
    }

    public BatchTagTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    @Data
    public static class BatchTagTask {
        private String taskId;
        private int total;
        private int done;
        private int failed;
        private String status; // running / done / partial / failed
        private String currentName;
        private String message;
        private List<String> failedItems;
    }
}
