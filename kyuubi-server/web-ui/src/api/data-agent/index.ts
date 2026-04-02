/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import request from '@/utils/request'

export interface SessionOpenRequest {
  configs?: Record<string, string>
}

export interface SessionHandle {
  identifier: string
  secretId: string
}

export interface SseEvent {
  event: string
  data: string
}

export function openSession(data: SessionOpenRequest) {
  return request({
    url: 'api/v1/sessions',
    method: 'post',
    data
  })
}

export function closeSession(sessionHandle: string) {
  return request({
    url: `api/v1/sessions/${sessionHandle}`,
    method: 'delete'
  })
}

/**
 * Send a chat message and receive SSE streaming response.
 * Uses native fetch + ReadableStream since axios doesn't support SSE.
 */
export function chatStream(
  sessionHandle: string,
  text: string,
  onEvent: (event: SseEvent) => void,
  signal?: AbortSignal
): Promise<void> {
  return fetch(`/api/v1/data-agent/${sessionHandle}/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream'
    },
    body: JSON.stringify({ text }),
    signal
  }).then(async (response) => {
    if (!response.ok) {
      const body = await response.text().catch(() => '')
      let errorMsg = `HTTP ${response.status}: ${response.statusText}`
      if (body) {
        try {
          const json = JSON.parse(body)
          errorMsg = json.message || body
        } catch {
          errorMsg = body
        }
      }
      throw new Error(errorMsg)
    }
    const reader = response.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let currentEvent = ''
    let currentData = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('event: ')) {
          currentEvent = line.slice(7).trim()
        } else if (line.startsWith('data: ')) {
          currentData = line.slice(6)
        } else if (line === '' && currentEvent) {
          onEvent({ event: currentEvent, data: currentData })
          currentEvent = ''
          currentData = ''
        }
      }
    }
  })
}
