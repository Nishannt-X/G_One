import { useState } from 'react'
import type { DemoLine } from '../lib/useSimulatorSetup'
import { maskedCallerLabel } from '../lib/useSimulatorSetup'
import type { Role, SimulatorConfig } from '../simulator/protocol'
import type { useCallSimulator } from '../simulator/useCallSimulator'
import { MicIcon, MicOffIcon, PhoneIcon, SendIcon } from '../ui/icons'

type Sim = ReturnType<typeof useCallSimulator>

const WITHHELD = ''

interface Props {
  role: Role
  sim: Sim
  config: SimulatorConfig | null
  lines: DemoLine[]
  /** The call a buyer would join. */
  callId: string
  canStart: boolean
  lockedReason?: string
  /** A buyer's idle line, such as how to join from a phone. */
  hint?: string
}

/** One person's phone: set up and start, then talk or type and hang up. Keeps the same height in every state. */
export default function LegDock({ role, sim, config, lines, callId, canStart, lockedReason, hint }: Props) {
  const { state, start, stop, say, setMuted, setMicMode, setTalking } = sim
  const farmer = role === 'farmer'
  const [lang, setLang] = useState(farmer ? 'hi-IN' : 'en-IN')
  const [caller, setCaller] = useState<string | null>(null)
  // The farmer is usually heard on the microphone. A buyer in the same tab usually types.
  const [voice, setVoice] = useState(farmer)
  const [text, setText] = useState('')

  const inCall = state.status === 'connecting' || state.status === 'live' || state.status === 'reconnecting'
  const languages = config?.languages ?? []
  const chosenCaller = caller ?? config?.callers[0]?.number ?? WITHHELD
  const pushToTalk = !farmer

  const begin = async (textOnly: boolean) => {
    if (pushToTalk) setMicMode('push-to-talk')
    await start({ role, lang, caller: farmer && chosenCaller !== WITHHELD ? chosenCaller : null, callId: farmer ? '' : callId, textOnly })
    // The session exists only now, so the mode has to be applied to it as well.
    if (pushToTalk) setMicMode('push-to-talk')
  }

  const send = () => {
    if (!text.trim()) return
    say(text)
    setText('')
  }

  if (!inCall) {
    return (
      <form className="flex h-full flex-col justify-between gap-2.5" onSubmit={(e) => { e.preventDefault(); void begin(!voice) }}>
        <div className="grid grid-cols-2 gap-2">
          {farmer ? (
            <label className="min-w-0">
              <span className="sr-only">Synthetic caller</span>
              <select className="field py-1" value={chosenCaller} onChange={(e) => setCaller(e.target.value)}>
                {(config?.callers ?? []).map((c) => <option key={c.number} value={c.number}>{maskedCallerLabel(c.label)}</option>)}
                <option value={WITHHELD}>Withheld, no memory</option>
              </select>
            </label>
          ) : (
            <p className="flex min-h-11 items-center rounded-xl bg-sunk px-3 text-[0.9375rem] leading-tight font-semibold text-muted">Buyer joins on their phone</p>
          )}
          <label className="min-w-0">
            <span className="sr-only">Language</span>
            <select className="field py-1" value={lang} onChange={(e) => setLang(e.target.value)}>
              {languages.length === 0 && <option value={lang}>{lang}</option>}
              {languages.map((l) => <option key={l.code} value={l.code}>{l.label}</option>)}
            </select>
          </label>
        </div>
        <div className="flex items-center gap-2">
          <div role="radiogroup" aria-label="How to talk" className="grid shrink-0 grid-cols-2 rounded-xl bg-sunk p-1">
            {[true, false].map((v) => (
              <label key={String(v)} className={`flex min-h-9 cursor-pointer items-center justify-center rounded-lg px-3 text-sm font-semibold transition-colors has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-brand ${voice === v ? 'bg-surface text-ink shadow-card' : 'text-muted'}`}>
                <input type="radio" className="sr-only" checked={voice === v} onChange={() => setVoice(v)} />
                {v ? (farmer ? 'Mic' : 'Talk') : 'Type'}
              </label>
            ))}
          </div>
          <button type="submit" disabled={!canStart || config === null} title={!canStart ? lockedReason : undefined} className={`${farmer ? 'btn-primary' : 'btn-quiet'} min-w-0 flex-1`}>
            <PhoneIcon /> <span className="truncate">{farmer ? 'Call as farmer' : 'Join as buyer'}</span>
          </button>
        </div>
        {state.status === 'error' ? (
          <p role="alert" className="flex items-center gap-2 text-sm leading-tight font-medium text-bad">
            <span className="min-w-0 flex-1 truncate" title={state.message}>{state.message}</span>
            {state.micError && <button type="button" className="shrink-0 font-bold underline" onClick={() => void begin(true)}>Use typing</button>}
          </p>
        ) : (
          <p className="truncate text-sm leading-tight text-faint">{!canStart ? lockedReason : !farmer ? (hint ?? 'Play the buyer from this tab.') : state.status === 'ended' ? state.message : 'A browser call, using a synthetic number.'}</p>
        )}
      </form>
    )
  }

  const live = state.status === 'live'
  const own = lines.filter((l) => l.role === role)
  return (
    <div className="flex h-full flex-col justify-between gap-2.5">
      <div className="scrollbar-none flex h-11 items-center gap-1.5 overflow-x-auto">
        {own.length > 0 ? own.map((l, i) => (
          <button key={i} type="button" disabled={!live} title={l.note} onClick={() => say(l.text)} className="btn-quiet btn-sm shrink-0 font-medium">
            <span lang={farmer ? 'hi' : 'en'} className="max-w-56 truncate">{l.text}</span>
          </button>
        )) : (
          <p role="status" className="truncate text-sm text-muted">{state.message}</p>
        )}
      </div>
      <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); send() }}>
        <label className="min-w-0 flex-1">
          <span className="sr-only">{farmer ? 'Type what the farmer says' : 'Type what the buyer says'}</span>
          <input className="field" value={text} onChange={(e) => setText(e.target.value)} disabled={!live} placeholder={farmer ? 'बोलिए या लिखिए…' : 'Type the buyer’s words…'} lang={farmer ? 'hi' : 'en'} />
        </label>
        <button type="submit" className="btn-primary w-11 shrink-0 px-0" disabled={!live || !text.trim()} aria-label="Send"><SendIcon /></button>
      </form>
      <div className="flex items-center gap-2">
        {!state.textOnly && (farmer ? (
          <button type="button" className="btn-quiet btn-sm" aria-pressed={state.muted} disabled={!live} onClick={() => setMuted(!state.muted)}>
            {state.muted ? <><MicOffIcon /> Muted</> : <><MicIcon /> Mic on</>}
          </button>
        ) : (
          <button type="button" className={`btn-sm ${state.talking ? 'btn-primary' : 'btn-quiet'}`} disabled={!live} onPointerDown={() => setTalking(true)} onPointerUp={() => setTalking(false)} onPointerLeave={() => setTalking(false)}>
            <MicIcon /> {state.talking ? 'Listening' : 'Hold to talk'}
          </button>
        ))}
        <span className="min-w-0 flex-1 truncate text-sm text-muted" role="status">{live ? (state.textOnly ? 'Typed turns' : 'On the call') : state.message}</span>
        <button type="button" className="btn-danger btn-sm" onClick={stop}><PhoneIcon className="rotate-[135deg]" /> {farmer ? 'End call' : 'Leave'}</button>
      </div>
    </div>
  )
}
