import { expect, test } from '@playwright/test'
import { connect, send, showChat, showOrders, switchShop, readConversationTurn } from './helpers'

const password = '123456'

test('live typed handoff prompt, chat-only staff claim/reply, close and AI restoration', async ({
  page,
  browser,
}, testInfo) => {
  let aiPosts = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      /\/chat\/(?:messages|conversations\/[^/]+\/messages)$/.test(new URL(request.url()).pathname)
    )
      aiPosts++
  })
  await page.goto('/?shopId=100')
  await connect(page, 'frontend-buyer', password)
  const activeClose = page.getByTestId('close-human-support')
  if (await activeClose.isVisible()) {
    await activeClose.click()
    await expect(page.getByTestId('request-human-support')).toBeVisible()
  }
  let supportRequests = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      /\/conversations\/[^/]+\/support$/.test(new URL(request.url()).pathname)
    )
      supportRequests++
  })
  const handoffResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      /\/conversations\/[^/]+\/messages$/.test(new URL(response.url()).pathname),
  )
  await send(page, '转人工')
  const turn = await readConversationTurn(await handoffResponse)
  expect(turn.messages.find((message) => message.role === 'ASSISTANT')?.response?.type).toBe(
    'HUMAN_SUPPORT',
  )
  const prompt = page.getByTestId('chat-message').last()
  await expect(prompt.getByTestId('contact-human-support')).toBeVisible()
  await expect(prompt).not.toContainText('无法回复')
  expect(supportRequests).toBe(0)
  await page.screenshot({
    path: testInfo.outputPath('typed-human-support-prompt.png'),
    fullPage: true,
  })
  await page.reload()
  await connect(page, 'frontend-buyer', password)
  const restoredPrompt = page
    .getByTestId('chat-message')
    .filter({ has: page.getByTestId('contact-human-support') })
    .last()
  await expect(
    restoredPrompt.getByRole('button', { name: '联系人工客服', exact: true }),
  ).toBeVisible()
  await restoredPrompt.getByTestId('contact-human-support').click()
  await expect(page.getByTestId('contact-human-support')).toHaveCount(0)
  await expect(page.getByTestId('buyer-support-status')).toContainText('正在等待店铺客服接待')
  const text = `人工核实 ${Date.now()}`
  await send(page, text)
  await expect(page.getByTestId('chat-message').last()).toContainText(text)
  await expect(page.getByTestId('delete-conversation')).toBeDisabled()
  await page.reload()
  await connect(page, 'frontend-buyer', password)
  await expect(page.getByTestId('buyer-support-status')).toContainText('正在等待店铺客服接待')
  await showOrders(page)
  const cards = page.getByTestId('orders-panel').getByTestId('order-card')
  await expect(cards).toHaveCount(20)
  await page
    .getByTestId('orders-panel')
    .locator('[data-testid="order-card"][data-shop-id="100"][data-order-no="SF10001"]')
    .getByRole('button', { name: /^咨询此订单/ })
    .click()
  await expect(page.getByTestId('conversation-panel')).toBeVisible()
  await expect(page.getByTestId('selected-order')).toContainText('SF10001')
  await expect(page.locator('.order-detail')).not.toBeVisible()
  await showChat(page)
  await page.getByRole('button', { name: '申请退款', exact: true }).click()
  const refund = page.getByRole('dialog', { name: '申请退款', exact: true })
  await expect(refund).toBeVisible()
  await expect(refund.locator('#refund-order option')).toHaveCount(21)
  await refund.getByRole('button', { name: '关闭', exact: true }).click()
  expect(aiPosts).toBe(1)
  expect(supportRequests).toBe(1)
  const context = await browser.newContext()
  const staff = await context.newPage()
  await staff.goto('/merchant')
  await staff.getByTestId('merchant-username').fill('frontend-chat-staff')
  await staff.getByTestId('merchant-password').fill(password)
  await staff.getByTestId('merchant-connect').click()
  await expect(staff.getByTestId('human-support-panel')).toBeVisible()
  await expect(staff.getByTestId('merchant-tab-after-sales')).toHaveCount(0)
  await expect(staff.getByTestId('merchant-tab-knowledge')).toHaveCount(0)
  await staff.locator('.human-session').first().click()
  await staff.getByTestId('support-claim').click()
  await expect(staff.getByTestId('support-message').last()).toContainText(text)
  const reply = `已收到，正在核实 ${Date.now()}`
  await staff.getByTestId('support-reply').fill(reply)
  await staff.getByTestId('support-send').click()
  await expect(page.getByTestId('chat-message').last()).toContainText(reply)
  await expect(page.getByTestId('chat-message').last()).toContainText('店铺客服')
  await staff.getByTestId('support-close').click()
  await expect(page.getByTestId('request-human-support')).toBeEnabled()
  const feedback = page.getByRole('dialog', { name: '客服评价', exact: true })
  await expect(feedback).toBeVisible()
  await feedback.getByRole('radio', { name: '5星', exact: true }).check()
  await feedback.getByTestId('support-feedback-comment').fill('客服解释清楚，处理及时')
  const savedFeedback = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      /\/support\/[^/]+\/feedback$/.test(new URL(response.url()).pathname),
  )
  await feedback.getByTestId('submit-support-feedback').click()
  await expect(feedback).not.toBeVisible()
  expect((await (await savedFeedback).json()).feedback).toMatchObject({
    rating: 5,
    comment: '客服解释清楚，处理及时',
  })
  await expect(page.getByTestId('buyer-support-status')).not.toContainText(/已评价|人工服务已结束/)
  await send(page, '我的订单')
  await expect(page.getByTestId('chat-message').last()).toContainText('店铺智能客服')
  await expect(page.getByTestId('chat-message').filter({ hasText: text })).toHaveCount(1)
  await expect(page.getByTestId('chat-message').filter({ hasText: reply })).toHaveCount(1)
  expect(aiPosts).toBe(2)
  const restoredSupport = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      /\/support$/.test(new URL(response.url()).pathname) &&
      response.status() === 200,
  )
  await page.reload()
  await connect(page, 'frontend-buyer', password)
  expect((await (await restoredSupport).json()).session.feedback).toMatchObject({
    rating: 5,
    comment: '客服解释清楚，处理及时',
  })
  await expect(page.getByTestId('buyer-support-status')).not.toContainText(/已评价|人工服务已结束/)
  await expect(page.getByRole('dialog', { name: '客服评价', exact: true })).not.toBeVisible()
  await expect(page.getByTestId('chat-message').filter({ hasText: text })).toHaveCount(1)
  await expect(page.getByTestId('chat-message').filter({ hasText: reply })).toHaveCount(1)
  await switchShop(page, '101')
  await expect(page.getByTestId('chat-message').filter({ hasText: text })).toHaveCount(0)
  await switchShop(page, '100')
  await expect(page.getByTestId('chat-message').filter({ hasText: text })).toHaveCount(1)
  await expect(page.getByTestId('chat-message').filter({ hasText: reply })).toHaveCount(1)
  await context.close()
})
