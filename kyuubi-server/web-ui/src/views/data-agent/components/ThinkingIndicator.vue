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
  <div class="thinking">
    <div class="thinking-avatar">
      <el-icon :size="16" color="#fff"><ChatDotRound /></el-icon>
    </div>
    <div class="thinking-bubble">
      <div class="dots">
        <span class="dot"></span>
        <span class="dot"></span>
        <span class="dot"></span>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import { ChatDotRound } from '@element-plus/icons-vue'
</script>

<style lang="scss" scoped>
  .thinking {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    margin-bottom: 20px;
  }

  .thinking-avatar {
    width: 32px;
    height: 32px;
    border-radius: 10px;
    background: linear-gradient(135deg, #409eff, #3a7bd5);
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  .thinking-bubble {
    padding: 14px 18px;
    border-radius: 4px 16px 16px 16px;
    background: #fff;
    border: 1px solid #e5e6eb;
  }

  .dots {
    display: flex;
    gap: 5px;
    align-items: center;
    height: 20px;
  }

  .dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #409eff;
    animation: typing 1.4s infinite ease-in-out;

    &:nth-child(1) {
      animation-delay: 0ms;
    }
    &:nth-child(2) {
      animation-delay: 200ms;
    }
    &:nth-child(3) {
      animation-delay: 400ms;
    }
  }

  @keyframes typing {
    0%,
    60%,
    100% {
      transform: translateY(0);
      opacity: 0.4;
    }
    30% {
      transform: translateY(-6px);
      opacity: 1;
    }
  }
</style>
