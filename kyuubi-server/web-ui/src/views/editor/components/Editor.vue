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
  <div class="editor-container">
    <splitpanes
      class="default-theme"
      horizontal
      style="height: 100%"
      @resized="handleResized">
      <pane :size="sqlEditorSize" min-size="20">
        <div class="editor-pane">
          <div class="editor-pane__header">
            <el-space>
              <el-button
                :disabled="!param.engineType || !execution.content"
                :loading="execution.loading"
                type="success"
                icon="VideoPlay"
                @click="handleQuerySql">
                {{ $t('operation.run') }}
              </el-button>
              <el-dropdown @command="handleChangeLimit">
                <span class="el-dropdown-link">
                  Limit: {{ limit }}
                  <el-icon class="el-icon--right">
                    <arrow-down />
                  </el-icon>
                </span>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      v-for="l in LIMIT_OPTIONS"
                      :key="l"
                      :command="l">
                      Limit: {{ l }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-select
                v-model="param.engineType"
                :disabled="false"
                :placeholder="$t('engine_type')">
                <el-option
                  v-for="item in getEngineType()"
                  :key="item"
                  :label="item"
                  :value="item" />
              </el-select>
            </el-space>
          </div>
          <div class="editor-pane__body">
            <MonacoEditor
              ref="monacoEditor"
              v-model="execution.content"
              :language="editorVariables.language"
              :theme="theme"
              @editor-mounted="editorMounted"
              @change="handleContentChange"
              @editor-save="editorSave" />
          </div>
        </div>
      </pane>
      <pane :size="100 - sqlEditorSize" min-size="10">
        <div class="result-pane">
          <el-tabs v-model="activeTab" type="card" class="result-el-tabs">
            <el-tab-pane
              :label="`${$t('result')}${
                execution.result?.length
                  ? ` (${execution.result?.length})`
                  : ''
              }`"
              name="result">
              <Result
                :data="execution.result"
                :error-messages="execution.errorMessages" />
            </el-tab-pane>
            <el-tab-pane
              v-loading="execution.loading"
              :label="$t('log')"
              name="log">
              <Log :data="execution.log" />
            </el-tab-pane>
          </el-tabs>
          <div class="result-pane-actions">
            <el-tooltip
              :content="$t('maximize')"
              placement="top"
              :show-arrow="false">
              <el-icon @click="maximizeResultPane">
                <ArrowUp />
              </el-icon>
            </el-tooltip>
            <el-tooltip
              :content="$t('minimize')"
              placement="top"
              :show-arrow="false">
              <el-icon @click="minimizeResultPane">
                <ArrowDown />
              </el-icon>
            </el-tooltip>
            <el-tooltip
              :content="$t('reset')"
              placement="top"
              :show-arrow="false">
              <el-icon @click="resetPane">
                <DCaret />
              </el-icon>
            </el-tooltip>
          </div>
        </div>
      </pane>
    </splitpanes>
  </div>
</template>

<script lang="ts" setup>
  import { Splitpanes, Pane } from 'splitpanes'
  import 'splitpanes/dist/splitpanes.css'
  import MonacoEditor from '@/components/monaco-editor/index.vue'
  import Result from './Result.vue'
  import Log from './Log.vue'
  import { ref, reactive, onUnmounted, toRaw } from 'vue'
  import type { Ref } from 'vue'
  import * as monaco from 'monaco-editor'
  import { format } from 'sql-formatter'
  import { ElMessage } from 'element-plus'
  import { useI18n } from 'vue-i18n'
  import { getEngineType } from '@/utils/engine'
  import {
    openSession,
    closeSession,
    runSql,
    getSqlRowset,
    getSqlMetadata,
    getLog,
    closeOperation
  } from '@/api/editor'
  import type {
    IResponse,
    ISqlResult,
    IFields,
    ILog,
    IErrorMessage,
    IError
  } from './types'

  const { t } = useI18n()

  const LIMIT_OPTIONS = [10, 50, 100]
  const paneSizes = {
    DEFAULT: 60,
    EDITOR_MINIMIZED: 95,
    RESULT_MINIMIZED: 20
  }

  const param = reactive({
    engineType: 'SPARK_SQL'
  })
  const limit = ref(10)
  const monacoEditor = ref()
  const activeTab = ref('result')
  const sessionIdentifier = ref('')
  const theme = ref('customTheme')

  const sqlEditorSize = ref(paneSizes.DEFAULT)

  const execution = reactive({
    content: '',
    loading: false,
    result: null as any[] | null,
    log: '',
    errorMessages: [] as IErrorMessage[]
  })

  const editorVariables = reactive({
    editor: {} as any,
    language: 'sql'
  })

  const handleResized = (event: any) => {
    sqlEditorSize.value = event[0].size
  }

  const maximizeResultPane = () => {
    sqlEditorSize.value = paneSizes.RESULT_MINIMIZED
  }

  const minimizeResultPane = () => {
    sqlEditorSize.value = paneSizes.EDITOR_MINIMIZED
  }

  const resetPane = () => {
    sqlEditorSize.value = paneSizes.DEFAULT
  }

  const editorMounted = (editor: monaco.editor.IStandaloneCodeEditor) => {
    editorVariables.editor = editor
  }

  const handleFormat = () => {
    const editor = toRaw(editorVariables.editor)
    if (editor && typeof editor.getValue === 'function') {
      editor.setValue(format(editor.getValue()))
    }
  }

  const editorSave = () => {
    handleFormat()
  }

  const handleContentChange = (value: string) => {
    execution.content = value
  }

  const resetExecutionState = () => {
    execution.result = null
    execution.log = ''
    execution.errorMessages = []
  }

  const handleQuerySql = async () => {
    execution.loading = true
    resetExecutionState()

    try {
      if (!sessionIdentifier.value) {
        const openSessionResponse: IResponse = await openSession({
          configs: { 'kyuubi.engine.type': param.engineType }
        })
        sessionIdentifier.value = openSessionResponse.identifier
      }

      const selectValue = monacoEditor.value.getSelectValue()
      const statementToRun = selectValue || execution.content
      if (!statementToRun.trim()) {
        ElMessage({ message: t('message.empty_sql'), type: 'warning' })
        return
      }

      const runSqlResponse: IResponse = await runSql(
        { statement: statementToRun, runAsync: false },
        sessionIdentifier.value
      )

      const operationHandle = runSqlResponse.identifier
      const promises = [
        fetchResults(operationHandle),
        fetchLogs(operationHandle)
      ]

      await Promise.all(promises)
      await closeOperation(operationHandle)
    } catch (err: any) {
      const errorTitle = err.isSessionError
        ? t('message.session_error')
        : t('message.run_sql_failed')
      postError(err, errorTitle)
    } finally {
      execution.loading = false
    }
  }

  async function fetchResults(operationHandle: string) {
    try {
      const [rowset, metadata] = await Promise.all([
        getSqlRowset({
          operationHandleStr: operationHandle,
          fetchorientation: 'FETCH_NEXT',
          maxrows: limit.value
        }),
        getSqlMetadata({
          operationHandleStr: operationHandle
        })
      ])

      execution.result =
        rowset?.rows?.map((row: IFields) => {
          const map: { [key: string]: any } = {}
          row.fields?.forEach(({ value }: ISqlResult, index: number) => {
            map[metadata.columns[index]?.columnName] = value
          })
          return map
        }) || []
    } catch (err: any) {
      postError(err, t('message.get_sql_result_failed'))
      execution.result = []
    }
  }

  async function fetchLogs(operationHandle: string) {
    try {
      const logData: ILog = await getLog(operationHandle)
      execution.log = logData?.logRowSet?.join('\r\n') || ''
    } catch (err: any) {
      postError(err, t('message.get_sql_log_failed'))
      execution.log = ''
    }
  }

  const postError = (err: IError, title: string) => {
    const description = err?.response?.data?.message || err?.message || ''
    execution.errorMessages.push({ title, description })
    ElMessage({ message: title, type: 'error' })
  }

  const handleChangeLimit = (command: number) => {
    limit.value = command
  }

  const customMonacoEditorTheme = () => {
    monaco.editor.defineTheme(theme.value, {
      base: 'vs',
      inherit: true,
      rules: [],
      colors: {
        'editor.foreground': '#000000',
        'editor.background': '#ffffff',
        'editor.lineHighlightBackground': '#f6f6f6',
        'editorGutter.background': '#e2e2e2'
      }
    })
    monaco.editor.setTheme(theme.value)
  }
  customMonacoEditorTheme()

  onUnmounted(() => {
    if (sessionIdentifier.value) {
      closeSession(sessionIdentifier.value)
    }
  })
</script>

<style lang="scss" scoped>
  .editor-container {
    height: 100%;
    background-color: #f5f7fa;
  }

  .editor-pane {
    display: flex;
    flex-direction: column;
    height: 100%;
    &__header {
      padding: 8px 12px;
      flex-shrink: 0;
      border-bottom: 1px solid #e4e7ed;
    }
    &__body {
      flex-grow: 1;
      overflow: hidden;
    }
  }

  .result-pane {
    height: 100%;
    display: flex;
    flex-direction: column;
    position: relative;
  }

  .result-el-tabs {
    flex-grow: 1;
    display: flex;
    flex-direction: column;
    height: 100%;
    background: #fff;

    :deep(.el-tabs__header) {
      flex-shrink: 0;
      margin: 0;
    }
    :deep(.el-tabs__content) {
      flex-grow: 1;
      overflow: auto;
      padding: 12px;
    }
  }

  .result-pane-actions {
    position: absolute;
    top: 6px;
    right: 16px;
    display: flex;
    align-items: center;
    z-index: 1;

    .el-icon {
      cursor: pointer;
      margin-left: 12px;
      font-size: 16px;
      color: #606266;
      &:hover {
        color: #1890ff;
      }
    }
  }
</style>

<style lang="scss">
  .splitpanes.default-theme {
    .splitpanes__pane {
      background-color: #ffffff;
    }
    .splitpanes__splitter {
      background-color: #f5f7fa;
      position: relative;
      height: 5px;
      border: none;
      &:hover {
        background-color: #e4e7ed;
      }
      &::after {
        content: '';
        position: absolute;
        top: 50%;
        left: 50%;
        transform: translate(-50%, -50%);
        width: 30px;
        height: 1px;
        background: #cccccc;
      }
    }
  }
</style>
