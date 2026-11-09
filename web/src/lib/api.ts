/** What went wrong with a request, in the terms the UI distinguishes. */
export type ApiFailure = 'unreachable' | 'unauthorized' | 'missing' | 'bad_request' | 'server'

export class ApiError extends Error {
  readonly failure: ApiFailure
  constructor(failure: ApiFailure) {
    super(failure)
    this.failure = failure
  }
}

export function failureOf(status: number): ApiFailure {
  if (status === 401 || status === 403) return 'unauthorized'
  if (status === 404 || status === 405) return 'missing'
  if (status === 400) return 'bad_request'
  return 'server'
}

const CODE_KEY = 'g-one-access-code'

export function accessCode(): string {
  try {
    return sessionStorage.getItem(CODE_KEY) ?? ''
  } catch {
    return ''
  }
}

export function setAccessCode(code: string) {
  try {
    sessionStorage.setItem(CODE_KEY, code)
  } catch {
    // Storage is blocked: the viewer is asked again.
  }
}

/** Fetches JSON and turns every failure into an {@link ApiError}. A body-less success resolves to null. */
export async function api(path: string, init: RequestInit = {}): Promise<unknown> {
  const code = accessCode()
  const headers = new Headers(init.headers)
  if (code) headers.set('X-Access-Code', code)
  let response: Response
  try {
    response = await fetch(path, { ...init, headers })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError('unreachable')
  }
  if (!response.ok) {
    // Vite answers 500 when its proxy finds no backend; a real backend error carries JSON.
    const proxyMiss = response.status === 500 && !response.headers.get('content-type')?.includes('json')
    throw new ApiError(proxyMiss ? 'unreachable' : failureOf(response.status))
  }
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    // Something other than our backend answered, such as a dev server's index page.
    throw new ApiError('missing')
  }
}
