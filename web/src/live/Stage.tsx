import { callPhase, legActivity, legByRole, type CallPhase, type CallView, type LegActivity } from '../lib/callState'
import { duration, languageLabel, offset } from '../lib/format'
import { useI18n, type MessageKey } from '../lib/i18n'
import { useNow } from '../lib/useNow'
import type { LegRole } from '../types/console'
import { BuyersIcon, Logo, WheatIcon } from '../ui/icons'
import EqBars from './EqBars'

const ROLE_STYLE = {
  farmer: { text: 'text-farmer', soft: 'bg-farmer-soft', bar: 'bg-farmer' },
  buyer: { text: 'text-buyer', soft: 'bg-buyer-soft', bar: 'bg-buyer' },
} as const

const ACTIVITY: Record<LegActivity, MessageKey> = {
  assistant: 'activity_assistant',
  whisper: 'activity_whisper',
  person: 'activity_person',
  idle: 'activity_idle',
}

function LegCard({ call, role, now }: { call: CallView; role: LegRole; now: number }) {
  const { t, lang } = useI18n()
  const leg = legByRole(call, role)
  const style = ROLE_STYLE[role]
  const activity = legActivity(call, role, now)
  const Icon = role === 'farmer' ? WheatIcon : BuyersIcon
  // A stored call has no one left to wait for: a missing leg either never joined or was not kept.
  const stored = call.origin === 'stored'
  const missing = call.recorded?.notStored[role]
  const absent = stored && missing && missing !== 'no_leg' ? t('leg_not_stored') : t('leg_not_joined')
  const status = !leg ? (stored ? '' : t('leg_waiting')) : leg.ended ? t('leg_ended', { reason: leg.ended.reason }) : t('leg_connected')
  return (
    <div className={`rounded-xl border border-line p-3 md:p-4 ${leg ? 'bg-surface' : 'border-dashed bg-transparent'}`}>
      <div className="flex items-center gap-3">
        <span className={`grid h-11 w-11 shrink-0 place-items-center rounded-full text-xl ${style.soft} ${style.text}`}><Icon /></span>
        <div className="min-w-0 flex-1">
          <p className="text-lg leading-tight font-bold">{t(role)}</p>
          <p className="truncate text-sm text-muted">
            {leg ? `${languageLabel(leg.language, lang)} · ${t(leg.source === 'twilio' ? 'source_twilio' : 'source_simulator')}` : absent}
          </p>
        </div>
        <p className="text-right text-sm font-semibold text-muted">{status}</p>
      </div>
      <div className="mt-2 flex items-center gap-3">
        <EqBars active={activity !== 'idle'} colorClass={activity === 'whisper' ? 'text-whisper' : activity === 'assistant' ? 'text-assistant' : style.text} />
        {/* Fixed width, so the bars never move when the words change. */}
        <p aria-live="off" className="w-28 shrink-0 text-right text-sm text-muted">{t(leg ? ACTIVITY[activity] : 'activity_idle')}</p>
      </div>
    </div>
  )
}

function phaseText(phase: CallPhase, t: ReturnType<typeof useI18n>['t']): string {
  switch (phase.kind) {
    case 'ended':
      return t('phase_ended')
    case 'listening':
      return t('phase_listening')
    case 'translating':
      return t('phase_translating', { role: t(phase.from) })
    case 'whisper':
      return t('phase_whisper')
    case 'speaking':
      return t('phase_speaking', { role: t(phase.to) })
    case 'tool':
      return t('phase_tool', { tool: phase.tool })
  }
}

function Assistant({ phase }: { phase: CallPhase }) {
  const { t } = useI18n()
  const busy = phase.kind !== 'listening' && phase.kind !== 'ended'
  return (
    <div className="flex items-center gap-3 md:w-44 md:flex-col md:justify-center md:gap-1.5 md:text-center">
      <span className="relative grid h-14 w-14 shrink-0 place-items-center text-2xl text-ink">
        {busy && <span aria-hidden="true" className="absolute inset-1 animate-beat rounded-full bg-assistant" />}
        <span className="relative"><Logo width="2.2em" height="2.2em" /></span>
      </span>
      <div>
        <p className="font-bold">{t('assistant')}</p>
        <p className="min-h-[2.6em] text-sm leading-snug text-muted">{phaseText(phase, t)}</p>
      </div>
    </div>
  )
}

/** When each side spoke, on one time axis. Block widths are the turn lengths from the feed. */
function CallTrack({ call, now }: { call: CallView; now: number }) {
  const { t } = useI18n()
  const end = call.ended?.atMs ?? now
  const span = Math.max(10_000, end - call.startedMs)
  const pct = (ms: number) => Math.min(100, Math.max(0, ((ms - call.startedMs) / span) * 100))
  const roles: LegRole[] = ['farmer', 'buyer']
  return (
    <div>
      <div className="space-y-1.5">
        {roles.map((role) => (
          <div key={role} className="flex items-center gap-2">
            <span className="w-14 shrink-0 text-xs font-semibold text-muted">{t(role)}</span>
            <div className="relative h-4 flex-1 overflow-hidden rounded bg-sunk">
              {call.items.filter((item) => (item.kind === 'whisper' ? role === 'farmer' : item.role === role)).map((item) => {
                if (item.kind === 'whisper') {
                  return <span key={item.key} title={`${t('whisper')} ${offset(item.atMs, call.startedMs)}`} className="absolute top-0 h-full w-1.5 rounded-sm bg-whisper-edge" style={{ left: `${pct(item.atMs)}%` }} />
                }
                const from = item.kind === 'turn' ? item.startedMs : item.atMs
                const to = item.kind === 'turn' ? item.atMs : item.atMs + Math.min(9000, Math.max(1200, item.text.length * 75))
                const who = item.kind === 'turn' ? t(role) : t('assistant')
                return (
                  <span
                    key={item.key}
                    title={`${who} ${offset(from, call.startedMs)}, ${duration(to - from)}`}
                    className={`absolute top-0.5 bottom-0.5 min-w-1 rounded-[3px] border-r-2 border-sunk ${item.kind === 'turn' ? ROLE_STYLE[role].bar : 'bg-assistant'}`}
                    style={{ left: `${pct(from)}%`, width: `${pct(to) - pct(from)}%` }}
                  />
                )
              })}
            </div>
          </div>
        ))}
      </div>
      <div className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-1 pl-16 text-xs text-muted">
        <span className="inline-flex items-center gap-1.5"><i className="h-2.5 w-2.5 rounded-sm bg-farmer" />{t('farmer')}</span>
        <span className="inline-flex items-center gap-1.5"><i className="h-2.5 w-2.5 rounded-sm bg-buyer" />{t('buyer')}</span>
        <span className="inline-flex items-center gap-1.5"><i className="h-2.5 w-2.5 rounded-sm bg-assistant" />{t('assistant')}</span>
        <span className="inline-flex items-center gap-1.5"><i className="h-2.5 w-1.5 rounded-sm bg-whisper-edge" />{t('whisper')}</span>
        <span className="ml-auto font-mono tabular-nums">{duration(end - call.startedMs)}</span>
      </div>
    </div>
  )
}

/** The two legs with the assistant between them, which is what G-One is. */
export default function Stage({ call }: { call: CallView }) {
  const now = useNow(!call.ended, 250)
  const phase = callPhase(call, now)
  return (
    <section className="card space-y-3 p-3 md:p-4">
      <div className="grid gap-3 md:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] md:items-center">
        <LegCard call={call} role="farmer" now={now} />
        <Assistant phase={phase} />
        <LegCard call={call} role="buyer" now={now} />
      </div>
      <CallTrack call={call} now={now} />
    </section>
  )
}
