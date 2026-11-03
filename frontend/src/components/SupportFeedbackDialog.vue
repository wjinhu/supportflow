<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { SupportSession } from '../types'
const props = defineProps<{
  open: boolean
  session: SupportSession | null
  submitting: boolean
  error: string | null
}>()
const emit = defineEmits<{ close: []; submit: [rating: number, comment: string] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const rating = ref(0)
const comment = ref('')
watch(
  [() => props.open, () => props.session?.id],
  async ([open]) => {
    if (!open) {
      dialog.value?.close()
      rating.value = 0
      comment.value = ''
      return
    }
    rating.value = 0
    comment.value = ''
    await nextTick()
    if (props.open && !dialog.value?.open) dialog.value?.showModal()
  },
  { immediate: true },
)
function submit() {
  if (rating.value && !props.submitting) emit('submit', rating.value, comment.value.trim())
}
</script>

<template>
  <dialog
    ref="dialog"
    class="support-feedback-dialog"
    aria-labelledby="support-feedback-title"
    data-testid="support-feedback-dialog"
    @cancel.prevent="!submitting && emit('close')"
  >
    <form @submit.prevent="submit">
      <h2 id="support-feedback-title">客服评价</h2>
      <p>
        {{
          session?.closeReason === 'INACTIVITY'
            ? '连续5分钟没有新消息，本次人工服务已自动结束。'
            : '本次人工服务已结束。'
        }}
      </p>
      <p>
        {{ session?.assigned ? '这次服务是否让你满意？' : '本次尚未接通客服，可以评价等待体验。' }}
      </p>
      <fieldset :disabled="submitting">
        <legend>请选择评分</legend>
        <label
          v-for="score in 5"
          :key="score"
          class="feedback-star"
        >
          <input
            v-model="rating"
            type="radio"
            name="support-rating"
            :value="score"
            :aria-label="`${score}星`"
          />
          <span :class="{ selected: rating >= score }">★</span>
          <span>{{ score }}星</span>
        </label>
      </fieldset>
      <label for="support-feedback-comment">意见或建议（选填）</label>
      <textarea
        id="support-feedback-comment"
        v-model="comment"
        data-testid="support-feedback-comment"
        maxlength="500"
        rows="3"
        :disabled="submitting"
        placeholder="说说你的服务体验，最多500字"
      />
      <p
        v-if="error"
        role="alert"
      >
        {{ error }}
      </p>
      <div class="feedback-actions">
        <button
          type="button"
          class="button button-secondary"
          :disabled="submitting"
          @click="emit('close')"
        >
          暂不评价
        </button>
        <button
          type="submit"
          class="button button-primary"
          data-testid="submit-support-feedback"
          :disabled="!rating || submitting"
        >
          {{ submitting ? '正在提交…' : '提交评价' }}
        </button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.support-feedback-dialog {
  width: min(480px, calc(100vw - 32px));
  padding: 24px;
  border: 1px solid #e4ddef;
  border-radius: 16px;
  color: #292334;
}
.support-feedback-dialog::backdrop {
  background: #16112266;
}
h2 {
  margin: 0 0 16px;
  font-size: 20px;
}
p {
  font-size: 14px;
  line-height: 1.7;
}
fieldset {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  border: 0;
  margin: 16px 0;
  padding: 0;
}
legend {
  font-size: 14px;
  margin-bottom: 12px;
}
.feedback-star {
  display: grid;
  justify-items: center;
  gap: 4px;
  cursor: pointer;
  font-size: 12px;
}
.feedback-star input {
  accent-color: #8061bb;
}
.feedback-star span:first-of-type {
  font-size: 28px;
  color: #ccc6d5;
}
.feedback-star .selected:first-of-type {
  color: #b17b00;
}
textarea {
  width: 100%;
  box-sizing: border-box;
  margin-top: 8px;
  padding: 10px;
  border: 1px solid #d7cfe4;
  border-radius: 8px;
  resize: vertical;
  font: inherit;
}
.feedback-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 18px;
}
</style>
