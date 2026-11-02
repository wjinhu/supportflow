/** Display received text over animation frames; never synthesize or execute reply content. */
export function createReplyDisplay(signal: AbortSignal, onText: (text: string) => void) {
  let received = ''
  let displayed = ''
  let frame: number | null = null
  let finishTimer: ReturnType<typeof setTimeout> | null = null
  let finished = false
  let cancelled = false
  let resolve: () => void = () => undefined
  const drained = new Promise<void>((done) => {
    resolve = done
  })

  function release() {
    if (finishTimer !== null) clearTimeout(finishTimer)
    finishTimer = null
    signal.removeEventListener('abort', cancel)
    document.removeEventListener('visibilitychange', visibilityChanged)
    resolve()
  }

  function cancel() {
    cancelled = true
    if (frame !== null) cancelAnimationFrame(frame)
    frame = null
    release()
  }

  function flush() {
    if (cancelled || signal.aborted) return cancel()
    if (frame !== null) cancelAnimationFrame(frame)
    frame = null
    displayed = received
    if (displayed) onText(displayed)
    release()
  }

  function visibilityChanged() {
    if (finished && document.hidden) flush()
  }

  function draw() {
    frame = null
    if (cancelled || signal.aborted) return cancel()
    const remaining = Array.from(received.slice(displayed.length))
    // Keep long, coalesced network chunks responsive without delaying the HTTP stream.
    displayed += remaining.slice(0, Math.max(2, Math.ceil(remaining.length / 30))).join('')
    onText(displayed)
    if (displayed !== received) frame = requestAnimationFrame(draw)
    else if (finished) release()
  }

  signal.addEventListener('abort', cancel, { once: true })
  document.addEventListener('visibilitychange', visibilityChanged)
  if (signal.aborted) cancel()
  return {
    push(text: string) {
      if (cancelled || finished || signal.aborted || !text) return
      received += text
      if (frame === null) frame = requestAnimationFrame(draw)
    },
    finish() {
      if (finished) return drained
      finished = true
      if (displayed === received || cancelled || signal.aborted) release()
      else if (document.hidden) flush()
      else finishTimer = setTimeout(flush, 1500)
      return drained
    },
    cancel,
  }
}
