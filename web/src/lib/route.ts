import { useSyncExternalStore } from 'react'

export type View = 'home' | 'demo' | 'call' | 'farmers' | 'live' | 'memory' | 'buyers' | 'simulator' | 'join' | 'sample-receipt'

export interface Route {
  view: View
  /** The path part after the view, such as the caller key in #/farmers/<key>, or the section in #/how. */
  param: string | null
  query: URLSearchParams
}

const VIEWS: readonly View[] = ['demo', 'call', 'farmers', 'live', 'memory', 'buyers', 'simulator', 'join', 'sample-receipt']

/** The views that sit under the Advanced menu. */
export const ADVANCED_VIEWS: readonly View[] = ['live', 'simulator', 'buyers', 'memory']

/** The main navigation, in order. The Advanced menu follows it. */
export const MAIN_NAV: readonly { href: string; view: View; label: string }[] = [
  { href: '#/demo', view: 'demo', label: 'Demo' },
  { href: '#/call', view: 'call', label: 'Try a call' },
  { href: '#/farmers', view: 'farmers', label: 'Farmers' },
]

/** Query keys that belong to the live browser call, which used to share #/demo with the recorded sample. */
const LIVE_CALL_PARAMS = ['lan', 'call'] as const

function split(hash: string): { head: string; rest: string[]; search: string } {
  const [path, search = ''] = hash.replace(/^#\/?/, '').split('?')
  const [head, ...rest] = path.split('/')
  return { head, rest, search }
}

/**
 * Where an old link should go now, or null when it is fine as it is. "#/demo?lan=..." or "#/demo?call=..." was the live
 * call before it got its own page, so it moves to "#/call" with the same query.
 */
export function redirectFor(hash: string): string | null {
  const { head, search } = split(hash)
  if (head !== 'demo') return null
  const query = new URLSearchParams(search)
  return LIVE_CALL_PARAMS.some((key) => query.has(key)) ? `#/call?${search}` : null
}

/** "#/demo", "#/call", "#/farmers/<key>", "#/how" (a section of home). Empty or unknown defaults to home. */
export function parseRoute(hash: string): Route {
  const target = redirectFor(hash) ?? hash
  const { head, rest, search } = split(target)
  const query = new URLSearchParams(search)
  if (head === 'how') return { view: 'home', param: 'how', query }
  const view = VIEWS.find((v) => v === head) ?? 'home'
  return { view, param: view !== 'home' && rest[0] ? decodeURIComponent(rest[0]) : null, query }
}

function subscribe(onChange: () => void) {
  window.addEventListener('hashchange', onChange)
  return () => window.removeEventListener('hashchange', onChange)
}

/** The current hash. A hash route needs nothing from the server. */
export function useHash(): string {
  return useSyncExternalStore(subscribe, () => location.hash)
}
