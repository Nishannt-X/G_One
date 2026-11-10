import { callPhase, legActivity, legByRole, type CallPhase, type CallView } from '../lib/callState'
import { duration, languageLabel } from '../lib/format'
import { useNow } from '../lib/useNow'
import type { FeedStatus } from '../lib/useConsoleFeed'
import type { LegRole } from '../types/console'
import { Avatar, Waveform } from './parts'

const PHASE_TEXT = (phase: CallPhase): string => {
  switch (phase.kind) {
    case 'ended': return 'The call has ended'
    case 'listening': return 'Listening'
    case 'translating': return `Translating for the ${phase.from === 'farmer' ? 'buyer' : 'farmer'}`
    case 'whisper': return 'Whispering to the farmer'
    case 'speaking': return `Speaking to the ${phase.to}`
    case 'tool': return phase.tool.includes('mandi') ? 'Checking the mandi reference' : phase.tool.includes('buyer') ? 'Looking for a buyer' : 'Looking something up'
  }
}

const FEED_LABEL: Record<FeedStatus, { text: string; dot: string }> = {
  connected: { text: 'Engine connected', dot: 'bg-ok' },
  connecting: { text: 'Connecting…', dot: 'bg-warn' },
  offline: { text: 'Engine offline', dot: 'bg-bad' },
}

/** Whether the backend's console feed is up, so calls can start. */
export function FeedStatusLabel({ status }: { status: FeedStatus }) {
  const label = FEED_LABEL[status]
  return <span role="status" className="flex items-center gap-1.5 text-sm font-medium text-muted"><span aria-hidden="true" className={`h-2 w-2 rounded-full ${label.dot}`} /> {label.text}</span>
}

function Side({ role, call, now }: { role: LegRole; call: CallView | null; now: number }) {
  const leg = call ? legByRole(call, role) : undefined
  const activity = call ? legActivity(call, role, now) : 'idle'
  const name = role === 'farmer' ? call?.memory?.facts.find((f) => f.field === 'name')?.value ?? 'Farmer' : 'Buyer'
  const speaking = activity === 'person' || activity === 'assistant' || activity === 'whisper'
  const state = !leg ? (role === 'buyer' && call ? 'Not on the line yet' : 'Waiting') : leg.ended ? 'Left the call' : activity === 'whisper' ? 'Hearing a private note' : activity === 'person' ? 'Speaking' : activity === 'assistant' ? 'Hearing G-One' : 'On the line'
  const right = role === 'buyer'
  return (
    <div className={`flex min-w-0 flex-1 items-center gap-2.5 ${right ? 'flex-row-reverse text-right' : ''}`}>
      <span className="hidden sm:block"><Avatar who={role} speaking={speaking} /></span>
      <div className="min-w-0">
        <p className="truncate text-lg leading-tight font-semibold">{name}</p>
        <p className="truncate text-sm leading-tight text-muted">{leg && <span className="hidden sm:inline">{languageLabel(leg.language)} · </span>}{state}</p>
      </div>
      <Waveform active={speaking} className={`hidden xl:flex ${role === 'farmer' ? 'text-farmer' : 'text-buyer'}`} />
    </div>
  )
}

/** Farmer, G-One and buyer with what each is doing. Refreshes on its own clock, so the conversation below does not re-render with it. */
export default function CallBar({ call }: { call: CallView | null }) {
  const running = Boolean(call && !call.ended)
  const now = useNow(running, 300)
  const phase = call ? callPhase(call, now) : null
  const busy = phase !== null && phase.kind !== 'listening' && phase.kind !== 'ended'
  return (
    <div className="flex items-center gap-2 border-b border-line px-3 py-2.5 sm:gap-4 sm:px-5">
      <Side role="farmer" call={call} now={now} />
      <div className="flex w-28 shrink-0 flex-col items-center sm:w-52">
        <span className="flex items-center gap-1.5"><Avatar who="assistant" size="sm" speaking={busy} /><span className="font-semibold">G-One</span></span>
        <span aria-live="polite" className="w-full truncate text-center text-xs leading-tight text-muted sm:text-sm">{phase ? PHASE_TEXT(phase) : 'Ready'}</span>
      </div>
      <Side role="buyer" call={call} now={now} />
    </div>
  )
}

export function CallClock({ call }: { call: CallView }) {
  const now = useNow(!call.ended, 1000)
  return <span className="font-semibold tabular-nums">{duration(call.ended ? call.ended.durationMs : now - call.startedMs)}</span>
}
