import { useT, type MessageKey } from '../lib/i18n'
import type { CreditMode } from './creditMode'

const KEYS: Record<NonNullable<CreditMode>, MessageKey> = { live: 'credit_speech_live', sample: 'credit_speech_sample' }

/** One quiet line under the call bar. The line is always there, so the conversation does not move when it fills in. */
export default function SpeechCredit({ mode }: { mode: CreditMode }) {
  const t = useT()
  return <p data-speech-credit={mode ?? 'none'} className="h-5 shrink-0 truncate px-3 text-center text-xs leading-5 text-muted sm:px-5">{mode && t(KEYS[mode])}</p>
}
