# Emby媒体管理器

一个基于Spring Boot的Emby媒体库管理和过滤工具，可以通过Web界面浏览和过滤Emby媒体库中的媒体内容。

## 功能特性

- ✅ 从配置文件读取Emby服务器配置（地址、账号、密码）
- ✅ 通过Emby Server API获取媒体信息
- ✅ 媒体库选择页面
- ✅ 媒体过滤页面（支持按标签和收藏状态过滤）
- ✅ 美观的现代化UI界面

## 技术栈

- Spring Boot 3.2.0
- Java 17
- Thymeleaf（模板引擎）
- WebFlux（HTTP客户端）
- Lombok

## 快速开始

### 1. 配置Emby服务器信息

编辑 `src/main/resources/application.yml` 文件，填入你的Emby服务器信息：

```yaml
emby:
  server:
    url: http://your-emby-server:8096  # 你的Emby服务器地址
    username: your_username            # Emby用户名
    password: your_password            # Emby密码
    api-key:                           # 可选，如果提供API Key则优先使用
```

### 2. 编译和运行

使用Maven编译项目：

```bash
mvn clean package
```

运行项目：

```bash
java -jar target/emby-fav-manager-1.0.0.jar
```

或者使用Maven直接运行：

```bash
mvn spring-boot:run
```

### 3. 访问应用

启动后，在浏览器中访问：

- 首页/媒体库选择：http://localhost:8080/libraries
- 媒体过滤页面：http://localhost:8080/media?libraryId=YOUR_LIBRARY_ID

## 使用说明

### 媒体库选择页面

1. 访问应用首页，系统会自动连接到Emby服务器并获取所有可用的媒体库
2. 点击任意媒体库卡片，进入该媒体库的浏览页面

### 媒体过滤页面

在媒体过滤页面，你可以：

1. **选择媒体库**：从下拉菜单中选择要浏览的媒体库
2. **按标签过滤**：选择特定标签来筛选媒体
3. **仅显示收藏**：勾选"仅显示收藏"复选框，只显示已收藏的媒体
4. **组合过滤**：可以同时使用标签和收藏状态进行组合过滤

## 项目结构

```
src/
├── main/
│   ├── java/com/emby/
│   │   ├── EmbyFavManagerApplication.java    # 主应用类
│   │   ├── config/
│   │   │   ├── EmbyProperties.java          # Emby配置属性类
│   │   │   └── WebClientConfig.java          # WebClient配置
│   │   ├── controller/
│   │   │   └── EmbyController.java          # 控制器
│   │   ├── dto/
│   │   │   ├── EmbyLibrary.java             # 媒体库DTO
│   │   │   ├── EmbyMediaItem.java           # 媒体项DTO
│   │   │   ├── EmbyAuthResponse.java        # 认证响应DTO
│   │   │   └── EmbyItemsResponse.java       # 媒体项列表响应DTO
│   │   └── service/
│   │       └── EmbyApiService.java          # Emby API服务
│   └── resources/
│       ├── application.yml                  # 应用配置文件
│       └── templates/
│           ├── libraries.html               # 媒体库选择页面
│           ├── media.html                   # 媒体过滤页面
│           └── error.html                  # 错误页面
└── pom.xml                                  # Maven配置文件
```

## API端点

- `GET /` - 重定向到媒体库选择页面
- `GET /libraries` - 显示媒体库选择页面
- `GET /media` - 显示媒体过滤页面
  - 参数：
    - `libraryId` (必需) - 媒体库ID
    - `tags` (可选) - 标签过滤
    - `isFavorite` (可选) - 是否仅显示收藏
- `POST /select-library` - 处理媒体库选择
- `GET /api/image/{itemId}` - 图片代理端点

## 注意事项

1. 确保Emby服务器可以正常访问
2. 确保提供的用户名和密码具有访问媒体库的权限
3. 如果使用API Key认证，请确保API Key具有足够的权限
4. 首次连接时，系统会自动进行认证

## 故障排除

### 连接失败

- 检查Emby服务器地址是否正确
- 检查网络连接是否正常
- 检查防火墙设置

### 认证失败

- 检查用户名和密码是否正确
- 如果使用API Key，检查API Key是否有效
- 查看应用日志获取详细错误信息

### 无法获取媒体库

- 检查用户权限是否足够
- 检查Emby服务器是否正常运行
- 查看应用日志获取详细错误信息

## 许可证

MIT License
