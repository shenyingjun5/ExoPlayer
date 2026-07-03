# ExoPlayer Fork Agent Instructions

## 输出语言

所有 Agent 输出必须使用中文。代码、命令、路径、API 名称和错误日志保持原文。

## 项目定位

本仓库是 Cast-SDK 项目的 ExoPlayer fork，来源为：

- upstream: `https://github.com/google/ExoPlayer.git`
- fork: `https://github.com/shenyingjun5/ExoPlayer.git`
- 工作分支: `labi-rtsp-feedback-exoplayer-2.19.1`

本 fork 的目标不是维护完整播放器产品，而是为 Cast-SDK Android 接收端提供 Android 4.4+ 可用的 patched ExoPlayer 2.19.1 RTSP 模块。

## 核心目标

- 保持 ExoPlayer 2.19.1 的 Android 4.4 支持能力。
- 增强 RTSP/RTP/RTCP 可观测性和低延迟恢复能力。
- 支持接收端发送 RTCP PLI/FIR，请求发送端强制 IDR。
- 暴露 RTP sequence、timestamp、arrival time、reorder queue、gap、drop、late、reset 等诊断指标。
- 选择性 backport AndroidX Media3 中对 RTSP、RTP、H.264/H.265、MediaCodec、Surface 和弱网恢复有正向价值的修复。
- 发布可被 Cast-SDK 依赖的 patched Maven artifact。

## 不做范围

- 不迁移到完整 Media3。
- 不引入 Media3 UI、Session、Transformer、IMA、Cast、下载、Inspector、Compose 等无关模块。
- 不改变 Android 4.4 支持目标。
- 不整目录覆盖 Media3 源码。
- 不做 H.264 软件解码器；解码仍交给 Android 系统 `MediaCodec`。
- 不做 Cast-SDK 业务代码改动；Cast-SDK 依赖变更在 Cast-SDK 仓库内完成。

## 文档入口

本 fork 的需求、设计、backport 策略和发布流程看：

- `labi-docs/rtsp-feedback-enhancement-plan.md`

Cast-SDK 侧关联文档：

- `/Users/shenyingjun/Work/Cast-SDK/docs/plans/2026-07-03-receiver-exoplayer-rtsp-feedback-fork-plan.md`
- `/Users/shenyingjun/Work/Cast-SDK/docs/plans/2026-07-03-receiver-custom-rtsp-stack-long-term-plan.md`

## 修改原则

- 优先只改 `library/rtsp`。
- 如必须改 `library/core`、`library/extractor` 或 renderer，先在文档说明原因和影响范围。
- 每个 backport 必须能对应到 Media3 release note、issue、PR 或 commit。
- 每个协议修复必须补 fixture 或单测。
- 涉及 Android API level 的改动必须保留 API guard，不能破坏 Android 4.4。
- 不引入大依赖，不增加无关模块。

## 发布原则

推荐发布 patched RTSP artifact：

```text
com.zknowai.exoplayer:exoplayer-rtsp:2.19.1-labi.N
```

`N` 每次发布递增。优先只发布 patched `exoplayer-rtsp`；只有当 patch 触达 core/renderer 且无法避免时，再扩大 artifact 范围。

推荐发布渠道：GitHub Pages 静态 Maven repo。GitHub Packages 可作为备用，JitPack 不作为主链路。
