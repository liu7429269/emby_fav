#!/bin/bash
# 在有 Docker 的机器上运行，导出基础镜像 tar 供离线服务器使用
# 用法: bash scripts/save-base-image.sh
# 输出: docker/openjdk-8-jre-slim.tar

set -euo pipefail

IMAGE="openjdk:8-jre-slim"
OUT_DIR="$(cd "$(dirname "$0")/../docker" && pwd)"
OUT_FILE="$OUT_DIR/openjdk-8-jre-slim.tar"

mkdir -p "$OUT_DIR"

echo "正在拉取 $IMAGE ..."
docker pull "$IMAGE"

echo "正在导出到 $OUT_FILE ..."
docker save "$IMAGE" -o "$OUT_FILE"

echo "完成。上传到服务器后执行："
echo "  docker load -i openjdk-8-jre-slim.tar"
echo "  docker build --build-arg BASE_IMAGE=openjdk:8-jre-slim -t emby-fav-manager:1.0.0 ."
