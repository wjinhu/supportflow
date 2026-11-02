import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from './client'

const conversation = {
  id: '00000000-0000-4000-8000-000000000001',
  shopId: 100,
  createdAt: '2026-10-31T00:00:00Z',
  updatedAt: '2026-10-31T00:00:00Z',
}
const messageId = '00000000-0000-4000-8000-000000000004'
const csrf = { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'test-csrf' }
const encoder = new TextEncoder()
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'content-type': 'application/json' } })

function turn(reply = '订单已发货🙂\n请留意物流。') {
  return {
    conversation: { ...conversation },
    messages: [
      {
        id: '00000000-0000-4000-8000-000000000002',
        role: 'USER',
        text: '订单发货了吗',
        createdAt: conversation.createdAt,
        response: null,
      },
      {
        id: '00000000-0000-4000-8000-000000000003',
        role: 'ASSISTANT',
        text: reply,
        createdAt: conversation.createdAt,
        response: { reply, type: 'TEXT', orders: [], nextAfterId: null, knowledge: null },
      },
    ],
  }
}

const event = (name: string, value: unknown) => `event: ${name}\ndata: ${JSON.stringify(value)}\n\n`
const complete = (reply: string) => event('completed', turn(reply))
const delta = (text: string) => event('delta', { text })

function sse(text: string | Uint8Array, chunkSize = 23): Response {
  const bytes = typeof text === 'string' ? encoder.encode(text) : text
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        for (let offset = 0; offset < bytes.length; offset += chunkSize)
          controller.enqueue(bytes.slice(offset, offset + chunkSize))
        controller.close()
      },
    }),
    { headers: { 'content-type': 'text/event-stream;charset=UTF-8' } },
  )
}

function fixture(response: Response) {
  const fetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(json(csrf))
    .mockResolvedValueOnce(response)
  return { api: new ApiClient('buyer', 'test', fetcher), fetcher }
}

function send(api: ApiClient, onDelta = vi.fn(), signal?: AbortSignal) {
  return api.sendConversationMessage(
    '100',
    conversation.id,
    messageId,
    '订单发货了吗',
    undefined,
    signal,
    false,
    onDelta,
  )
}

afterEach(() => vi.restoreAllMocks())

describe('conversation response streaming', () => {
  it('delivers text before completion while keeping CSRF, identity, the original message id and one POST', async () => {
    let controller!: ReadableStreamDefaultController<Uint8Array>
    const { api, fetcher } = fixture(
      new Response(new ReadableStream({ start: (stream) => (controller = stream) }), {
        headers: { 'content-type': 'text/event-stream' },
      }),
    )
    const onDelta = vi.fn()
    let settled = false
    const pending = send(api, onDelta).then((value) => {
      settled = true
      return value
    })
    await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2))
    controller.enqueue(encoder.encode(delta('订单已')))
    await vi.waitFor(() => expect(onDelta).toHaveBeenCalledWith('订单已'))
    expect(settled).toBe(false)
    controller.enqueue(encoder.encode(delta('发货🙂')))
    controller.enqueue(encoder.encode(complete('订单已发货🙂')))
    controller.close()
    expect((await pending).messages[1]!.text).toBe('订单已发货🙂')
    expect(onDelta.mock.calls.map(([text]) => text)).toEqual(['订单已', '发货🙂'])
    expect(fetcher).toHaveBeenCalledTimes(2)
    const options = fetcher.mock.calls[1]![1]!
    expect(options).toMatchObject({
      method: 'POST',
      credentials: 'same-origin',
      redirect: 'error',
      cache: 'no-store',
      headers: {
        'X-CSRF-TOKEN': 'test-csrf',
        Authorization: expect.stringMatching(/^Basic /),
        Accept: 'text/event-stream, application/json;q=0.9',
      },
    })
    expect(JSON.parse(String(options.body))).toMatchObject({ clientMessageId: messageId })
    expect(fetcher.mock.calls[1]![0]).toBe(
      `/api/v1/shops/100/chat/conversations/${conversation.id}/messages`,
    )
  })

  it('decodes Chinese and emoji split at every UTF-8 byte, including literal newlines and escapes', async () => {
    const reply = '中文🙂\n"引号"\\路径'
    const { api } = fixture(sse(delta(reply) + complete(reply), 1))
    const onDelta = vi.fn()
    await expect(send(api, onDelta)).resolves.toMatchObject({ messages: [{}, { text: reply }] })
    expect(onDelta).toHaveBeenCalledExactlyOnceWith(reply)
  })

  it('handles CRLF split boundaries, comment heartbeats and multiline JSON data', async () => {
    const payload = JSON.stringify(turn('收到')).replace(',"messages"', ',\n"messages"')
    const completed = `event: completed\ndata: ${payload.split('\n').join('\ndata: ')}\n\n`
    const { api } = fixture(
      sse((': keepalive\n\n' + delta('收到') + completed).replaceAll('\n', '\r\n'), 1),
    )
    await expect(send(api)).resolves.toMatchObject({ messages: [{}, { text: '收到' }] })
  })

  it('accepts a JSON response from an older server without submitting again', async () => {
    const { api, fetcher } = fixture(json(turn('兼容回复')))
    const onDelta = vi.fn()
    await expect(send(api, onDelta)).resolves.toMatchObject({
      messages: [{}, { text: '兼容回复' }],
    })
    expect(onDelta).toHaveBeenCalledExactlyOnceWith('兼容回复')
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('bounds the same-response JSON compatibility body before a text callback', async () => {
    const { api, fetcher } = fixture(json(turn('a'.repeat(1024 * 1024))))
    const onDelta = vi.fn()
    await expect(send(api, onDelta)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    expect(onDelta).not.toHaveBeenCalled()
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('does not expose a JSON compatibility reply from another shop', async () => {
    const value = turn('wrong shop')
    value.conversation.shopId = 101
    const { api } = fixture(json(value))
    const onDelta = vi.fn()
    await expect(send(api, onDelta)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    expect(onDelta).not.toHaveBeenCalled()
  })

  it('cancels an unsupported successful response body and does not retry', async () => {
    const cancellation = vi.fn()
    const { api, fetcher } = fixture(
      new Response(new ReadableStream({ cancel: cancellation }), {
        headers: { 'content-type': 'text/html' },
      }),
    )
    await expect(send(api)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    expect(cancellation).toHaveBeenCalledOnce()
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('preserves JSON behavior when no streaming callback is supplied', async () => {
    const { api, fetcher } = fixture(json(turn('普通回复')))
    await api.sendConversationMessage('100', conversation.id, messageId, '订单发货了吗')
    expect(fetcher.mock.calls[1]![1]?.headers).toMatchObject({ Accept: 'application/json' })
  })

  it.each([401, 403, 409, 429, 503])(
    'keeps an HTTP %s error and never retries a POST',
    async (status) => {
      const { api, fetcher } = fixture(
        json({ code: 'MODEL_UNAVAILABLE', message: 'provider detail' }, status),
      )
      const onDelta = vi.fn()
      await expect(send(api, onDelta)).rejects.toMatchObject({ status, code: 'MODEL_UNAVAILABLE' })
      expect(onDelta).not.toHaveBeenCalled()
      expect(fetcher).toHaveBeenCalledTimes(2)
    },
  )

  it.each([
    ['missing completion', delta('收到')],
    ['truncated final frame', delta('收到') + complete('收到').slice(0, -1)],
    ['unknown event', event('reasoning', { text: 'not public' })],
    ['missing event name', 'data: {"text":"收到"}\n\n'],
    ['invalid JSON', 'event: delta\ndata: {\n\n'],
    ['invalid delta', event('delta', { text: 3 })],
    ['extra delta metadata', event('delta', { text: '收到', orderNo: 'SF10001' })],
    ['empty delta', delta('')],
    ['duplicate completion', delta('收到') + complete('收到') + complete('收到')],
    ['delta after completion', delta('收到') + complete('收到') + delta('！')],
    ['duplicate event field', 'event: delta\nevent: delta\ndata: {"text":"收到"}\n\n'],
  ])('rejects %s without replaying', async (_name, stream) => {
    const { api, fetcher } = fixture(sse(stream))
    await expect(send(api)).rejects.toMatchObject({ code: 'STREAM_INTERRUPTED' })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it.each([
    ['mismatched reply', { ...turn('另一个答案') }],
    ['another shop', { ...turn('收到'), conversation: { ...conversation, shopId: 101 } }],
    ['another conversation', { ...turn('收到'), conversation: { ...conversation, id: messageId } }],
    [
      'wrong user text',
      {
        ...turn('收到'),
        messages: [{ ...turn().messages[0], text: 'other' }, turn('收到').messages[1]],
      },
    ],
  ])('rejects a completed turn with %s before exposing metadata', async (_name, completed) => {
    const { api } = fixture(sse(delta('收到') + event('completed', completed)))
    await expect(send(api)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it.each([
    ['oversized delta', delta('a'.repeat(4097))],
    ['oversized reply', delta('a'.repeat(4096)).repeat(33)],
    ['excess events', delta('a').repeat(8193)],
    ['excess body bytes', ': '.padEnd(1024 * 1024 + 1, 'a')],
  ])('bounds %s', async (_name, stream) => {
    const { api } = fixture(sse(stream, 8192))
    await expect(send(api)).rejects.toMatchObject({ code: 'STREAM_INTERRUPTED' })
  })

  it('rejects invalid UTF-8 rather than displaying replacement characters', async () => {
    const { api } = fixture(sse(new Uint8Array([0xc3, 0x28])))
    await expect(send(api)).rejects.toMatchObject({ code: 'STREAM_INTERRUPTED' })
  })

  it.each(['abort', 'dispose', 'timeout'])('cancels an unfinished reader on %s', async (action) => {
    const deadline = new AbortController()
    const nativeTimeout = AbortSignal.timeout.bind(AbortSignal)
    vi.spyOn(AbortSignal, 'timeout').mockImplementation((milliseconds) =>
      milliseconds === 105_000 ? deadline.signal : nativeTimeout(milliseconds),
    )
    const cancellation = vi.fn()
    const { api, fetcher } = fixture(
      new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(encoder.encode(delta('一部分')))
          },
          cancel: cancellation,
        }),
        { headers: { 'content-type': 'text/event-stream' } },
      ),
    )
    const caller = new AbortController()
    const onDelta = vi.fn()
    const pending = send(api, onDelta, caller.signal)
    const rejected = expect(pending).rejects.toMatchObject(
      action === 'timeout' ? { code: 'TIMEOUT' } : { name: 'AbortError' },
    )
    await vi.waitFor(() => expect(onDelta).toHaveBeenCalledExactlyOnceWith('一部分'))
    if (action === 'abort') caller.abort()
    else if (action === 'dispose') api.dispose()
    else deadline.abort()
    await rejected
    expect(cancellation).toHaveBeenCalledOnce()
    expect(onDelta).toHaveBeenCalledOnce()
    expect(fetcher).toHaveBeenCalledTimes(2)
  })
})
