import { Client, type IMessage } from '@stomp/stompjs'
import { getAccessToken } from './api'

/**
 * The live notification channel.
 *
 * A thin wrapper over STOMP that owns one connection for the whole app. Components subscribe
 * to the parsed stream rather than each opening their own socket - a per-component
 * connection would mean several sockets per tab and duplicate deliveries of every message.
 */

export interface BudgetAlert {
  budgetItemId: string
  categoryId: string
  categoryName: string
  categoryColor?: string
  spent: number
  limitAmount: number
  percentUsed: number
  status: 'ON_TRACK' | 'WARNING' | 'EXCEEDED'
}

export interface Notification {
  type: 'BUDGET_THRESHOLD'
  title: string
  message: string
  data: BudgetAlert
  timestamp: string
}

type Listener = (notification: Notification) => void

const listeners = new Set<Listener>()
let client: Client | null = null

/** WebSocket URL for the current origin. Vite proxies /ws in dev; nginx does in production. */
function socketUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws`
}

/**
 * Opens the connection, authenticating on the CONNECT frame.
 *
 * Safe to call repeatedly: an already-active client is left alone rather than replaced,
 * so React StrictMode's double-invoked effects do not open two sockets.
 */
export function connectNotifications(): void {
  if (client?.active) return

  const token = getAccessToken()
  if (!token) return

  client = new Client({
    brokerURL: socketUrl(),
    // The token cannot ride on the handshake - browsers do not allow custom headers there -
    // so it goes on the STOMP CONNECT frame, which the server's interceptor reads.
    connectHeaders: { Authorization: `Bearer ${token}` },

    // Backoff rather than a tight retry loop, so a server restart does not get hammered by
    // every open tab at once.
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,

    onConnect: () => {
      client?.subscribe('/user/queue/notifications', (message: IMessage) => {
        try {
          const notification: Notification = JSON.parse(message.body)
          listeners.forEach((listener) => listener(notification))
        } catch {
          // A malformed frame must not tear down the subscription; the next one may be fine.
        }
      })
    },

    onStompError: (frame) => {
      // The server refuses CONNECT when the token is missing, expired or invalid. Retrying
      // with the same token would loop forever, so the client stops and waits for the next
      // explicit connect - which happens after a refresh has produced a new token.
      if (frame.headers.message?.includes('token')) {
        void disconnectNotifications()
      }
    },
  })

  client.activate()
}

/** Closes the connection. Called on sign-out, so the next user does not inherit it. */
export async function disconnectNotifications(): Promise<void> {
  if (!client) return
  const active = client
  client = null
  await active.deactivate()
}

/**
 * Registers a listener.
 *
 * @returns an unsubscribe function, so a component can clean up on unmount without the
 *          listener set growing every time it remounts
 */
export function onNotification(listener: Listener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
