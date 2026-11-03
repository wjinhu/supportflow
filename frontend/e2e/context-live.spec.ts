import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import type { ChatResponse, KnowledgeArticle } from '../src/types'
import { connect, send, switchShop, clearShopConversation, readConversationTurn } from './helpers'
import { sessionAccount } from './session'

const password = '123456'
const knowledgeBase = '/api/v1/merchant/shops/100/knowledge'

async function editor(): Promise<APIRequestContext> {
  return sessionAccount('frontend-editor', password)
}

async function api<T>(
  client: APIRequestContext,
  method: 'POST' | 'PUT',
  path: string,
  data: unknown,
): Promise<T> {
  const csrfResponse = await client.get('/api/v1/security/csrf')
  expect(csrfResponse.status()).toBe(200)
  const csrf = await csrfResponse.json()
  const response = await client.fetch(path, {
    method,
    data,
    headers: { [csrf.headerName]: csrf.token },
  })
  expect(response.status(), `${method} ${path}`).toBe(200)
  return response.json()
}

async function ask(page: Page, text: string) {
  const responded = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      /\/chat\/conversations\/[^/]+\/messages$/.test(new URL(response.url()).pathname) &&
      response.request().postDataJSON()?.message === text,
  )
  await send(page, text)
  const response = await responded
  expect(response.status()).toBe(200)
  const turn = (await readConversationTurn(response)) as {
    messages: { role: string; response: ChatResponse | null }[]
  }
  const answer = turn.messages.find((message) => message.role === 'ASSISTANT')!.response!
  await expect(page.getByTestId('chat-message').last()).toContainText(answer.reply)
  await expect(page.getByTestId('message-input')).toBeEnabled()
  return {
    answer,
    requestBody: response.request().postDataJSON() as { orderNo?: string; resetContext?: boolean },
  }
}

test('shipping complaints continue the current order and spending asks for real all-shop totals', async ({
  page,
}, testInfo) => {
  test.setTimeout(75_000)
  await page.goto('/?shopId=101')
  await connect(page, 'frontend-buyer', password)
  await clearShopConversation(page)
  const selected = await ask(page, '查订单 SF10001')
  expect(selected.answer.orders[0]?.status).toBe('PAID')

  await page.reload()
  await connect(page, 'frontend-buyer', password)
  const followup = await ask(page, '为什么还不发货')
  expect(followup.requestBody.orderNo == null).toBe(true)
  expect(followup.answer.orders[0]?.orderNo).toBe('SF10001')
  expect(followup.answer.reply).toContain('待发货')
  expect(followup.answer.reply).toContain('人工客服')
  expect(followup.answer.reply).not.toContain('暂未找到本店包含')

  const spending = await ask(page, '最近的消费总金额有多少')
  expect(spending.answer.type).toBe('TEXT')
  expect(spending.answer.orders).toEqual([])
  expect(spending.answer.context?.kind).toBe('NONE')
  expect(spending.answer.reply).toContain('全部店铺')
  expect(spending.answer.reply).toContain('近30天下单')
  expect(spending.answer.reply).toMatch(/金额合计 ¥\d+\.\d{2}/)
  expect(spending.answer.reply).not.toContain('收到你的问题')
  await page.screenshot({
    path: testInfo.outputPath('shipping-followup-and-spending.png'),
    fullPage: true,
  })
})

test('restored order context supports a fresh read and cancelling the current selection resets it', async ({
  page,
}, testInfo) => {
  test.setTimeout(75_000)
  await page.goto('/?shopId=100')
  await connect(page, 'frontend-buyer', password)
  await clearShopConversation(page)
  const first = await ask(page, '查订单 SF10001')
  expect(first.answer.context?.kind).toBe('ORDER')
  expect(first.answer.context?.orderNo).toBe('SF10001')

  await page.reload()
  await connect(page, 'frontend-buyer', password)
  await expect(page.getByTestId('selected-order')).toHaveCount(0)
  const followed = await ask(page, '这单现在是什么状态？')
  expect(followed.requestBody.orderNo == null).toBe(true)
  expect(followed.answer.orders[0]?.orderNo).toBe('SF10001')
  expect(followed.answer.orders[0]?.status).toBe('SHIPPED')
  expect(followed.answer.context?.kind).toBe('ORDER')
  await page.screenshot({
    path: testInfo.outputPath('order-follow-up-desktop.png'),
    fullPage: true,
  })

  await page.getByRole('button', { name: '取消选中订单', exact: true }).click()
  await expect(page.getByTestId('selected-order')).toHaveCount(0)
  const cleared = await ask(page, '这单现在是什么状态？')
  expect(cleared.requestBody.resetContext).toBe(true)
  expect(cleared.requestBody.orderNo == null).toBe(true)
  expect(cleared.answer.context?.kind).toBe('NONE')
  expect(cleared.answer.orders).toEqual([])
  expect(cleared.answer.reply).toMatch(/选择|明确/)

  await ask(page, '查订单 SF10001')
  await clearShopConversation(page)
  const newConversation = await ask(page, '这单现在是什么状态？')
  expect(newConversation.answer.context?.kind).toBe('NONE')
  expect(newConversation.answer.orders).toEqual([])
  await switchShop(page, '101')
  const otherShop = await ask(page, '这单现在是什么状态？')
  expect(otherShop.answer.context?.kind).toBe('NONE')
  expect(otherShop.answer.orders).toEqual([])
})

test('an owned earbud order supplies the product for usage follow-ups across clarification and reconnect', async ({
  page,
}, testInfo) => {
  test.setTimeout(90_000)
  const client = await editor()
  const token = `earbud-context-${Date.now()}`
  const content = `蓝牙耳机使用说明：先核对随附产品说明，再让耳机进入配对状态。安卓手机打开蓝牙设置，选择耳机名称并按屏幕提示连接；不同型号的按键和菜单可能不同，应以对应说明为准。测试核验标记 ${token}。`
  let published: KnowledgeArticle | null = null
  try {
    const article = await api<KnowledgeArticle>(client, 'POST', knowledgeBase, {
      title: `蓝牙耳机安卓手机连接说明-${token}`,
      keywords: ['蓝牙耳机', '安卓手机', token],
      content,
    })
    published = await api<KnowledgeArticle>(
      client,
      'POST',
      `${knowledgeBase}/${article.id}/publish`,
      {
        expectedVersion: article.version,
      },
    )
    await page.goto('/?shopId=100')
    await connect(page, 'frontend-buyer', password)
    await clearShopConversation(page)
    const selected = await ask(page, '查订单 SF10001')
    expect(selected.answer.orders[0]?.items?.[0]?.productName).toBe('蓝牙耳机')
    expect(selected.answer.context?.orderNo).toBe('SF10001')

    const clarified = await ask(page, '它呢')
    expect(clarified.answer.context?.kind).toBe('ORDER')
    expect(clarified.answer.context?.orderNo).toBe('SF10001')
    expect(clarified.answer.orders).toEqual([])

    const usage = await ask(page, '如何使用')
    expect(usage.answer.reply).toContain('蓝牙耳机')
    expect(usage.answer.reply).toContain(token)
    expect(usage.answer.reply).not.toContain('对不起，无法回复您的问题')
    expect(usage.answer.context?.kind).toBe('ORDER')
    expect(usage.answer.context?.orderNo).toBe('SF10001')
    expect(usage.answer.knowledge?.sources.map((source) => source.sourceId)).toContain(
      `kb-${article.id}`,
    )
    await page.reload()
    await connect(page, 'frontend-buyer', password)
    await expect(page.getByTestId('selected-order')).toHaveCount(0)
    const reconnected = await ask(page, '怎么连接手机')
    expect(reconnected.requestBody.orderNo == null).toBe(true)
    expect(reconnected.answer.reply).toContain(token)
    expect(reconnected.answer.reply).toContain('安卓手机')
    expect(reconnected.answer.context?.orderNo).toBe('SF10001')
    const platform = await ask(page, '安卓手机')
    expect(platform.answer.reply).toContain(token)
    expect(platform.answer.context?.orderNo).toBe('SF10001')
    await page.screenshot({
      path: testInfo.outputPath('earbud-usage-follow-up.png'),
      fullPage: true,
    })

    await switchShop(page, '101')
    const otherShop = await ask(page, '如何使用')
    expect(otherShop.answer.context?.kind).toBe('NONE')
    expect(otherShop.answer.knowledge?.sources ?? []).toEqual([])
    expect(otherShop.answer.reply).toMatch(/选择|商品|订单/)
    expect(otherShop.answer.reply).not.toContain(token)
    expect(otherShop.answer.reply).not.toContain('对不起，无法回复您的问题')
  } finally {
    if (published)
      await api(client, 'POST', `${knowledgeBase}/${published.id}/offline`, {
        expectedVersion: published.version,
      })
    await client.dispose()
  }
})

test('knowledge follow-ups reload the current publication and never resurrect an offline source from history', async ({
  page,
}, testInfo) => {
  test.setTimeout(75_000)
  const client = await editor()
  const token = `context-card-${Date.now()}`
  const original = `仅供本地演示。贺卡填写前请核对祝福语，资料标记 V1-${token}。`
  const revised = `仅供本地演示。贺卡内容请先与客服确认，资料标记 V2-${token}。`
  try {
    const article = await api<KnowledgeArticle>(client, 'POST', knowledgeBase, {
      title: `演示贺卡主题-${token}`,
      keywords: [token],
      content: original,
    })
    const published = await api<KnowledgeArticle>(
      client,
      'POST',
      `${knowledgeBase}/${article.id}/publish`,
      { expectedVersion: article.version },
    )
    await page.goto('/?shopId=100')
    await connect(page, 'frontend-buyer', password)
    await clearShopConversation(page)
    const first = await ask(page, `知识库：${token}`)
    expect(first.answer.context?.kind).toBe('KNOWLEDGE')
    expect(first.answer.context?.sourceIds).toEqual([`kb-${article.id}`])

    await page.reload()
    await connect(page, 'frontend-buyer', password)
    await expect(page.getByTestId('selected-order')).toHaveCount(0)
    const saved = await api<KnowledgeArticle>(client, 'PUT', `${knowledgeBase}/${article.id}`, {
      title: article.title,
      keywords: article.keywords,
      content: revised,
      expectedVersion: published.version,
    })
    const republished = await api<KnowledgeArticle>(
      client,
      'POST',
      `${knowledgeBase}/${article.id}/publish`,
      { expectedVersion: saved.version },
    )
    const current = await ask(page, '那怎么填写？')
    expect(current.requestBody.orderNo == null).toBe(true)
    expect(current.answer.knowledge?.sources).toContainEqual({
      sourceId: `kb-${article.id}`,
      version: `v${republished.publishedRevision}`,
      title: article.title,
      content: revised,
    })
    expect(first.answer.knowledge?.sources[0]?.content).toBe(original)
    await expect(
      page
        .getByTestId('chat-message')
        .filter({ hasText: `V1-${token}` })
        .first(),
    ).toContainText(original)
    await page.setViewportSize({ width: 390, height: 844 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    )
    await page.getByTestId('chat-message').last().scrollIntoViewIfNeeded()
    await page.screenshot({
      path: testInfo.outputPath('knowledge-follow-up-mobile.png'),
      fullPage: true,
    })

    await api(client, 'POST', `${knowledgeBase}/${article.id}/offline`, {
      expectedVersion: republished.version,
    })
    const offline = await ask(page, '那怎么填写？')
    expect(offline.answer.knowledge?.status).toBe('NO_MATCH')
    expect(offline.answer.knowledge?.sources).toEqual([])
    expect(offline.answer.context?.kind).toBe('NONE')
    await expect(page.getByTestId('chat-message').last()).not.toContainText(original)
    await expect(page.getByTestId('chat-message').last()).not.toContainText(revised)
  } finally {
    await client.dispose()
  }
})

test('an ambiguous knowledge reference asks for a topic and a write request cannot borrow the remembered order', async ({
  page,
}) => {
  test.setTimeout(75_000)
  const client = await editor()
  const token = `ambiguous-context-${Date.now()}`
  try {
    for (const topic of ['包装', '材质']) {
      const article = await api<KnowledgeArticle>(client, 'POST', knowledgeBase, {
        title: `${topic}-${token}`,
        keywords: [token],
        content: `演示${topic}资料-${token}`,
      })
      await api(client, 'POST', `${knowledgeBase}/${article.id}/publish`, {
        expectedVersion: article.version,
      })
    }
    await page.goto('/?shopId=100')
    await connect(page, 'frontend-buyer', password)
    await clearShopConversation(page)
    const multiple = await ask(page, `知识库：${token}`)
    expect(multiple.answer.context?.sourceIds).toHaveLength(2)
    const clarified = await ask(page, '那怎么使用？')
    expect(clarified.answer.reply).toMatch(/多份|明确/)
    expect(clarified.answer.orders).toEqual([])
    expect(clarified.answer.context).toEqual(multiple.answer.context)

    await ask(page, '查订单 SF10001')
    await page.reload()
    await connect(page, 'frontend-buyer', password)
    await expect(page.getByTestId('selected-order')).toHaveCount(0)
    const application = await ask(page, '我想给这单退款')
    expect(application.requestBody.orderNo == null).toBe(true)
    expect(application.answer.type).toBe('AFTER_SALE_FORM')
    expect(application.answer.orders).toEqual([])
    expect(application.answer.context?.kind).toBe('NONE')
  } finally {
    await client.dispose()
  }
})

test('a named gift-box price overrides a stale selection while an unspecified price still reads the owned order', async ({
  page,
}) => {
  test.setTimeout(75_000)
  const client = await editor()
  const token = `gift-price-context-${Date.now()}`
  const content = `演示礼盒公开价格为每份十二元，具体包装请先向客服确认。资料标记 ${token}。`
  try {
    const article = await api<KnowledgeArticle>(client, 'POST', knowledgeBase, {
      title: `礼盒公开价格-${token}`,
      keywords: [token],
      content,
    })
    await api(client, 'POST', `${knowledgeBase}/${article.id}/publish`, {
      expectedVersion: article.version,
    })
    await page.goto('/?shopId=100')
    await connect(page, 'frontend-buyer', password)
    await clearShopConversation(page)
    await ask(page, '查订单 SF10001')
    const gift = await ask(page, `这个礼盒 ${token} 多少钱？`)
    expect(gift.requestBody.orderNo).toBe('SF10001')
    expect(gift.answer.knowledge?.sources[0]?.sourceId).toBe(`kb-${article.id}`)
    expect(gift.answer.reply).toContain(content)
    expect(gift.answer.orders).toEqual([])
    expect(gift.answer.context?.kind).toBe('KNOWLEDGE')
    await expect(page.getByTestId('selected-order')).toHaveCount(0)

    await ask(page, '查订单 SF10001')
    const order = await ask(page, '这个多少钱？')
    expect(order.answer.orders[0]?.orderNo).toBe('SF10001')
    expect(order.answer.orders[0]?.totalAmount).toBe(199.9)
    expect(order.answer.context?.kind).toBe('ORDER')
    expect(order.answer.knowledge).toBeNull()
    const transaction = await ask(page, '本单礼盒多少钱？')
    expect(transaction.answer.orders[0]?.orderNo).toBe('SF10001')
    expect(transaction.answer.orders[0]?.totalAmount).toBe(199.9)
    expect(transaction.answer.knowledge).toBeNull()
  } finally {
    await client.dispose()
  }
})
