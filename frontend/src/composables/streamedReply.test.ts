import { afterEach, describe, expect, it, vi } from 'vitest'
import { createReplyDisplay } from './streamedReply'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

function frames() {
  let id = 0
  const queue = new Map<number, FrameRequestCallback>()
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    queue.set(++id, callback)
    return id
  })
  vi.stubGlobal('cancelAnimationFrame', (key: number) => queue.delete(key))
  return {
    tick() {
      for (const [key, callback] of [...queue]) {
        queue.delete(key)
        callback(0)
      }
    },
    size: () => queue.size,
  }
}

describe('received reply display', () => {
  it('reveals received Unicode text in order and waits for the final display frame', async () => {
    const clock = frames()
    const values: string[] = []
    const display = createReplyDisplay(new AbortController().signal, (text) => values.push(text))
    display.push('你😀好，订单已发货。')
    let complete = false
    const finished = display.finish().then(() => {
      complete = true
    })
    expect(values).toEqual([])
    clock.tick()
    expect(values[0]).toBe('你😀')
    expect(complete).toBe(false)
    while (clock.size()) clock.tick()
    await finished
    expect(values.at(-1)).toBe('你😀好，订单已发货。')
    expect(complete).toBe(true)
  })

  it('cancels scheduled display and resolves completion when its scope is aborted', async () => {
    const clock = frames()
    const controller = new AbortController()
    const onText = vi.fn()
    const display = createReplyDisplay(controller.signal, onText)
    display.push('不应出现在新会话')
    const finished = display.finish()
    controller.abort()
    clock.tick()
    await finished
    expect(clock.size()).toBe(0)
    expect(onText).not.toHaveBeenCalled()
  })

  it('finishes a validated reply within the display deadline even when animation frames stop', async () => {
    vi.useFakeTimers()
    const clock = frames()
    const onText = vi.fn()
    const display = createReplyDisplay(new AbortController().signal, onText)
    display.push('已经收到的完整回答')
    const finished = display.finish()
    await vi.advanceTimersByTimeAsync(1500)
    await finished
    expect(onText).toHaveBeenLastCalledWith('已经收到的完整回答')
    expect(clock.size()).toBe(0)
    expect(vi.getTimerCount()).toBe(0)
  })
})
