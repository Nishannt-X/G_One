/** What went wrong with a request, in the terms the UI distinguishes. */
/** `unavailable`: the backend answered 503, such as while a one-time cleanup runs. */
export type ApiFailure = 'unreachable' | 'unauthorized' | 'missing' | 'bad_request' | 'server' | 'unavailable'

export class ApiError extends Error {
  readonly failure: ApiFailure
  /** The backend's `error` code from a JSON error body, when it sent one. */
  readonly code: string | null
  constructor(failure: ApiFailure, code: string | null = null) {
    super(failure)
    this.failure = failure
    this.code = code
  }
}

export function failureOf(status: number): ApiFailure {
  if (status === 401 || status === 403) return 'unauthorized'
  if (status === 404 || status === 405) return 'missing'
  if (status === 400) return 'bad_request'
  if (status === 503) return 'unavailable'
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

async function errorCode(response: Response): Promise<string | null> {
  try {
    const body = (await response.json()) as { error?: unknown } | null
    return typeof body?.error === 'string' ? body.error : null
  } catch {
    return null
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
    const json = response.headers.get('content-type')?.includes('json') ?? false
    const proxyMiss = response.status === 500 && !json
    throw new ApiError(proxyMiss ? 'unreachable' : failureOf(response.status), json ? await errorCode(response) : null)
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
