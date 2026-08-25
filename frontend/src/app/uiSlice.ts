import { createSlice, nanoid, type PayloadAction } from '@reduxjs/toolkit'

export interface Toast {
  id: string
  message: string
  tone: 'success' | 'error' | 'info'
}

interface UiState {
  toasts: Toast[]
}

const initialState: UiState = { toasts: [] }

const uiSlice = createSlice({
  name: 'ui',
  initialState,
  reducers: {
    /** Non-blocking feedback. Prepared so the caller supplies just a message and a tone. */
    toast: {
      reducer(state, action: PayloadAction<Toast>) {
        state.toasts.push(action.payload)
      },
      prepare(message: string, tone: Toast['tone'] = 'success') {
        return { payload: { id: nanoid(), message, tone } }
      },
    },
    dismissToast(state, action: PayloadAction<string>) {
      state.toasts = state.toasts.filter((toast) => toast.id !== action.payload)
    },
  },
})

export const { toast, dismissToast } = uiSlice.actions
export default uiSlice.reducer
