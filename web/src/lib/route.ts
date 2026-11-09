import { useSyncExternalStore } from 'react'

export type View = 'home' | 'demo' | 'farmers' | 'live' | 'memory' | 'buyers' | 'simulator'

export interface Route {
  view: View
  /** The path part after the view, such as the caller key in #/farmers/<key>, or the section in #/how. */
  param: string | null
  query: URLSearchParams
}

const VIEWS: readonly View[] = ['demo', 'farmers', 'live', 'memory', 'buyers', 'simulator']

/** The views that sit under the Advanced menu. */
export const ADVANCED_VIEWS: readonly View[] = ['live', 'simulator', 'buyers', 'memory']

/** "#/demo", "#/farmers/<key>", "#/how" (a section of home). Empty or unknown defaults to home. */
export function parseRoute(hash: string): Route {
  const [path, search = ''] = hash.replace(/^#\/?/, '').split('?')
  const [head, ...rest] = path.split('/')
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
