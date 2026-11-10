/** What exists in the repository today, stated plainly. No latency figures: none has been measured. */

/** Automated test counts from the last full runs, with the date they were counted. Update all three together. */
export const TEST_COUNTS = { backend: 862, web: 111, asOf: '10 Nov 2026' } as const

export const LANGUAGES = {
  liveTested: ['Hindi', 'English', 'Hinglish'],
  awaitingReview: ['Bengali', 'Tamil', 'Kannada'],
} as const

export type BuiltIcon = 'speech' | 'translate' | 'whisper' | 'consent' | 'deal' | 'memory' | 'tests' | 'languages'

export interface BuiltFact {
  icon: BuiltIcon
  title: string
  body: string
}

const list = (items: readonly string[]) => `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`

export const BUILT_FACTS: readonly BuiltFact[] = [
  { icon: 'speech', title: 'Gnani speech in the call', body: 'Gnani Prisma turns speech into text and Gnani Timbre gives G-One its voice in the browser call.' },
  { icon: 'translate', title: 'Translation both ways', body: 'The farmer and the buyer each hear the other in their own language.' },
  { icon: 'whisper', title: 'Private advice', body: 'Notes on price and caution go to the farmer only. The buyer does not hear them.' },
  { icon: 'consent', title: 'The buyer must say yes', body: 'A matched buyer is asked first and joins the call only after agreeing.' },
  { icon: 'deal', title: 'Two explicit yeses', body: 'Farmer and buyer each say yes before a deal is read back. It is a record of the call, not a contract.' },
  { icon: 'memory', title: 'Memory, with a forget option', body: 'G-One keeps a short gist of earlier calls, and any farmer can be forgotten.' },
  {
    icon: 'tests',
    title: `${TEST_COUNTS.backend} + ${TEST_COUNTS.web} automated tests`,
    body: `${TEST_COUNTS.backend} backend and ${TEST_COUNTS.web} web tests, as of ${TEST_COUNTS.asOf}. They use fakes and never call Gnani, a phone line or a language model.`,
  },
  {
    icon: 'languages',
    title: 'Languages, stated carefully',
    body: `${list(LANGUAGES.liveTested)} are exercised in live tests. ${list(LANGUAGES.awaitingReview)} are configured and awaiting review by native speakers.`,
  },
]
