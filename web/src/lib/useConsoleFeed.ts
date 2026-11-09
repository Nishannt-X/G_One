import { useEffect, useReducer, useState } from 'react'
import { backoffDelay } from './backoff'
import { feedReducer, initialFeed, normalizeEvent } from './callState'

export type FeedStatus = 'connecting' | 'connected' | 'offline'

const RETRY = { baseMs: 1000, maxMs: 5000, jitter: 0.2 }

function feedUrl(): string {
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/console`
}

/**
 * Follows /ws/console and keeps reconnecting. On every (re)connect the server sends the running calls again from
 * their start, so the feed's calls are cleared first. Dev mock calls are kept.
 */
export function useConsoleFeed() {
  const [feed, dispatch] = useReducer(feedReducer, initialFeed)
  const [status, setStatus] = useState<FeedStatus>('connecting')

  useEffect(() => {
    let socket: WebSocket | null = null
    let timer: ReturnType<typeof setTimeout> | undefined
    let stopped = false
    let attempt = 0

    const connect = () => {
      const ws = new WebSocket(feedUrl())
      socket = ws
      ws.onopen = () => {
        attempt = 0
        dispatch({ type: 'reset' })
        setStatus('connected')
      }
      ws.onmessage = (message) => {
        try {
          const event = normalizeEvent(JSON.parse(String(message.data)))
          if (event) dispatch({ type: 'event', event })
        } catch {
          // A message that is not JSON is not ours: ignore it.
        }
      }
      ws.onclose = () => {
        if (stopped) return
        setStatus('offline')
        attempt += 1
        timer = setTimeout(connect, backoffDelay(attempt, RETRY))
      }
    }
    connect()

    return () => {
      stopped = true
      clearTimeout(timer)
      if (socket) {
        socket.onclose = null
        socket.close()
      }
    }
  }, [])

  return { feed, status, dispatch }
}
