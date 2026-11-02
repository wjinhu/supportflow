/** Bounded SSE transport for an already validated, persisted conversation turn. */
export class ConversationStreamError extends Error {
  constructor() {
    super('Invalid or interrupted conversation stream')
    this.name = 'ConversationStreamError'
  }
}

const MAX_BYTES = 1024 * 1024
const MAX_EVENTS = 8192
const MAX_REPLY_LENGTH = 131_072
const MAX_DELTA_LENGTH = 4096

/** The same-response JSON compatibility path has the same transport size boundary. */
export async function readConversationJson(
  response: Response,
  signal: AbortSignal,
): Promise<unknown> {
  if (!response.body) throw new ConversationStreamError()
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let bytes = 0
  let text = ''
  let ended = false
  const abort = () => {
    void reader.cancel().catch(() => undefined)
  }
  signal.addEventListener('abort', abort, { once: true })
  try {
    if (signal.aborted) throw new ConversationStreamError()
    while (true) {
      const chunk = await reader.read()
      if (signal.aborted) throw new ConversationStreamError()
      if (chunk.done) {
        text += decoder.decode()
        const value: unknown = JSON.parse(text)
        ended = true
        return value
      }
      bytes += chunk.value.byteLength
      if (bytes > MAX_BYTES) throw new ConversationStreamError()
      text += decoder.decode(chunk.value, { stream: true })
    }
  } finally {
    signal.removeEventListener('abort', abort)
    if (!ended) await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}

export async function readConversationStream(
  response: Response,
  signal: AbortSignal,
  onDelta: (text: string) => void,
): Promise<{ value: unknown; reply: string }> {
  if (!response.body) throw new ConversationStreamError()
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let bytes = 0
  let events = 0
  let buffer = ''
  let eventName = ''
  let data: string[] = []
  let frameLength = 0
  let reply = ''
  let completed = false
  let ended = false
  let value: unknown
  const cancelled = () => new DOMException('Request cancelled', 'AbortError')
  const abort = () => {
    void reader.cancel().catch(() => undefined)
  }
  signal.addEventListener('abort', abort, { once: true })

  function dispatch() {
    if (!eventName && data.length === 0) return
    if (completed || ++events > MAX_EVENTS || data.length === 0) throw new ConversationStreamError()
    let payload: unknown
    try {
      payload = JSON.parse(data.join('\n'))
    } catch {
      throw new ConversationStreamError()
    }
    if (eventName === 'delta') {
      if (
        typeof payload !== 'object' ||
        payload === null ||
        Array.isArray(payload) ||
        Object.keys(payload).length !== 1 ||
        !('text' in payload) ||
        typeof payload.text !== 'string' ||
        payload.text.length === 0 ||
        payload.text.length > MAX_DELTA_LENGTH ||
        reply.length + payload.text.length > MAX_REPLY_LENGTH
      )
        throw new ConversationStreamError()
      reply += payload.text
      if (signal.aborted) throw cancelled()
      onDelta(payload.text)
    } else if (eventName === 'completed') {
      completed = true
      value = payload
    } else {
      throw new ConversationStreamError()
    }
    eventName = ''
    data = []
    frameLength = 0
  }

  function line(text: string) {
    if (text === '') {
      dispatch()
      return
    }
    if (text.startsWith(':')) return
    frameLength += text.length
    if (frameLength > MAX_BYTES) throw new ConversationStreamError()
    const colon = text.indexOf(':')
    const field = colon === -1 ? text : text.slice(0, colon)
    let fieldValue = colon === -1 ? '' : text.slice(colon + 1)
    if (fieldValue.startsWith(' ')) fieldValue = fieldValue.slice(1)
    if (field === 'event') {
      if (eventName) throw new ConversationStreamError()
      eventName = fieldValue
    } else if (field === 'data') {
      data.push(fieldValue)
    } else {
      throw new ConversationStreamError()
    }
  }

  function drain(final = false) {
    while (true) {
      const index = buffer.search(/[\r\n]/)
      if (index === -1 || (!final && buffer[index] === '\r' && index === buffer.length - 1)) break
      const width = buffer[index] === '\r' && buffer[index + 1] === '\n' ? 2 : 1
      line(buffer.slice(0, index))
      buffer = buffer.slice(index + width)
    }
    if (buffer.length > MAX_BYTES) throw new ConversationStreamError()
  }

  try {
    if (signal.aborted) throw cancelled()
    while (true) {
      const chunk = await reader.read()
      if (signal.aborted) throw cancelled()
      if (chunk.done) {
        buffer += decoder.decode()
        drain(true)
        // An unfinished frame is not a committed turn, even when its JSON happens to parse.
        if (buffer || eventName || data.length || !completed) throw new ConversationStreamError()
        ended = true
        return { value, reply }
      }
      bytes += chunk.value.byteLength
      if (bytes > MAX_BYTES) throw new ConversationStreamError()
      buffer += decoder.decode(chunk.value, { stream: true })
      drain()
    }
  } catch (failure) {
    if (signal.aborted) throw cancelled()
    if (failure instanceof ConversationStreamError) throw failure
    throw new ConversationStreamError()
  } finally {
    signal.removeEventListener('abort', abort)
    if (!ended) await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
