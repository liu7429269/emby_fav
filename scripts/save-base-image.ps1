# 在有 Docker 的机器上运行，导出基础镜像 tar 供离线服务器使用
# 用法: .\scripts\save-base-image.ps1
# 输出: docker/openjdk-8-jre-slim.tar

$ErrorActionPreference = "Stop"
$Image = "openjdk:8-jre-slim"
$OutDir = Join-Path $PSScriptRoot "..\docker"
$OutFile = Join-Path $OutDir "openjdk-8-jre-slim.tar"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 docker 命令，请在已安装 Docker 的机器上运行此脚本"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host "正在拉取 $Image ..."
docker pull $Image

Write-Host "正在导出到 $OutFile ..."
docker save $Image -o $OutFile

Write-Host "完成。将 docker/openjdk-8-jre-slim.tar 上传到服务器后执行："
Write-Host "  docker load -i openjdk-8-jre-slim.tar"
Write-Host "  docker build --build-arg BASE_IMAGE=openjdk:8-jre-slim -t emby-fav-manager:1.0.0 ."
