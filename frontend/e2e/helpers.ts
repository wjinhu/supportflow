import { expect, type APIResponse, type Page, type Response } from '@playwright/test'
import type { ConversationMessages } from '../src/types'

type CapturedConversationBody = { text: string | null; failed: boolean }
type ConversationCaptureWindow = Window & {
  __supportflowE2EConversationBodies?: Map<string, Promise<CapturedConversationBody>>
}

/** CDP may discard SSE bodies; a test-only tee retains this exact fetch response, never a replay. */
async function installConversationCapture(page: Page) {
  await page.evaluate(() => {
    const scope = window as ConversationCaptureWindow
    if (scope.__supportflowE2EConversationBodies) return
    const bodies = new Map<string, Promise<CapturedConversationBody>>()
    scope.__supportflowE2EConversationBodies = bodies
    const originalFetch = window.fetch.bind(window)
    window.fetch = async (input, init) => {
      const response = await originalFetch(input, init)
      if (!(response.headers.get('content-type') ?? '').includes('text/event-stream'))
        return response
      const path = new URL(input instanceof Request ? input.url : String(input), location.href)
        .pathname
      if (
        init?.method?.toUpperCase() !== 'POST' ||
        !/^\/api\/v1\/shops\/[1-9][0-9]*\/chat\/conversations\/[a-f0-9-]+\/messages$/.test(path) ||
        typeof init.body !== 'string'
      )
        return response
      let clientMessageId: unknown
      try {
        clientMessageId = (JSON.parse(init.body) as { clientMessageId?: unknown }).clientMessageId
      } catch {
        return response
      }
      if (typeof clientMessageId !== 'string' || !/^[a-f0-9-]{36}$/.test(clientMessageId))
        return response
      while (bodies.size >= 20) bodies.delete(bodies.keys().next().value!)
      const captured = response
        .clone()
        .text()
        .then(
          (text): CapturedConversationBody =>
            text.length <= 1024 * 1024 ? { text, failed: false } : { text: null, failed: true },
          (): CapturedConversationBody => ({ text: null, failed: true }),
        )
      bodies.set(`${path}/${clientMessageId}`, captured)
      return response
    }
  })
}

async function conversationResponseText(response: Response | APIResponse): Promise<string> {
  if (!('frame' in response)) return response.text()
  const request = response.request()
  const body = request.postDataJSON() as { clientMessageId?: unknown }
  expect(typeof body.clientMessageId).toBe('string')
  const key = `${new URL(response.url()).pathname}/${String(body.clientMessageId)}`
  const frame = response.frame()
  await frame.waitForFunction(
    (key) => !!(window as ConversationCaptureWindow).__supportflowE2EConversationBodies?.has(key),
    key,
  )
  const captured = await frame.evaluate(async (key) => {
    const bodies = (window as ConversationCaptureWindow).__supportflowE2EConversationBodies!
    const pending = bodies.get(key)!
    try {
      return await pending
    } finally {
      bodies.delete(key)
    }
  }, key)
  expect(captured.failed, 'Browser-side capture must finish this exact SSE response').toBe(false)
  expect(captured.text).not.toBeNull()
  return captured.text!
}

/** Browser responses now carry a completed canonical turn after text-only SSE deltas. */
export async function readConversationTurn(
  response: Response | APIResponse,
): Promise<ConversationMessages> {
  if (!(response.headers()['content-type'] ?? '').includes('text/event-stream'))
    return (await response.json()) as ConversationMessages
  const frames = (await conversationResponseText(response))
    .split(/\r?\n\r?\n/)
    .filter((frame) => frame.trim())
  const events = frames.map((frame) => {
    const lines = frame.split(/\r?\n/)
    return {
      event: lines
        .find((line) => line.startsWith('event:'))
        ?.slice(6)
        .trim(),
      data: JSON.parse(
        lines
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trimStart())
          .join('\n'),
      ),
    }
  })
  const completed = events.filter((event) => event.event === 'completed')
  expect(completed).toHaveLength(1)
  expect(events.at(-1)?.event).toBe('completed')
  const turn = completed[0]!.data as ConversationMessages
  const assistant = turn.messages.find((message) => message.role === 'ASSISTANT')!
  expect(
    events
      .filter((event) => event.event === 'delta')
      .map((event) => event.data.text)
      .join(''),
  ).toBe(assistant.text)
  return turn
}

export async function connect(page: Page, username = 'buyer-demo', password = 'browser-test-only') {
  await installConversationCapture(page)
  await page.getByRole('button', { name: '连接账号', exact: true }).first().click()
  await page.getByTestId('username-input').fill(username)
  await page.getByTestId('password-input').fill(password)
  await page.getByTestId('connect-button').click()
  await expect(page.getByTestId('disconnect-button')).toBeVisible()
  // The account header appears before the initial orders have loaded. Wait until
  // the login modal actually closes before interacting with the conversation.
  await expect(page.getByTestId('connect-form')).not.toBeVisible()
}

export async function send(page: Page, message: string) {
  await showChat(page)
  await page.getByTestId('message-input').fill(message)
  await page.getByTestId('send-button').click()
}

export async function showOrders(page: Page) {
  const navigation = page.getByRole('button', { name: '查看订单面板', exact: true })
  await navigation.click()
  await expect(navigation).toHaveAttribute('aria-current', 'page')
  await expect(page.getByTestId('orders-panel')).toBeVisible()
}

export async function showChat(page: Page) {
  const navigation = page.getByRole('button', { name: '会话', exact: true })
  await navigation.click()
  await expect(navigation).toHaveAttribute('aria-current', 'page')
  await expect(page.getByTestId('message-input')).toBeVisible()
}

export async function switchShop(page: Page, shopId: string) {
  await showChat(page)
  await page.getByRole('button', { name: '切换店铺', exact: true }).click()
  await page.getByTestId('shop-input').fill(shopId)
  await page.getByTestId('shop-confirm').click()
  await expect(page).toHaveURL(new RegExp(`[?&]shopId=${shopId}(?:&|$)`))
  await expect(page.getByTestId('shop-confirm')).not.toBeVisible()
}

export async function clearShopConversation(page: Page) {
  await showChat(page)
  await expect(page.getByTestId('message-input')).toBeEnabled()
  if ((await page.getByTestId('chat-message').count()) === 0) return
  await page.getByTestId('delete-conversation').click()
  await expect(page.getByRole('dialog', { name: '删除该店铺对话？' })).toBeVisible()
  await page.getByTestId('confirm-delete-conversation').click()
  await expect(page.getByRole('dialog', { name: '删除该店铺对话？' })).not.toBeVisible()
  await expect(page.getByTestId('chat-message')).toHaveCount(0)
}
