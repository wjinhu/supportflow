import { mount } from '@vue/test-utils'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import SupportFeedbackDialog from './SupportFeedbackDialog.vue'
import type { SupportSession } from '../types'

const session: SupportSession = {
  id: '11111111-1111-4111-8111-111111111111',
  status: 'CLOSED',
  assigned: true,
  assignedToMe: false,
  createdAt: '2026-11-03T00:00:00Z',
  updatedAt: '2026-11-03T00:05:00Z',
  closedAt: '2026-11-03T00:05:00Z',
  closeReason: 'INACTIVITY',
  feedback: null,
}
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
    this.setAttribute('open', '')
  })
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
    this.removeAttribute('open')
  })
})
describe('human support feedback dialog', () => {
  it('explains timeout and requires stars while allowing an optional comment', async () => {
    const wrapper = mount(SupportFeedbackDialog, {
      props: { open: true, session, submitting: false, error: null },
    })
    await nextTick()
    expect(wrapper.text()).toContain('连续5分钟没有新消息')
    expect(wrapper.get('[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[aria-label="5星"]').setValue(true)
    await wrapper.get('textarea').setValue(' 服务很好 ')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')).toEqual([[5, '服务很好']])
    wrapper.unmount()
  })
  it('allows skipping and distinguishes an unassigned waiting experience', async () => {
    const wrapper = mount(SupportFeedbackDialog, {
      props: {
        open: true,
        session: { ...session, assigned: false },
        submitting: false,
        error: null,
      },
    })
    expect(wrapper.text()).toContain('等待体验')
    await wrapper.get('[type="button"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
})
