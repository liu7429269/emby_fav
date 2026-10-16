package com.emby.service;

import com.emby.dto.EmbyMediaItem;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Slf4j
@Service
public class FileMoveService {

    private static final int MAX_DEPTH = 4;
    private static final int THUMB_EDGE = 360;

    private final ConcurrentHashMap<String, Object> thumbLocks = new ConcurrentHashMap<>();

    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "avi", "m2ts", "ts", "mts", "wmv", "mov", "flv", "webm",
            "mpg", "mpeg", "m4v", "iso", "rmvb"
    ));

    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp"
    ));

    private final ConcurrentHashMap<String, MoveTask> tasks = new ConcurrentHashMap<>();
    private final EmbyApiService embyApiService;

    public FileMoveService(EmbyApiService embyApiService) {
        this.embyApiService = embyApiService;
    }

    private static boolean isFilesystemRoot(Path p) {
        if (p == null) {
            return false;
        }
        Path n = p.normalize();
        return n.getParent() == null;
    }

    /** 规范化路径：统一分隔符，去掉 file: 前缀。远程 http(s) 返回 null。 */
    public String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String p = path.trim();
        if (p.isEmpty()) {
            return null;
        }
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return null;
        }
        if (lower.startsWith("file:")) {
            p = p.substring(5);
            if (p.startsWith("//")) {
                p = p.substring(2);
            }
            // file:///C:/x → /C:/x 或 C:/x
            if (p.length() >= 3 && p.charAt(0) == '/' && Character.isLetter(p.charAt(1)) && p.charAt(2) == ':') {
                p = p.substring(1);
            }
        }
        p = p.replace('\\', '/');
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        return p.isEmpty() ? null : p;
    }

    public boolean isRemotePath(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** 对一组文件路径取公共前缀目录，作为媒体根；必要时向上找到可访问目录。 */
    public String detectRoot(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            log.warn("detectRoot: 输入路径列表为空");
            return null;
        }
        log.info("detectRoot: 原始路径共 {} 条: {}", paths.size(), paths);
        List<String> normalized = new ArrayList<>();
        int remoteCount = 0;
        for (String raw : paths) {
            if (isRemotePath(raw)) {
                remoteCount++;
                log.info("detectRoot: 跳过远程 URL: {}", raw);
                continue;
            }
            String n = normalizePath(raw);
            if (n != null) {
                normalized.add(n);
            } else {
                log.warn("detectRoot: 规范化后为空, 原始={}", raw);
            }
        }
        log.info("detectRoot: 规范化后 {} 条, 远程跳过 {} 条: {}", normalized.size(), remoteCount, normalized);
        if (normalized.isEmpty()) {
            log.warn("detectRoot: 无可用本地路径");
            return null;
        }
        String common = normalized.get(0);
        for (int i = 1; i < normalized.size(); i++) {
            common = commonPrefix(common, normalized.get(i));
        }
        log.info("detectRoot: 公共前缀={}", common);
        int sep = Math.max(common.lastIndexOf('/'), common.lastIndexOf('\\'));
        if (sep > 0) {
            common = common.substring(0, sep);
        } else if (sep == 0) {
            common = "/";
        }
        if (common == null || common.isEmpty()) {
            log.warn("detectRoot: 截断后公共目录为空");
            return null;
        }
        log.info("detectRoot: 截断为目录候选={}", common);
        String accessible = findAccessibleAncestor(common);
        log.info("detectRoot: 可访问祖先={}", accessible);
        return accessible;
    }

    /** 若目录不存在，沿父级向上找第一个存在的目录；绝不回退到文件系统根 `/`。 */
    public String findAccessibleAncestor(String root) {
        String normalized = normalizePath(root);
        if (normalized == null) {
            log.warn("findAccessibleAncestor: 规范化失败, 输入={}", root);
            return null;
        }
        Path cur = Paths.get(normalized).normalize();
        Path start = cur;
        log.info("findAccessibleAncestor: 从 {} 向上查找可访问目录", cur);
        for (int i = 0; i < 32 && cur != null; i++) {
            String curStr = cur.toString().replace('\\', '/');
            try {
                boolean exists = Files.exists(cur);
                boolean isDir = exists && Files.isDirectory(cur);
                boolean readable = isDir && Files.isReadable(cur);
                boolean fsRoot = isFilesystemRoot(cur);
                log.info("findAccessibleAncestor: 尝试[{}] path={} exists={} isDirectory={} readable={} fsRoot={}",
                        i, curStr, exists, isDir, readable, fsRoot);
                if (isDir) {
                    // 候选路径本身不存在时，不要把 / 或盘符根当成媒体根
                    if (fsRoot && !isFilesystemRoot(start)) {
                        log.warn("findAccessibleAncestor: 跳过文件系统根 {}, 原始路径不可达", curStr);
                        return null;
                    }
                    if (!readable) {
                        log.warn("findAccessibleAncestor: 目录存在但不可读: {}", curStr);
                    }
                    return curStr;
                }
            } catch (Exception e) {
                log.warn("findAccessibleAncestor: 检查失败 path={} err={}", curStr, e.toString());
            }
            Path parent = cur.getParent();
            if (parent == null || parent.equals(cur)) {
                log.warn("findAccessibleAncestor: 已到文件系统根仍未找到目录, 放弃={}", normalized);
                break;
            }
            cur = parent;
        }
        log.warn("findAccessibleAncestor: 未找到可访问目录, 返回 null (原候选={})", normalized);
        return null;
    }

    /** 检查根路径在本机是否可列目录。 */
    public String checkRootAccess(String root) {
        String normalized = normalizePath(root);
        String diag = " [user=" + System.getProperty("user.name")
                + ", file.encoding=" + System.getProperty("file.encoding")
                + ", path=" + (normalized != null ? normalized : root) + "]";
        log.info("checkRootAccess: 输入={}, 规范化={}", root, normalized);
        if (normalized == null) {
            if (root != null && isRemotePath(root)) {
                log.warn("checkRootAccess: 远程 URL 不可用{}", diag);
                return "远程 URL 路径无法本地移动，请使用已挂载到本机的路径" + diag;
            }
            log.warn("checkRootAccess: 路径无效{}", diag);
            return "媒体根路径无效" + diag;
        }
        Path rootPath = Paths.get(normalized).normalize();
        if (isFilesystemRoot(rootPath)) {
            log.warn("checkRootAccess: 拒绝文件系统根作为媒体根 → {}", normalized);
            return "媒体根不能是文件系统根 `/`，说明 Emby 路径（如 /clouddriver2）在本机未挂载" + diag;
        }
        try {
            boolean exists = Files.exists(rootPath);
            boolean isDir = exists && Files.isDirectory(rootPath);
            boolean readable = isDir && Files.isReadable(rootPath);
            log.info("checkRootAccess: path={} exists={} isDirectory={} readable={}",
                    rootPath, exists, isDir, readable);
            // 额外探测挂载点常见前缀是否存在
            probeMountHints(normalized);

            if (!exists) {
                log.warn("checkRootAccess: 路径不存在 → {}", normalized);
                return "路径在本机不存在: " + normalized
                        + "（请检查 Docker 是否挂载、user 是否与 Emby 一致、locale 是否 UTF-8）" + diag;
            }
            if (!isDir) {
                log.warn("checkRootAccess: 不是目录 → {}", normalized);
                return "路径不是目录: " + normalized + diag;
            }
            if (!readable) {
                log.warn("checkRootAccess: 不可读 → {}", normalized);
                return "路径不可读: " + normalized
                        + "（请将 compose user 与 Emby PUID:PGID 对齐）" + diag;
            }
        } catch (Exception e) {
            log.error("checkRootAccess: 访问异常 path={}", normalized, e);
            return "无法访问路径: " + normalized + "（" + e.getMessage() + "）" + diag;
        }
        log.info("checkRootAccess: 通过 → {}", normalized);
        return null;
    }

    /** 记录常见挂载点是否存在，便于对照 Emby 挂载。 */
    private void probeMountHints(String normalized) {
        String[] hints = {"/clouddriver2", "/emby_data8", "/emby_data6", "/emby_data4", "/emby_data2", "/strm"};
        for (String hint : hints) {
            if (normalized.startsWith(hint) || hint.equals(normalized)) {
                Path p = Paths.get(hint);
                try {
                    log.info("挂载探测: {} exists={} isDirectory={} readable={}",
                            hint, Files.exists(p), Files.isDirectory(p), Files.isReadable(p));
                } catch (Exception e) {
                    log.warn("挂载探测失败: {} → {}", hint, e.toString());
                }
                break;
            }
        }
        // 也探测路径的第一级目录
        String first = normalized;
        if (first.startsWith("/")) {
            int next = first.indexOf('/', 1);
            if (next > 1) {
                first = first.substring(0, next);
            }
            if (!first.equals(normalized)) {
                Path p = Paths.get(first);
                try {
                    log.info("路径首段探测: {} exists={} isDirectory={} readable={}",
                            first, Files.exists(p), Files.isDirectory(p), Files.isReadable(p));
                } catch (Exception e) {
                    log.warn("路径首段探测失败: {} → {}", first, e.toString());
                }
            }
        }
    }

    private String commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return a.substring(0, i);
    }

    /** 递归列出 root 下所有子目录（相对路径），深度上限 4。 */
    public List<String> listSubdirs(String root) {
        String normalized = normalizePath(root);
        if (normalized == null) {
            log.warn("listSubdirs: 规范化失败 root={}", root);
            return Collections.emptyList();
        }
        Path rootPath = Paths.get(normalized).normalize();
        if (!Files.isDirectory(rootPath)) {
            log.warn("listSubdirs: 不是可访问目录 root={}", rootPath);
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(rootPath, MAX_DEPTH)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.equals(rootPath))
                    .forEach(p -> result.add(rootPath.relativize(p.normalize()).toString().replace('\\', '/')));
        } catch (Exception e) {
            log.error("列出子目录失败: {}", root, e);
        }
        log.info("listSubdirs: root={} 子目录数={}", rootPath, result.size());
        // 按路径深度（/ 数量）从短到长，同深度再按字典序
        result.sort(Comparator
                .comparingInt(FileMoveService::pathDepth)
                .thenComparing(s -> s));
        return result;
    }

    /** 相对路径深度：斜杠数量越少越浅。 */
    static int pathDepth(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return 0;
        }
        int depth = 0;
        for (int i = 0; i < relativePath.length(); i++) {
            char c = relativePath.charAt(i);
            if (c == '/' || c == '\\') {
                depth++;
            }
        }
        return depth;
    }

    /** 单层列出 dir 下的直接子目录名（非递归），按名称排序。 */
    public List<String> listSubdirsFlat(String dir) {
        String normalized = normalizePath(dir);
        if (normalized == null) {
            log.warn("listSubdirsFlat: 规范化失败 dir={}", dir);
            return Collections.emptyList();
        }
        Path dirPath = Paths.get(normalized).normalize();
        if (!Files.isDirectory(dirPath)) {
            log.warn("listSubdirsFlat: 不是可访问目录 dir={}", dirPath);
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dirPath)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) {
                    String name = p.getFileName().toString();
                    result.add(name);
                }
            }
        } catch (Exception e) {
            log.error("单层列出子目录失败: {}", dir, e);
        }
        Collections.sort(result, String::compareToIgnoreCase);
        log.info("listSubdirsFlat: dir={} 子目录数={}", dirPath, result.size());
        return result;
    }

    /** 将路径向上取 levels 级目录，levels 超过实际深度时返回文件系统根。 */
    public String goUpLevels(String path, int levels) {
        String normalized = normalizePath(path);
        if (normalized == null) {
            return null;
        }
        Path cur = Paths.get(normalized).normalize();
        for (int i = 0; i < levels; i++) {
            Path parent = cur.getParent();
            if (parent == null || parent.equals(cur)) {
                break;
            }
            cur = parent;
        }
        return cur.toString().replace('\\', '/');
    }

    /** 返回路径的父目录（规范化、用 / 分隔），到根时返回 null。 */
    public String parentDir(String path) {
        String normalized = normalizePath(path);
        if (normalized == null) {
            return null;
        }
        Path cur = Paths.get(normalized).normalize();
        Path parent = cur.getParent();
        if (parent == null || parent.equals(cur)) {
            return null;
        }
        return parent.toString().replace('\\', '/');
    }

    static boolean isVideoFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.contains(ext);
    }

    static String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }

    /** 父目录下一层中的视频文件数量。 */
    int countVideosInDir(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (isVideoFile(p)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 独占电影文件夹：父目录不是文件系统根，且其中恰好只有 1 个视频文件。
     */
    boolean isExclusiveMovieFolder(Path videoFile) throws IOException {
        Path parent = videoFile.getParent();
        if (parent == null || isFilesystemRoot(parent)) {
            return false;
        }
        return countVideosInDir(parent) == 1;
    }

    /**
     * 收集同目录下与视频同基名的 sidecar（含视频本身）。
     * 匹配：basename + "." 或 basename + "-" 开头；不含 poster.jpg 等泛名。
     */
    List<Path> collectSidecarGroup(Path videoFile) throws IOException {
        Path parent = videoFile.getParent();
        List<Path> group = new ArrayList<>();
        if (parent == null || !Files.isDirectory(parent)) {
            group.add(videoFile);
            return group;
        }
        String basename = stripExtension(videoFile.getFileName().toString());
        String prefixDot = basename + ".";
        String prefixDash = basename + "-";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (name.equals(videoFile.getFileName().toString())
                        || name.startsWith(prefixDot)
                        || name.startsWith(prefixDash)) {
                    group.add(p.normalize());
                }
            }
        }
        // 视频优先放最后移动，减少中途失败时主文件已搬走、sidecar 仍在的情况
        group.sort(Comparator.comparing(p -> p.equals(videoFile.normalize()) ? 1 : 0));
        return group;
    }

    /**
     * 移动文件/目录，move 失败时回退 copy + delete（兼容 FUSE 等不支持跨目录 rename 的场景）。
     * @return true 成功
     */
    private boolean moveWithFallback(Path source, Path target) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception moveEx) {
            log.warn("Files.move 失败, 回退 copy+delete: {} -> {}, err={}", source, target, moveEx.toString());
        }
        try {
            if (Files.isRegularFile(source)) {
                log.info("复制文件: {} -> {}", source, target);
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(source);
                log.info("删除源文件: {}", source);
            } else if (Files.isDirectory(source)) {
                log.info("递归复制目录: {} -> {}", source, target);
                Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Path rel = source.relativize(dir);
                        Path dest = target.resolve(rel).normalize();
                        log.info("创建目录: {}", dest);
                        Files.createDirectories(dest);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Path rel = source.relativize(file);
                        Path dest = target.resolve(rel).normalize();
                        log.info("复制文件: {} -> {}", file, dest);
                        Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                        return FileVisitResult.CONTINUE;
                    }
                });
                log.info("递归删除源目录: {}", source);
                Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            return true;
        } catch (Exception e) {
            log.error("copy+delete 也失败: {} -> {}", source, target, e);
            return false;
        }
    }

    /** 启动异步移动任务，返回 taskId。targetPath 为绝对路径。 */
    public String startMove(String targetPath, List<MoveItem> items,
                            String token, String userId) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("startMove: targetPath={}, itemCount={}", targetPath, items.size());
        MoveTask task = new MoveTask();
        task.setTaskId(taskId);
        task.setTotal(items.size());
        task.setDone(0);
        task.setFailed(0);
        task.setStatus("running");
        task.setFailedFiles(new ArrayList<>());
        tasks.put(taskId, task);

        Path targetDir = Paths.get(targetPath).normalize();
        try {
            if (isFilesystemRoot(targetDir)) {
                task.setStatus("failed");
                task.setMessage("目标目录不能是文件系统根");
                return taskId;
            }
            Files.createDirectories(targetDir);
        } catch (Exception e) {
            task.setStatus("failed");
            task.setMessage("创建目标目录失败: " + e.getMessage());
            return taskId;
        }

        Thread worker = new Thread(() -> {
            int done = 0, failed = 0;
            for (MoveItem mi : items) {
                try {
                    String filePath = mi.getPath();
                    if (filePath == null || filePath.isEmpty()) {
                        filePath = embyApiService.getItemPath(token, userId, mi.getItemId());
                    }
                    if (filePath == null || filePath.isEmpty()) {
                        log.warn("处理失败(无路径): name={}", mi.getName());
                        failed++;
                        task.setFailed(failed);
                        task.getFailedFiles().add(mi.getName() + "（无路径）");
                        continue;
                    }
                    log.info("开始处理: name={}, path={}", mi.getName(), filePath);
                    Path src = Paths.get(filePath).normalize();

                    boolean success;
                    if (isExclusiveMovieFolder(src)) {
                        success = moveExclusiveFolder(src, targetDir, task, mi);
                    } else {
                        success = moveSidecarGroup(src, targetDir, task, mi);
                    }
                    if (success) {
                        done++;
                        task.setDone(done);
                        log.info("处理完成: name={}, 结果=成功, 进度={}/{}, 失败={}", mi.getName(), done, items.size(), failed);
                    } else {
                        failed++;
                        task.setFailed(failed);
                        log.info("处理完成: name={}, 结果=失败, 进度={}/{}, 失败={}", mi.getName(), done + failed, items.size(), failed);
                    }
                } catch (Exception e) {
                    log.error("移动失败: {}", mi.getName(), e);
                    failed++;
                    task.setFailed(failed);
                    task.getFailedFiles().add(mi.getName() + "（" + e.getMessage() + "）");
                }
            }
            log.info("移动任务完成: taskId={}, 总数={}, 成功={}, 失败={}", taskId, items.size(), done, failed);
            task.setStatus(failed == 0 ? "done" : (done == 0 ? "failed" : "partial"));
            task.setCurrentName(null);
        }, "move-" + taskId);
        worker.setDaemon(true);
        worker.start();

        return taskId;
    }

    /**
     * 将磁盘上的文件夹整夹移动到 targetPath 下（targetPath / 文件夹名）。
     */
    public String startMoveFolderDirectory(String targetPath, Path srcDir, String displayName) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String name = displayName != null && !displayName.isEmpty()
                ? displayName : srcDir.getFileName().toString();
        log.info("startMoveFolderDirectory: targetPath={}, src={}, name={}", targetPath, srcDir, name);
        MoveTask task = new MoveTask();
        task.setTaskId(taskId);
        task.setTotal(1);
        task.setDone(0);
        task.setFailed(0);
        task.setStatus("running");
        task.setFailedFiles(new ArrayList<>());
        tasks.put(taskId, task);

        Path targetDir = Paths.get(targetPath).normalize();
        try {
            if (isFilesystemRoot(targetDir)) {
                task.setStatus("failed");
                task.setMessage("目标目录不能是文件系统根");
                return taskId;
            }
            Files.createDirectories(targetDir);
        } catch (Exception e) {
            task.setStatus("failed");
            task.setMessage("创建目标目录失败: " + e.getMessage());
            return taskId;
        }

        Thread worker = new Thread(() -> {
            task.setCurrentName(name);
            MoveItem mi = new MoveItem(null, name, srcDir.toString());
            boolean success = moveFolderDirectory(srcDir, targetDir, task, mi);
            if (success) {
                task.setDone(1);
                task.setStatus("done");
                task.setMessage("已完成：移动文件夹 " + name);
            } else {
                task.setFailed(1);
                task.setStatus("failed");
                task.setMessage("移动文件夹失败");
            }
            task.setCurrentName(null);
        }, "move-folder-" + taskId);
        worker.setDaemon(true);
        worker.start();
        return taskId;
    }

    private boolean moveFolderDirectory(Path srcDir, Path targetDir, MoveTask task, MoveItem mi) {
        try {
            Path src = srcDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(src)) {
                task.getFailedFiles().add(mi.getName() + "（不是目录）");
                return false;
            }
            Path dest = targetDir.resolve(src.getFileName()).normalize();
            task.setCurrentName(src.getFileName().toString());
            if (dest.equals(src)) {
                task.getFailedFiles().add(mi.getName() + "（目标与源相同）");
                return false;
            }
            if (dest.startsWith(src)) {
                task.getFailedFiles().add(mi.getName() + "（不能移入自身子路径）");
                return false;
            }
            if (src.startsWith(dest)) {
                task.getFailedFiles().add(mi.getName() + "（不能移入自身子路径）");
                return false;
            }
            if (Files.exists(dest)) {
                task.getFailedFiles().add(mi.getName() + "（目标已存在）");
                return false;
            }
            log.info("整夹移动文件夹: {} -> {}", src, dest);
            if (moveWithFallback(src, dest)) {
                return true;
            }
            task.getFailedFiles().add(mi.getName() + "（移动失败, 详见日志）");
            return false;
        } catch (Exception e) {
            log.error("整夹移动文件夹失败: {}", mi.getName(), e);
            task.getFailedFiles().add(mi.getName() + "（" + e.getMessage() + "）");
            return false;
        }
    }

    /**
     * 整夹移入目标目录：targetDir / 文件夹名。
     * @return true 成功
     */
    private boolean moveExclusiveFolder(Path videoFile, Path targetDir,
                                        MoveTask task, MoveItem mi) {
        Path parent = videoFile.getParent();
        if (parent == null) {
            task.getFailedFiles().add(mi.getName() + "（无父目录）");
            return false;
        }
        Path dest = targetDir.resolve(parent.getFileName()).normalize();
        task.setCurrentName(parent.getFileName().toString());

        // 禁止移入自身或其子路径
        if (dest.equals(parent) || dest.startsWith(parent)) {
            task.getFailedFiles().add(mi.getName() + "（不能移入自身子路径）");
            return false;
        }
        if (Files.exists(dest)) {
            task.getFailedFiles().add(mi.getName() + "（目标已存在）");
            return false;
        }
        try {
            log.info("整夹移动: {} -> {}", parent, dest);
            if (moveWithFallback(parent, dest)) {
                return true;
            } else {
                task.getFailedFiles().add(mi.getName() + "（移动失败, 详见日志）");
                return false;
            }
        } catch (Exception e) {
            log.error("整夹移动失败: {} -> {}", parent, dest, e);
            task.getFailedFiles().add(mi.getName() + "（" + e.getMessage() + "）");
            return false;
        }
    }

    /**
     * 共享目录：移动视频 + 同基名 sidecar。
     * @return true 成功
     */
    private boolean moveSidecarGroup(Path videoFile, Path targetDir,
                                     MoveTask task, MoveItem mi) throws IOException {
        task.setCurrentName(videoFile.getFileName().toString());
        List<Path> group = collectSidecarGroup(videoFile);
        if (group.isEmpty()) {
            group = Collections.singletonList(videoFile);
        }

        List<Path> destinations = new ArrayList<>(group.size());
        for (Path p : group) {
            Path normalized = p.normalize();
            Path dst = targetDir.resolve(normalized.getFileName()).normalize();
            if (Files.exists(dst)) {
                task.getFailedFiles().add(mi.getName() + "（目标已存在: "
                        + dst.getFileName() + "）");
                return false;
            }
            destinations.add(dst);
        }

        for (int i = 0; i < group.size(); i++) {
            Path from = group.get(i);
            Path to = destinations.get(i);
            try {
                log.info("移动文件: {} -> {}", from, to);
                if (!moveWithFallback(from, to)) {
                    task.getFailedFiles().add(mi.getName() + "（附属文件 "
                            + from.getFileName() + " 移动失败, 详见日志）");
                    return false;
                }
            } catch (Exception e) {
                log.error("移动失败: {} -> {}", from, to, e);
                task.getFailedFiles().add(mi.getName() + "（附属文件 "
                        + from.getFileName() + " 失败: " + e.getMessage() + "）");
                return false;
            }
        }
        return true;
    }

    public MoveTask getProgress(String taskId) {
        return tasks.get(taskId);
    }

    /** 把 Emby 返回的本地目录路径解析成当前进程可见的目录。远程地址或不存在时返回 null。 */
    public Path toLocalDir(String rawPath) {
        if (rawPath == null || isRemotePath(rawPath)) {
            return null;
        }
        String normalized = normalizePath(rawPath);
        if (normalized == null) {
            return null;
        }
        try {
            Path dir = Paths.get(normalized).normalize();
            if (!Files.isDirectory(dir)) {
                return null;
            }
            return dir.toRealPath();
        } catch (IOException e) {
            log.warn("本地目录不可访问: {}", rawPath);
            return null;
        }
    }

    /**
     * 目录路径用目录本身，文件路径用它所在的父目录。远程路径或不存在时返回 null。
     */
    public Path toLocalContainingDir(String rawPath) {
        if (rawPath == null || isRemotePath(rawPath)) {
            return null;
        }
        String normalized = normalizePath(rawPath);
        if (normalized == null) {
            return null;
        }
        try {
            Path path = Paths.get(normalized).normalize();
            if (Files.isDirectory(path)) {
                return path.toRealPath();
            }
            if (Files.isRegularFile(path)) {
                Path parent = path.getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    return parent.toRealPath();
                }
            }
            return null;
        } catch (IOException e) {
            log.warn("本地路径不可访问: {}", rawPath);
            return null;
        }
    }

    /**
     * 把相对路径解析到 root 之内。空相对路径就是 root。
     * 含 .. 或符号链接逃出 root 时返回 null。
     */
    public Path resolveInside(Path root, String relative) {
        if (root == null) {
            return null;
        }
        try {
            Path rootReal = root.toRealPath();
            String rel = relative == null ? "" : relative.trim().replace('\\', '/');
            while (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            if (rel.contains("\0")) {
                return null;
            }
            Path target = rel.isEmpty() ? rootReal : rootReal.resolve(rel).normalize();
            if (!target.startsWith(rootReal)) {
                return null;
            }
            if (Files.exists(target)) {
                Path real = target.toRealPath();
                if (!real.startsWith(rootReal)) {
                    return null;
                }
                return real;
            }
            return target;
        } catch (IOException e) {
            log.warn("解析目录内路径失败 root={} rel={}", root, relative);
            return null;
        }
    }

    public boolean isImageFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 只列这一层：子目录、图片、以及其他文件。 */
    public FolderListing listFolderLevel(Path dir) {
        FolderListing listing = new FolderListing();
        if (dir == null || !Files.isDirectory(dir)) {
            return listing;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path child : ds) {
                String name = child.getFileName() == null ? "" : child.getFileName().toString();
                if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                    continue;
                }
                if (Files.isDirectory(child)) {
                    listing.getDirectories().add(name);
                } else if (Files.isRegularFile(child) && isImageFile(child)) {
                    listing.getImages().add(name);
                } else if (Files.isRegularFile(child)) {
                    listing.getFiles().add(name);
                }
            }
        } catch (IOException e) {
            log.error("列出目录内容失败: {}", dir, e);
        }
        listing.getDirectories().sort(String::compareToIgnoreCase);
        listing.getImages().sort(String::compareToIgnoreCase);
        listing.getFiles().sort(String::compareToIgnoreCase);
        return listing;
    }

    /** 删除根目录内的文件，或连同内容删除子目录。空相对路径和根目录本身拒绝。 */
    public void deleteEntry(Path root, String relative) throws IOException {
        if (root == null) {
            throw new IOException("找不到对应目录");
        }
        Path rootReal = root.toRealPath();
        Path target = resolveInside(root, relative);
        if (target == null || !Files.exists(target) || target.equals(rootReal)) {
            throw new IOException("找不到对应文件");
        }
        if (Files.isDirectory(target)) {
            Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return;
        }
        Files.delete(target);
    }

    /** 在同一目录内重命名。新名字不能包含路径分隔符或 ..，也不覆盖已有名字。 */
    public void renameEntry(Path root, String relative, String newName) throws IOException {
        if (root == null) {
            throw new IOException("找不到对应目录");
        }
        if (!isSafeEntryName(newName)) {
            throw new IOException("名字不合法");
        }
        String name = newName.trim();
        Path rootReal = root.toRealPath();
        Path target = resolveInside(root, relative);
        if (target == null || !Files.exists(target) || target.equals(rootReal)) {
            throw new IOException("找不到对应文件");
        }
        if (name.equals(target.getFileName().toString())) {
            return;
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("名字不合法");
        }
        Path parentReal = parent.toRealPath();
        if (!parentReal.startsWith(rootReal)) {
            throw new IOException("名字不合法");
        }
        Path dest = parentReal.resolve(name).normalize();
        if (!dest.startsWith(parentReal)) {
            throw new IOException("名字不合法");
        }
        if (Files.exists(dest)) {
            throw new IOException("已存在同名文件");
        }
        Files.move(target, dest);
    }

    private static boolean isSafeEntryName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim();
        if (n.isEmpty() || ".".equals(n) || "..".equals(n)) {
            return false;
        }
        return n.indexOf('/') < 0 && n.indexOf('\\') < 0 && n.indexOf('\0') < 0 && !n.contains("..");
    }

    /** jpg/png/gif/bmp 可以生成缩略图。webp 当前 JDK 读不了。 */
    public boolean canThumbnailName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    /**
     * 生成或返回已缓存的 JPEG 缩略图。长边 360。失败返回 null。
     */
    public Path ensureThumbnail(Path source) {
        if (source == null || !canThumbnailName(source.getFileName() == null ? "" : source.getFileName().toString())
                || !Files.isRegularFile(source)) {
            return null;
        }
        try {
            Path real = source.toRealPath();
            BasicFileAttributes attrs = Files.readAttributes(real, BasicFileAttributes.class);
            String key = real.toString() + "|" + attrs.lastModifiedTime().toMillis() + "|" + attrs.size();
            String hash = sha256(key);
            Path cacheDir = Paths.get(System.getProperty("java.io.tmpdir"), "emby-fav-thumbs");
            Files.createDirectories(cacheDir);
            Path dest = cacheDir.resolve(hash + ".jpg");
            Object lock = thumbLocks.computeIfAbsent(hash, k -> new Object());
            synchronized (lock) {
                if (Files.isRegularFile(dest) && Files.size(dest) > 0) {
                    return dest;
                }
                writeThumbnail(real, dest);
                return Files.isRegularFile(dest) ? dest : null;
            }
        } catch (Exception e) {
            log.warn("生成缩略图失败: {}", source, e);
            return null;
        }
    }

    private void writeThumbnail(Path source, Path dest) throws IOException {
        BufferedImage sampled = readSubsampled(source, THUMB_EDGE);
        if (sampled == null) {
            throw new IOException("无法读取图片");
        }
        BufferedImage thumb = scaleToEdge(sampled, THUMB_EDGE);
        Path tmp = dest.resolveSibling(dest.getFileName().toString() + ".part");
        ImageWriter writer = null;
        try (ImageOutputStream out = ImageIO.createImageOutputStream(tmp.toFile())) {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) {
                throw new IOException("没有 JPEG 编码器");
            }
            writer = writers.next();
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.75f);
            }
            writer.write(null, new IIOImage(thumb, null, null), param);
        } finally {
            if (writer != null) {
                writer.dispose();
            }
        }
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private BufferedImage readSubsampled(Path source, int edge) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(source.toFile())) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                int sample = Math.max(1, Math.max(width, height) / edge);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(sample, sample, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    private BufferedImage scaleToEdge(BufferedImage src, int edge) {
        int width = src.getWidth();
        int height = src.getHeight();
        int longEdge = Math.max(width, height);
        int targetW = width;
        int targetH = height;
        if (longEdge > edge) {
            double scale = (double) edge / longEdge;
            targetW = Math.max(1, (int) Math.round(width * scale));
            targetH = Math.max(1, (int) Math.round(height * scale));
        }
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetW, targetH);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    @Data
    public static class FolderListing {
        private List<String> directories = new ArrayList<>();
        private List<String> images = new ArrayList<>();
        private List<String> files = new ArrayList<>();
    }

    @Data
    public static class MoveTask {
        private String taskId;
        private int total;
        private int done;
        private int failed;
        private String status; // running / done / partial / failed
        private String currentName;
        private String message;
        private List<String> failedFiles;
    }

    @Data
    public static class MoveItem {
        private String itemId;
        private String name;
        private String path;

        public MoveItem() {}

        public MoveItem(String itemId, String name, String path) {
            this.itemId = itemId;
            this.name = name;
            this.path = path;
        }

        public static MoveItem from(EmbyMediaItem item) {
            return new MoveItem(item.getId(), item.getName(), item.resolvePath());
        }
    }
}
