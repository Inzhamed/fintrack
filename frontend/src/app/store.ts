import { configureStore } from '@reduxjs/toolkit'
import { useDispatch, useSelector } from 'react-redux'
import authReducer from '../features/auth/authSlice'
import uiReducer from './uiSlice'

/**
 * Redux holds only client state: who is signed in, and transient UI like toasts.
 * Everything that lives on the server - transactions, budgets, analytics - is owned by
 * TanStack Query instead, which already solves caching, refetching and invalidation.
 * Mirroring server data into Redux would mean maintaining that machinery twice.
 */
export const store = configureStore({
  reducer: {
    auth: authReducer,
    ui: uiReducer,
  },
})

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch

export const useAppDispatch = useDispatch.withTypes<AppDispatch>()
export const useAppSelector = useSelector.withTypes<RootState>()
