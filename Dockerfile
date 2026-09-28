# syntax=docker/dockerfile:1



# 使用前请先在本地打包：mvn -DskipTests package

#

# 基础镜像可通过 build-arg 指定（服务器拉不到 docker.io 时用国内镜像）：

#   docker build --build-arg BASE_IMAGE=m.daocloud.io/docker.io/library/openjdk:8-jre-slim -t emby-fav-manager:1.0.0 .

#

# 网盘/中文路径：请与 Emby 使用相同 user（compose: user: "PUID:PGID"），

# PUID 环境变量对本镜像无效。

ARG BASE_IMAGE=m.daocloud.io/docker.io/library/openjdk:8-jre-slim

FROM ${BASE_IMAGE}



WORKDIR /app



ENV LANG=C.UTF-8 \

    LC_ALL=C.UTF-8 \

    LANGUAGE=C.UTF-8



RUN groupadd -r appuser && useradd -r -g appuser -u 1000 appuser



COPY target/emby-fav-manager-1.0.0.jar app.jar



USER appuser



EXPOSE 8383



ENV SERVER_PORT=8383 \

    JAVA_OPTS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8"



ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]


