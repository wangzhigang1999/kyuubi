<!--
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
-->

<template>
  <div class="chat-message" :class="{ 'is-user': role === 'user' }">
    <!-- User message -->
    <template v-if="role === 'user'">
      <div class="user-bubble">
        {{ text }}
      </div>
      <div class="avatar avatar-user">
        <el-icon :size="16"><User /></el-icon>
      </div>
    </template>

    <!-- Assistant message -->
    <template v-else>
      <div class="avatar avatar-assistant">
        <el-icon :size="16" color="#fff"><ChatDotRound /></el-icon>
      </div>
      <div class="assistant-bubble">
        <div v-for="(block, idx) in blocks" :key="idx" class="assistant-block">
          <!-- Text block -->
          <div v-if="block.type === 'text'" class="text-block">
            <div
              class="markdown-body"
              v-html="renderMarkdown(block.text || '')"></div>
          </div>

          <!-- Tool call block -->
          <div v-if="block.type === 'tool_call'" class="tool-call-block">
            <div class="tool-header" @click="block.expanded = !block.expanded">
              <div class="tool-header-left">
                <div
                  class="tool-dot"
                  :class="{
                    'is-running': block.result == null,
                    'is-done': block.result != null && !block.isError,
                    'is-error': block.isError
                  }"></div>
                <span class="tool-name">{{ block.name }}</span>
              </div>
              <div class="tool-header-right">
                <span
                  class="tool-status"
                  :class="{
                    'is-running': block.result == null,
                    'is-done': block.result != null && !block.isError,
                    'is-error': block.isError
                  }">
                  {{
                    block.result != null
                      ? block.isError
                        ? 'Error'
                        : 'Done'
                      : 'Running...'
                  }}
                </span>
                <el-icon
                  class="chevron"
                  :class="{ 'is-expanded': block.expanded }">
                  <ArrowDown />
                </el-icon>
              </div>
            </div>
            <Transition name="tool-expand">
              <div v-if="block.expanded" class="tool-body">
                <div v-if="block.args" class="tool-section">
                  <div class="tool-section-label">Arguments</div>
                  <div class="tool-pre-wrapper">
                    <pre class="tool-pre">{{ formatArgs(block.args) }}</pre>
                    <button
                      class="copy-btn"
                      title="Copy"
                      @click.stop="copyText(block.args || '')">
                      <el-icon :size="12"><DocumentCopy /></el-icon>
                    </button>
                  </div>
                </div>
                <div v-if="block.result != null" class="tool-section">
                  <div class="tool-section-label">Result</div>
                  <div v-if="block.isError" class="tool-pre-wrapper">
                    <pre class="tool-pre is-error">{{ block.result }}</pre>
                    <button
                      class="copy-btn"
                      title="Copy"
                      @click.stop="copyText(block.result || '')">
                      <el-icon :size="12"><DocumentCopy /></el-icon>
                    </button>
                  </div>
                  <div v-else class="tool-result-markdown">
                    <div
                      class="markdown-body"
                      v-html="renderMarkdown(block.result || '')"></div>
                    <button
                      class="copy-btn"
                      title="Copy"
                      @click.stop="copyText(block.result || '')">
                      <el-icon :size="12"><DocumentCopy /></el-icon>
                    </button>
                  </div>
                </div>
              </div>
            </Transition>
          </div>
        </div>

        <!-- Streaming indicator -->
        <div v-if="streaming" class="streaming-indicator">
          <span class="streaming-dot"></span>
          <span class="streaming-label">Generating...</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script lang="ts" setup>
  import {
    User,
    ChatDotRound,
    ArrowDown,
    DocumentCopy
  } from '@element-plus/icons-vue'
  import { marked } from 'marked'
  import { ElMessage } from 'element-plus'

  export interface ChatBlock {
    type: 'text' | 'tool_call'
    text?: string
    name?: string
    args?: string
    result?: string
    isError?: boolean
    expanded?: boolean
  }

  defineProps<{
    role: 'user' | 'assistant'
    text?: string
    blocks?: ChatBlock[]
    streaming?: boolean
  }>()

  function renderMarkdown(content: string): string {
    if (!content) return ''
    try {
      return marked.parse(content, { async: false }) as string
    } catch {
      return content
    }
  }

  function formatArgs(args: string): string {
    try {
      return JSON.stringify(JSON.parse(args), null, 2)
    } catch {
      return args
    }
  }

  async function copyText(text: string) {
    try {
      await navigator.clipboard.writeText(text)
      ElMessage.success({ message: 'Copied', duration: 1500 })
    } catch {
      ElMessage.warning('Copy failed')
    }
  }
</script>

<style lang="scss" scoped>
  .chat-message {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    margin-bottom: 20px;

    &.is-user {
      justify-content: flex-end;
    }
  }

  .avatar {
    flex-shrink: 0;
    width: 32px;
    height: 32px;
    border-radius: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-top: 2px;
  }
  .avatar-user {
    background: #e8f4ff;
    color: #409eff;
    order: 1;
  }
  .avatar-assistant {
    background: linear-gradient(135deg, #409eff, #3a7bd5);
  }

  .user-bubble {
    max-width: 70%;
    padding: 10px 16px;
    border-radius: 16px 16px 4px 16px;
    background: #409eff;
    color: #fff;
    font-size: 14px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
    box-shadow: 0 2px 8px rgba(64, 158, 255, 0.2);
  }

  .assistant-bubble {
    max-width: 85%;
    min-width: 0;
    background: #fff;
    border: 1px solid #e5e6eb;
    border-radius: 4px 16px 16px 16px;
    overflow: hidden;
  }

  .assistant-block {
    &:not(:first-child) {
      border-top: 1px solid #f0f0f0;
    }
  }

  .text-block {
    padding: 12px 16px;
    font-size: 14px;
    line-height: 1.7;
    overflow-x: auto;

    .markdown-body {
      :deep(p) {
        margin: 0 0 8px;
        &:last-child {
          margin-bottom: 0;
        }
      }
      :deep(pre) {
        background: #1e1e2e;
        color: #cdd6f4;
        border-radius: 8px;
        padding: 14px;
        overflow-x: auto;
        font-size: 13px;
        margin: 8px 0;
        position: relative;
      }
      :deep(code) {
        font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo,
          monospace;
        font-size: 13px;
      }
      :deep(code:not(pre code)) {
        background: #f0f1f3;
        padding: 2px 6px;
        border-radius: 4px;
        color: #c7254e;
      }
      :deep(table) {
        border-collapse: collapse;
        min-width: 100%;
        width: max-content;
        margin: 8px 0;
        border-radius: 6px;
        overflow: hidden;
      }
      :deep(th),
      :deep(td) {
        border: 1px solid #e5e6eb;
        padding: 8px 12px;
        text-align: left;
        font-size: 13px;
        white-space: nowrap;
      }
      :deep(th) {
        background: #f7f8fa;
        font-weight: 600;
        color: #4e5969;
      }
      :deep(ul),
      :deep(ol) {
        padding-left: 20px;
        margin: 4px 0 8px;
      }
      :deep(li) {
        margin: 2px 0;
      }
    }
  }

  // Tool call block
  .tool-call-block {
    overflow: hidden;
    background: #fff;
  }

  .tool-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 14px;
    cursor: pointer;
    user-select: none;
    font-size: 13px;
    transition: background 0.15s;

    &:hover {
      background: #f7f8fa;
    }
  }
  .tool-header-left {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
    flex: 1;
  }
  .tool-header-right {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-shrink: 0;
    margin-left: 16px;
  }

  .tool-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    flex-shrink: 0;

    &.is-running {
      background: #e6a23c;
      animation: pulse-dot 1.5s infinite;
    }
    &.is-done {
      background: #67c23a;
    }
    &.is-error {
      background: #f56c6c;
    }
  }

  @keyframes pulse-dot {
    0%,
    100% {
      opacity: 1;
    }
    50% {
      opacity: 0.4;
    }
  }

  .tool-name {
    font-weight: 600;
    color: #303133;
    font-family: 'SFMono-Regular', Consolas, monospace;
    font-size: 13px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .tool-status {
    font-size: 12px;
    font-weight: 500;

    &.is-running {
      color: #e6a23c;
    }
    &.is-done {
      color: #67c23a;
    }
    &.is-error {
      color: #f56c6c;
    }
  }

  .chevron {
    transition: transform 0.25s ease;
    color: #c0c4cc;

    &.is-expanded {
      transform: rotate(180deg);
    }
  }

  .tool-body {
    border-top: 1px solid #f0f0f0;
  }

  .tool-section {
    padding: 10px 14px;

    &:not(:last-child) {
      border-bottom: 1px solid #f5f5f5;
    }
  }

  .tool-section-label {
    font-size: 11px;
    font-weight: 600;
    color: #86909c;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    margin-bottom: 6px;
  }

  .tool-pre-wrapper {
    position: relative;

    .copy-btn {
      position: absolute;
      top: 6px;
      right: 6px;
      width: 24px;
      height: 24px;
      border: none;
      border-radius: 4px;
      background: rgba(0, 0, 0, 0.04);
      color: #86909c;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      opacity: 0;
      transition: all 0.15s;

      &:hover {
        background: rgba(0, 0, 0, 0.08);
        color: #4e5969;
      }
    }

    &:hover .copy-btn {
      opacity: 1;
    }
  }

  .tool-result-markdown {
    position: relative;
    padding: 8px 10px;
    background: #f7f8fa;
    border-radius: 6px;
    max-height: 320px;
    overflow: auto;
    font-size: 12px;
    line-height: 1.5;

    .markdown-body {
      :deep(p) {
        margin: 0 0 6px;
        font-size: 12px;
        &:last-child { margin-bottom: 0; }
      }
      :deep(table) {
        border-collapse: collapse;
        width: 100%;
        margin: 4px 0;
        border-radius: 4px;
        overflow: hidden;
        font-size: 11px;
      }
      :deep(th),
      :deep(td) {
        border: 1px solid #e5e6eb;
        padding: 4px 8px;
        text-align: left;
        font-family: 'SFMono-Regular', Consolas, monospace;
        font-size: 11px;
      }
      :deep(th) {
        background: #eef0f4;
        font-weight: 600;
        color: #4e5969;
      }
      :deep(td) {
        color: #303133;
      }
    }

    .copy-btn {
      position: absolute;
      top: 6px;
      right: 6px;
    }
  }

  .tool-pre {
    margin: 0;
    padding: 10px 12px;
    background: #f7f8fa;
    border-radius: 6px;
    font-size: 12px;
    font-family: 'SFMono-Regular', Consolas, monospace;
    white-space: pre-wrap;
    word-break: break-all;
    max-height: 320px;
    overflow-y: auto;
    color: #303133;
    line-height: 1.6;

    &.is-error {
      background: #fff2f0;
      color: #f56c6c;
      border: 1px solid #ffccc7;
    }
  }

  // Streaming indicator
  .streaming-indicator {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 16px;
    border-top: 1px solid #f0f0f0;
    background: #fafbfc;
  }

  .streaming-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #409eff;
    animation: pulse-stream 1.2s infinite ease-in-out;
  }

  @keyframes pulse-stream {
    0%, 100% { opacity: 1; transform: scale(1); }
    50% { opacity: 0.4; transform: scale(0.75); }
  }

  .streaming-label {
    font-size: 12px;
    color: #909399;
    font-weight: 500;
  }

  // Tool expand transition
  .tool-expand-enter-active,
  .tool-expand-leave-active {
    transition: all 0.25s ease;
    overflow: hidden;
  }
  .tool-expand-enter-from,
  .tool-expand-leave-to {
    opacity: 0;
    max-height: 0;
  }
  .tool-expand-enter-to,
  .tool-expand-leave-from {
    max-height: 600px;
  }
</style>
