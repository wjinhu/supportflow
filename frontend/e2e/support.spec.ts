import { expect, test } from '@playwright/test'
import { connect, send } from './helpers'

const mockBase = 'http://127.0.0.1:18080'
test.beforeEach(async ({ request, page }) => {
  await request.post(`${mockBase}/__e2e/reset`)
  await page.goto('/?shopId=100')
})

test('an idle closure received by polling opens feedback and an explicit rating closes the prompt', async ({
  page,
}, testInfo) => {
  await connect(page)
  await page.getByTestId('request-human-support').click()
  await expect(page.getByTestId('buyer-support-status')).toContainText('正在等待店铺客服接待')
  let closedSession: Record<string, unknown> | null = null
  let feedback: { rating: number; comment: string; createdAt: string } | null = null
  let feedbackWrites = 0
  await page.route('**/chat/conversations/*/support', async (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    const response = await route.fetch()
    const body = await response.json()
    if (body.session) {
      closedSession = {
        ...body.session,
        status: 'CLOSED',
        closeReason: 'INACTIVITY',
        closedAt: new Date().toISOString(),
        lastMessageAt: new Date(Date.now() - 300_000).toISOString(),
        feedback,
      }
      await route.fulfill({ response, json: { ...body, session: closedSession } })
    } else await route.fulfill({ response })
  })
  await page.route('**/support/*/feedback', async (route) => {
    feedbackWrites++
    const body = route.request().postDataJSON()
    feedback = { rating: body.rating, comment: body.comment, createdAt: new Date().toISOString() }
    await route.fulfill({ json: { ...closedSession, feedback } })
  })
  const dialog = page.getByRole('dialog', { name: '客服评价', exact: true })
  await expect(dialog).toBeVisible({ timeout: 10_000 })
  await expect(dialog).toContainText('连续5分钟没有新消息')
  await expect(dialog).toContainText('等待体验')
  expect(feedbackWrites).toBe(0)
  await dialog.getByRole('radio', { name: '4星', exact: true }).check()
  await dialog.getByTestId('support-feedback-comment').fill('希望接待更及时')
  await page.screenshot({
    path: testInfo.outputPath('idle-human-support-feedback.png'),
    fullPage: true,
  })
  await dialog.getByTestId('submit-support-feedback').click()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByTestId('buyer-support-status')).not.toContainText(/已评价|人工服务已结束/)
  expect(feedback).toMatchObject({ rating: 4, comment: '希望接待更及时' })
  expect(feedbackWrites).toBe(1)
})

test('buyer explicitly requests support, staff claims and replies, close restores AI with both histories', async ({
  page,
  browser,
  request,
}) => {
  await connect(page)
  await send(page, '你好')
  await expect(page.getByTestId('chat-message')).toHaveCount(2)
  await page.getByTestId('request-human-support').click()
  await expect(page.getByTestId('buyer-support-status')).toContainText('正在等待店铺客服接待')
  await send(page, '我需要人工核实商品问题')
  await expect(page.getByTestId('chat-message')).toHaveCount(3)
  await expect(page.getByTestId('delete-conversation')).toBeDisabled()

  const context = await browser.newContext()
  const staff = await context.newPage()
  await staff.goto('/merchant')
  await staff.getByTestId('merchant-username').fill('chat-staff')
  await staff.getByTestId('merchant-password').fill('123456')
  await staff.getByTestId('merchant-connect').click()
  await expect(staff.getByTestId('human-support-panel')).toBeVisible()
  await expect(staff.getByTestId('merchant-tab-after-sales')).toHaveCount(0)
  await staff.locator('.human-session').first().click()
  await expect(staff.getByTestId('support-reply')).toHaveCount(0)
  await staff.getByTestId('support-claim').click()
  await expect(staff.getByTestId('support-message')).toContainText('我需要人工核实商品问题')
  await staff.getByTestId('support-reply').fill('<b>您好，我正在核实</b>')
  await staff.getByTestId('support-send').click()
  await expect(page.getByTestId('chat-message').last()).toContainText('店铺客服')
  await expect(page.getByTestId('chat-message').last()).toContainText('<b>您好，我正在核实</b>')
  await expect(page.getByTestId('chat-message').last().locator('b')).toHaveCount(0)
  await staff.getByTestId('support-close').click()
  await expect(page.getByTestId('request-human-support')).toBeEnabled()
  await send(page, '你好')
  await expect(page.getByTestId('chat-message')).toHaveCount(6)
  await expect(page.getByTestId('chat-message').last()).toContainText('店铺智能客服')
  const recorded = await (await request.get(`${mockBase}/__e2e/requests`)).json()
  expect(
    recorded.filter(
      (item: { path: string; method: string }) =>
        item.method === 'POST' && /\/chat\/conversations\/[^/]+\/messages$/.test(item.path),
    ),
  ).toHaveLength(2)
  await context.close()
})

test('an uncertain human send is retained without replay and explicit retry uses its original UUID', async ({
  page,
}) => {
  await connect(page)
  await page.getByTestId('request-human-support').click()
  await expect(page.getByTestId('close-human-support')).toBeVisible()
  const ids: string[] = []
  await page.route('**/support/*/messages', async (route) => {
    ids.push(route.request().postDataJSON().clientMessageId)
    if (ids.length === 1) await route.abort('failed')
    else await route.continue()
  })
  await send(page, '请核实这条消息')
  await expect(page.getByTestId('buyer-support-status')).toContainText('待核实消息：请核实这条消息')
  await page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      /\/support$/.test(new URL(response.url()).pathname) &&
      response.status() === 200,
    { timeout: 10_000 },
  )
  expect(ids).toHaveLength(1)
  await page.getByRole('button', { name: '重试这条消息', exact: true }).click()
  await expect(page.getByTestId('chat-message')).toContainText('请核实这条消息')
  expect(ids).toHaveLength(2)
  expect(ids[1]).toBe(ids[0])
  await page.getByTestId('close-human-support').click()
  await expect(page.getByTestId('request-human-support')).toBeEnabled()
})
