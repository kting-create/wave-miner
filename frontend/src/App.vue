<script setup lang="ts">
import { VideoCamera } from '@element-plus/icons-vue'
</script>

<template>
  <el-container style="min-height: 100vh">
    <el-header style="border-bottom: 1px solid #eee; display: flex; align-items: center; gap: 16px">
      <el-icon :size="22"><VideoCamera /></el-icon>
      <span style="font-size: 18px; font-weight: 600">wave-miner</span>
      <el-menu mode="horizontal" router :default-active="$route.path" style="flex: 1; border-bottom: none">
        <el-menu-item index="/">新建任务</el-menu-item>
        <el-menu-item index="/tasks">任务列表</el-menu-item>
        <el-menu-item index="/settings">设置</el-menu-item>
      </el-menu>
    </el-header>
    <el-main style="max-width: 1080px; margin: 0 auto; width: 100%">
      <router-view />
    </el-main>
  </el-container>
</template>
