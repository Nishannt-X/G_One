import { useCallback, useEffect, useRef, useState } from 'react'

const NEAR_BOTTOM_PX = 48

/**
 * For a scrolling list that grows at the bottom: follows new entries while the viewer is at the bottom, and leaves
 * them alone once they scroll up to read. `away` says when to offer a way back.
 */
export function useStickToBottom(changeKey: number) {
  const ref = useRef<HTMLDivElement>(null)
  const pinned = useRef(true)
  const [away, setAway] = useState(false)

  const onScroll = useCallback(() => {
    const el = ref.current
    if (!el) return
    pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_PX
    setAway(!pinned.current)
  }, [])

  const jump = useCallback(() => {
    const el = ref.current
    if (!el) return
    pinned.current = true
    setAway(false)
    el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
  }, [])

  useEffect(() => {
    const el = ref.current
    if (el && pinned.current) el.scrollTop = el.scrollHeight
  }, [changeKey])

  return { ref, onScroll, away, jump }
}
