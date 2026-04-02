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

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { chatStream, SseEvent } from '@/api/data-agent'

/**
 * Helper: create a mock fetch Response whose body is a ReadableStream
 * that yields the given string chunks sequentially.
 */
function mockFetchWithChunks(chunks: string[]) {
  const encoder = new TextEncoder()
  let index = 0

  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (index < chunks.length) {
        controller.enqueue(encoder.encode(chunks[index++]))
      } else {
        controller.close()
      }
    }
  })

  return vi.fn().mockResolvedValue({
    ok: true,
    body: stream
  } as unknown as Response)
}

describe('chatStream SSE parser', () => {
  const originalFetch = globalThis.fetch

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('parses a single complete SSE event in one chunk', async () => {
    const events: SseEvent[] = []
    globalThis.fetch = mockFetchWithChunks([
      'event: content\ndata: {"text":"hello"}\n\n'
    ])

    await chatStream('handle', 'hi', (e) => events.push(e))

    expect(events).toEqual([{ event: 'content', data: '{"text":"hello"}' }])
  })

  it('parses multiple complete SSE events in one chunk', async () => {
    const events: SseEvent[] = []
    globalThis.fetch = mockFetchWithChunks([
      'event: content\ndata: {"text":"hello"}\n\nevent: done\ndata: {}\n\n'
    ])

    await chatStream('handle', 'hi', (e) => events.push(e))

    expect(events).toHaveLength(2)
    expect(events[0]).toEqual({ event: 'content', data: '{"text":"hello"}' })
    expect(events[1]).toEqual({ event: 'done', data: '{}' })
  })

  it('handles SSE event split across two chunks (event in chunk1, data+blank in chunk2)', async () => {
    const events: SseEvent[] = []
    globalThis.fetch = mockFetchWithChunks([
      'event: content\n',
      'data: {"text":"hello"}\n\n'
    ])

    await chatStream('handle', 'hi', (e) => events.push(e))

    expect(events).toHaveLength(1)
    expect(events[0]).toEqual({ event: 'content', data: '{"text":"hello"}' })
  })

  it('handles SSE event split across three chunks', async () => {
    const events: SseEvent[] = []
    globalThis.fetch = mockFetchWithChunks([
      'event: content\n',
      'data: {"text":"hello"}\n',
      '\n'
    ])

    await chatStream('handle', 'hi', (e) => events.push(e))

    expect(events).toHaveLength(1)
    expect(events[0]).toEqual({ event: 'content', data: '{"text":"hello"}' })
  })

  it('handles chunk boundary in the middle of a data line', async () => {
    const events: SseEvent[] = []
    globalThis.fetch = mockFetchWithChunks([
      'event: content\ndata: {"text":"hel',
      'lo"}\n\n'
    ])

    await chatStream('handle', 'hi', (e) => events.push(e))

    expect(events).toHaveLength(1)
    expect(events[0]).toEqual({ event: 'content', data: '{"text":"hello"}' })
  })

  it('handles multiple events with chunk boundaries between them', async () => {
    const events: SseEvent[] = []
    globalThis.fetch = mockFetchWithChunks([
      'event: content\ndata: {"text":"The database"}\n\nevent: content\n',
      'data: {"text":" contains **3 tables"}\n\nevent: content\ndata: {"text":"**:\\n\\n1"}\n\n',
      'event: done\ndata: {}\n\n'
    ])

    await chatStream('handle', 'hi', (e) => events.push(e))

    expect(events).toHaveLength(4)
    expect(events[0].data).toBe('{"text":"The database"}')
    expect(events[1].data).toBe('{"text":" contains **3 tables"}')
    expect(events[2].data).toBe('{"text":"**:\\n\\n1"}')
    expect(events[3]).toEqual({ event: 'done', data: '{}' })
  })

  it('handles blank line (event terminator) arriving in a separate chunk', async () => {
    const events: SseEvent[] = []
    globalThis.fetch = mockFetchWithChunks([
      'event: content\ndata: {"text":"hello"}',
      '\n\n'
    ])

    await chatStream('handle', 'hi', (e) => events.push(e))

    expect(events).toHaveLength(1)
    expect(events[0]).toEqual({ event: 'content', data: '{"text":"hello"}' })
  })

  it('throws on non-ok HTTP response with JSON error body', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      statusText: 'Service Unavailable',
      text: () => Promise.resolve('{"message":"Engine launch failed: connection refused"}')
    } as unknown as Response)

    await expect(chatStream('handle', 'hi', () => {})).rejects.toThrow(
      'Engine launch failed: connection refused'
    )
  })

  it('throws on non-ok HTTP response with plain text body', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      text: () => Promise.resolve('server error')
    } as unknown as Response)

    await expect(chatStream('handle', 'hi', () => {})).rejects.toThrow(
      'server error'
    )
  })

  it('throws on non-ok HTTP response with empty body', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      statusText: 'Service Unavailable',
      text: () => Promise.resolve('')
    } as unknown as Response)

    await expect(chatStream('handle', 'hi', () => {})).rejects.toThrow(
      'HTTP 503: Service Unavailable'
    )
  })

  it('handles mixed event types (content, tool_call, tool_result, done)', async () => {
    const events: SseEvent[] = []
    const sseText = [
      'event: content\ndata: {"text":"Let me check"}\n\n',
      'event: tool_call\ndata: {"name":"sql","args":"SELECT 1"}\n\n',
      'event: tool_result\ndata: {"name":"sql","output":"1"}\n\n',
      'event: done\ndata: {}\n\n'
    ].join('')

    globalThis.fetch = mockFetchWithChunks([sseText])
    await chatStream('handle', 'hi', (e) => events.push(e))

    expect(events.map((e) => e.event)).toEqual([
      'content',
      'tool_call',
      'tool_result',
      'done'
    ])
  })
})
