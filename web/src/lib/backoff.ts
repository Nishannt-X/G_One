export interface BackoffOptions {
  baseMs: number
  maxMs: number
  /** Share of the delay that is randomised, 0..1, so many clients do not retry in step. */
  jitter: number
}

/** The wait before retry number `attempt` (1 is the first retry): doubles from baseMs up to maxMs. */
export function backoffDelay(attempt: number, options: BackoffOptions, random: () => number = Math.random): number {
  const exact = Math.min(options.baseMs * 2 ** Math.max(0, attempt - 1), options.maxMs)
  const spread = exact * options.jitter
  return Math.round(exact - spread + random() * spread)
}
