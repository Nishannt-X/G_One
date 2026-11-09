import { useEffect, useState, type ReactNode } from 'react'
import { isSimulatorConfig, type Role, type SimulatorConfig } from './protocol'
import { useCallSimulator, type CallStatus } from './useCallSimulator'

const WITHHELD = ''

const STATUS_TEXT: Record<CallStatus, string> = {
  idle: 'Not on a call',
  connecting: 'Connecting…',
  live: 'On a call',
  ended: 'Call ended',
  error: 'Call failed',
  reconnecting: 'Reconnecting…',
}

const FIELD =
  'w-full rounded-xl border border-line-strong bg-surface px-4 py-3 text-lg font-semibold text-ink disabled:opacity-60 placeholder:text-faint'
const FOCUS = 'focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-brand'

interface DemoLine {
  role: string
  text: string
  note?: string
}

function useSimulatorConfig(): { config: SimulatorConfig | null; error: boolean } {
  const [config, setConfig] = useState<SimulatorConfig | null>(null)
  const [error, setError] = useState(false)
  useEffect(() => {
    let cancelled = false
    fetch('/api/simulator/config')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((body: unknown) => {
        if (cancelled) return
        if (isSimulatorConfig(body)) setConfig(body)
        else setError(true)
      })
      .catch(() => {
        if (!cancelled) setError(true)
      })
    return () => {
      cancelled = true
    }
  }, [])
  return { config, error }
}

function useDemoScript(): DemoLine[] {
  const [lines, setLines] = useState<DemoLine[]>([])
  useEffect(() => {
    let cancelled = false
    fetch('/api/demo/script')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((body: unknown) => {
        if (cancelled) return
        if (typeof body === 'object' && body !== null && 'lines' in body && Array.isArray((body as { lines: unknown[] }).lines)) {
          setLines((body as { lines: DemoLine[] }).lines)
        }
      })
      .catch(() => {
        // Not in demo mode or script endpoint unavailable
      })
    return () => {
      cancelled = true
    }
  }, [])
  return lines
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="block space-y-2">
      <span className="block text-lg font-bold">{label}</span>
      {children}
      {hint && <span className="block text-base text-muted">{hint}</span>}
    </label>
  )
}

/** The override's empty choice: the leg's language is worked out on the call. */
const DETECT = ''

export default function SimulatorPage() {
  const { config, error } = useSimulatorConfig()
  const demoLines = useDemoScript()
  const { state, start, stop, say, setMuted, setMicMode, setTalking, retry } = useCallSimulator()
  const [role, setRole] = useState<Role>('farmer')
  const [lang, setLang] = useState(DETECT)
  const [caller, setCaller] = useState('+919876543210')
  const [callId, setCallId] = useState('')
  const [typedText, setTypedText] = useState('')
  const [textOnly, setTextOnly] = useState(false)

  const inCall = state.status === 'connecting' || state.status === 'live' || state.status === 'reconnecting'
  const languages = config?.languages ?? []
  const language = languages.some((l) => l.code === lang) ? lang : DETECT
  const seconds = (n: number) => (n * 0.02).toFixed(1)

  const roleLines = demoLines.filter((l) => l.role === role)

  // Spacebar push-to-talk handler
  useEffect(() => {
    if (state.status !== 'live' || state.micMode !== 'push-to-talk') return

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.code === 'Space' && !(e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement)) {
        e.preventDefault()
        setTalking(true)
      }
    }
    const handleKeyUp = (e: KeyboardEvent) => {
      if (e.code === 'Space') {
        setTalking(false)
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    window.addEventListener('keyup', handleKeyUp)
    return () => {
      window.removeEventListener('keydown', handleKeyDown)
      window.removeEventListener('keyup', handleKeyUp)
    }
  }, [state.status, state.micMode, setTalking])

  const handleSend = () => {
    if (!typedText.trim()) return
    say(typedText)
    setTypedText('')
  }

  return (
    <div className="shell max-w-4xl py-8 md:py-10">
      <div className="space-y-5 md:space-y-6">
        <header className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="kicker">Advanced</p>
            <h1 className="text-3xl font-bold md:text-4xl">Call simulator</h1>
          </div>
          <a
            href="#/demo"
            className={`btn-quiet text-base font-semibold ${FOCUS}`}
          >
            Open the demo view
          </a>
        </header>

        {/* Notice Section */}
        <section
          aria-label="Simulator notice"
          className="rounded-2xl border-2 border-warn-soft bg-warn-soft p-5 text-ink shadow-card"
        >
          <p className="text-2xl font-extrabold">Simulated Browser Call</p>
          <p className="mt-2 text-base font-medium text-muted">
            Your browser simulates a telephone leg without dialing actual numbers or Twilio. When running offline demo mode,
            typed turns bypass speech STT. Use headphones if using your microphone so the audio does not echo.
          </p>
        </section>

        {/* Backend Error Banner */}
        {error && (
          <p
            role="alert"
            className="rounded-xl border border-bad bg-bad-soft p-4 text-base font-bold text-bad"
          >
            The backend did not answer, so synthetic caller numbers or languages could not be loaded. Ensure the backend is
            running on port 8080.
          </p>
        )}

        {/* Microphone Error & Text Fallback Banner */}
        {state.micError && (
          <div
            role="alert"
            className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-bad bg-bad-soft p-4 text-ink"
          >
            <div>
              <p className="font-bold text-bad">Microphone Notice</p>
              <p className="text-sm text-muted">{state.micError}</p>
            </div>
            <button
              type="button"
              className="btn-primary text-sm"
              onClick={() => {
                setTextOnly(true)
                void start({ role, lang: language === DETECT ? null : language, caller: caller === WITHHELD ? null : caller, callId, textOnly: true })
              }}
            >
              Continue in Text-Only Mode
            </button>
          </div>
        )}

        {/* Reconnecting Banner */}
        {state.status === 'reconnecting' && (
          <div
            role="status"
            className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-warn bg-warn-soft p-4 font-semibold text-warn"
          >
            <p>{state.message}</p>
            <button type="button" className="btn-quiet text-sm" onClick={retry}>
              Retry Now
            </button>
          </div>
        )}

        {/* Call Setup Form */}
        <form
          className="card space-y-5 p-5 md:p-6"
          onSubmit={(e) => {
            e.preventDefault()
            if (inCall) stop()
            else void start({ role, lang: language === DETECT ? null : language, caller: caller === WITHHELD ? null : caller, callId, textOnly })
          }}
        >
          <fieldset disabled={inCall} className="space-y-5">
            <legend className="sr-only">Call settings</legend>

            <div role="radiogroup" aria-label="Who are you on this call?" className="grid grid-cols-2 gap-3">
              {(['farmer', 'buyer'] as const).map((r) => (
                <label
                  key={r}
                  className={`cursor-pointer rounded-xl border-2 p-3 text-center text-lg font-bold transition-colors ${
                    role === r
                      ? r === 'farmer'
                        ? 'border-farmer bg-farmer-soft text-farmer'
                        : 'border-buyer bg-buyer-soft text-buyer'
                      : 'border-line bg-surface text-muted hover:bg-sunk'
                  } ${inCall ? 'opacity-60' : ''}`}
                >
                  <input
                    type="radio"
                    name="role"
                    value={r}
                    checked={role === r}
                    onChange={() => setRole(r)}
                    className="sr-only"
                  />
                  {r === 'farmer' ? 'Farmer' : 'Buyer'}
                </label>
              ))}
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Language override (advanced)">
                <select
                  value={language}
                  onChange={(e) => setLang(e.target.value)}
                  className={`${FIELD} ${FOCUS}`}
                >
                  <option value={DETECT}>None: G-One asks in English</option>
                  {languages.map((l) => (
                    <option key={l.code} value={l.code}>
                      {l.label} ({l.code})
                    </option>
                  ))}
                </select>
              </Field>

              <Field
                label="Synthetic Caller ID"
                hint="Same number preserves caller memory across calls. Withheld stores nothing."
              >
                <select
                  value={caller}
                  onChange={(e) => setCaller(e.target.value)}
                  className={`${FIELD} ${FOCUS}`}
                >
                  {(config?.callers ?? []).map((c) => (
                    <option key={c.number} value={c.number}>
                      {c.label}
                    </option>
                  ))}
                  <option value={WITHHELD}>Withheld, no memory</option>
                </select>
              </Field>
            </div>

            {role === 'buyer' && (
              <Field
                label="Call to Join (Optional)"
                hint="Paste the call id from the farmer tab. Leave blank to join the only pending call."
              >
                <input
                  value={callId}
                  onChange={(e) => setCallId(e.target.value)}
                  placeholder="call-…"
                  autoComplete="off"
                  spellCheck={false}
                  className={`${FIELD} ${FOCUS}`}
                />
              </Field>
            )}

            <div className="flex items-center gap-3">
              <label className="flex items-center gap-2 cursor-pointer text-sm font-semibold text-muted">
                <input
                  type="checkbox"
                  checked={textOnly}
                  onChange={(e) => setTextOnly(e.target.checked)}
                  className="h-4 w-4 rounded border-line"
                />
                Text-Only Mode (no microphone requested)
              </label>
            </div>
          </fieldset>

          {/* Call Controls */}
          <div className="flex flex-wrap items-center gap-3 border-t border-line pt-4">
            <button
              type="submit"
              disabled={!inCall && config === null}
              className={`btn min-h-12 px-8 text-lg font-bold ${
                inCall ? 'btn-danger' : 'btn-primary'
              }`}
            >
              {inCall ? 'Hang Up Call' : 'Start Simulated Call'}
            </button>

            {state.status === 'live' && !state.textOnly && (
              <div className="flex flex-wrap items-center gap-2">
                {/* Hands-free vs Push-to-talk toggle */}
                <div className="flex rounded-xl border border-line bg-sunk p-1">
                  <button
                    type="button"
                    onClick={() => setMicMode('hands-free')}
                    className={`rounded-lg px-3 py-1.5 text-sm font-bold ${
                      state.micMode === 'hands-free'
                        ? 'bg-surface shadow text-ink'
                        : 'text-muted hover:text-ink'
                    }`}
                  >
                    Hands-Free
                  </button>
                  <button
                    type="button"
                    onClick={() => setMicMode('push-to-talk')}
                    className={`rounded-lg px-3 py-1.5 text-sm font-bold ${
                      state.micMode === 'push-to-talk'
                        ? 'bg-surface shadow text-ink'
                        : 'text-muted hover:text-ink'
                    }`}
                  >
                    Push-to-Talk
                  </button>
                </div>

                {state.micMode === 'hands-free' ? (
                  <button
                    type="button"
                    aria-pressed={state.muted}
                    onClick={() => setMuted(!state.muted)}
                    className="btn-quiet"
                  >
                    {state.muted ? 'Unmute microphone' : 'Mute microphone'}
                  </button>
                ) : (
                  <button
                    type="button"
                    onMouseDown={() => setTalking(true)}
                    onMouseUp={() => setTalking(false)}
                    onTouchStart={() => setTalking(true)}
                    onTouchEnd={() => setTalking(false)}
                    className={`btn min-h-11 px-5 transition-colors ${
                      state.talking
                        ? 'bg-brand text-on-brand animate-pulse'
                        : 'btn-quiet'
                    }`}
                  >
                    {state.talking ? 'Transmitting, release to stop' : 'Hold to speak (Space)'}
                  </button>
                )}
              </div>
            )}
          </div>
        </form>

        {/* Live Call Conversation & Typed Turns */}
        {state.status === 'live' && (
          <section aria-label="Spoken turn input" className="card space-y-4 p-5 md:p-6">
            <h2 className="text-xl font-bold">Typed Turn Input ({role === 'farmer' ? 'Farmer' : 'Buyer'})</h2>
            <p className="text-sm text-muted">
              Sends <code className="font-mono text-xs">{`{"type":"say"}`}</code> to the simulator websocket.
              Required for offline demo mode and zero-key environments.
            </p>

            {/* Quick Demo Script Buttons */}
            {roleLines.length > 0 && (
              <div className="space-y-2">
                <p className="text-xs font-bold uppercase tracking-wider text-muted">Demo Quick Lines</p>
                <div className="flex flex-wrap gap-2">
                  {roleLines.map((line, idx) => (
                    <button
                      key={idx}
                      type="button"
                      onClick={() => say(line.text)}
                      className="btn-quiet text-left text-sm py-1.5 px-3"
                      title={line.note}
                    >
                      <span>{line.text}</span>
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* Custom Say Form */}
            <form
              className="flex gap-2"
              onSubmit={(e) => {
                e.preventDefault()
                handleSend()
              }}
            >
              <input
                type="text"
                className="field flex-1"
                placeholder={role === 'farmer' ? 'बोलो या टाइप करो (जैसे: मुझे बीस क्विंटल प्याज़ बेचना है)' : 'Type buyer turn (e.g. I can pay 3600 per quintal)'}
                value={typedText}
                onChange={(e) => setTypedText(e.target.value)}
              />
              <button type="submit" className="btn-primary" disabled={!typedText.trim()}>
                Send Turn
              </button>
            </form>
          </section>
        )}

        {/* Call Status & Diagnostic Stream Panel */}
        <section
          aria-label="Call status"
          className="card space-y-3 p-5 md:p-6"
        >
          <div className="flex items-center justify-between">
            <p role="status" className="text-xl font-extrabold">
              {STATUS_TEXT[state.status]}
              {state.status === 'live' && state.muted && ' (mic muted)'}
              {state.status === 'live' && state.textOnly && ' (text-only)'}
            </p>
            {state.assistantSpeaking && (
              <span className="chip bg-assistant-soft text-assistant font-bold animate-pulse">
                Assistant Speaking
              </span>
            )}
          </div>

          {state.message && <p className="text-base font-medium text-muted">{state.message}</p>}

          {state.callId && (
            <dl className="grid gap-x-4 gap-y-1.5 border-t border-line pt-3 text-sm sm:grid-cols-[max-content_1fr]">
              <dt className="font-bold text-muted">Call ID:</dt>
              <dd className="break-all font-mono font-bold">{state.callId}</dd>

              <dt className="font-bold text-muted">Farmer Memory:</dt>
              <dd>{state.memory ? 'Active for this synthetic number' : 'Off (withheld caller ID)'}</dd>

              <dt className="font-bold text-muted">Microphone Audio Sent:</dt>
              <dd>
                {state.framesSent} frames ({seconds(state.framesSent)} s of 20 ms 8 kHz PCM16 audio)
              </dd>

              <dt className="font-bold text-muted">Spoken Turns Sent:</dt>
              <dd>{state.turnsSent} typed turns via WebSocket</dd>

              <dt className="font-bold text-muted">Audio Received:</dt>
              <dd>
                {state.framesReceived} frames ({seconds(state.framesReceived)} s)
              </dd>
            </dl>
          )}
        </section>
      </div>
    </div>
  )
}
