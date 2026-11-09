import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { languageLabel } from '../lib/format'
import { parseJoinCall } from '../lib/lan'
import { useDemoLines, useSimulatorConfig } from '../lib/useSimulatorSetup'
import { useStickToBottom } from '../lib/useStickToBottom'
import { MicIcon, MicOffIcon, PhoneIcon, SendIcon } from '../ui/icons'
import { joinStage, type JoinStage } from './joinStage'
import type { CallLine, LineKind } from './protocol'
import { useCallSimulator } from './useCallSimulator'

const LINE_LABEL: Record<LineKind, string> = {
  you_said: 'You said',
  they_hear: 'Farmer hears',
  you_hear: 'You hear',
  g_one: 'G-One',
}

const LINE_STYLE: Record<LineKind, string> = {
  you_said: 'ml-8 bg-buyer-soft',
  they_hear: 'ml-8 bg-sunk text-muted',
  you_hear: 'mr-8 bg-farmer-soft',
  g_one: 'mr-8 bg-assistant-soft',
}

const BADGE: Record<JoinStage, string> = {
  'bad-link': 'Bad link',
  insecure: 'Not secure',
  ready: 'Not joined',
  connecting: 'Connecting…',
  waiting: 'On the line',
  'in-call': 'In call',
  reconnecting: 'Reconnecting…',
  ended: 'Ended',
  'mic-denied': 'Mic blocked',
  refused: 'Cannot join',
  unreachable: 'No connection',
}

function Notice({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-3 p-6 text-center" role="status">
      <h1 className="text-3xl leading-tight font-bold">{title}</h1>
      {children && <p className="max-w-sm text-lg text-muted">{children}</p>}
      {action}
    </div>
  )
}

function Transcript({ lines }: { lines: CallLine[] }) {
  const { ref, onScroll } = useStickToBottom(lines.length)
  return (
    <div ref={ref} onScroll={onScroll} aria-live="polite" className="flex-1 space-y-2 overflow-y-auto overscroll-contain p-4" data-testid="join-lines">
      {lines.map((line, i) => (
        <p key={i} className={`rounded-2xl p-3 ${LINE_STYLE[line.kind]}`}>
          <span className="block text-sm font-semibold text-muted">{LINE_LABEL[line.kind]}</span>
          <span lang={line.language.split('-')[0]} className="block text-xl leading-snug">{line.text}</span>
        </p>
      ))}
    </div>
  )
}

/** Keeps the phone awake during a call, since a sleeping phone drops the microphone. Best effort. */
function useWakeLock(active: boolean) {
  useEffect(() => {
    if (!active || !('wakeLock' in navigator)) return
    let lock: WakeLockSentinel | null = null
    let released = false
    navigator.wakeLock.request('screen').then((l) => {
      if (released) void l.release()
      else lock = l
    }).catch(() => {
      // Refused on low battery, for example. The call works without it.
    })
    return () => {
      released = true
      void lock?.release()
    }
  }, [active])
}

/**
 * The buyer's phone: #/join?call=<id>, opened from the demo page's QR code. Tapping join is the user gesture audio needs.
 * It speaks the same /ws/simulator protocol as every other leg. G-One still asks for the buyer's spoken yes first.
 */
export default function JoinPage({ query }: { query: URLSearchParams }) {
  const callId = parseJoinCall(query)
  const { failed } = useSimulatorConfig()
  const demoLines = useDemoLines()
  const { state, start, stop, say, setMuted } = useCallSimulator()
  const [text, setText] = useState('')
  const stage = joinStage(callId, window.isSecureContext, state)
  // Only the words to tap. A script note describes what the farmer is told, so it stays off this screen.
  const quickLines = useMemo(() => demoLines.filter((l) => l.role === 'buyer').map((l) => l.text), [demoLines])
  const typingOffered = quickLines.length > 0
  const onLine = stage === 'waiting' || stage === 'in-call'
  useWakeLock(onLine)

  const join = (textOnly = false) => {
    if (callId) void start({ role: 'buyer', lang: null, caller: null, callId, textOnly })
  }
  const send = () => {
    if (!text.trim()) return
    say(text)
    setText('')
  }
  const tryAgain = <button type="button" className="btn-primary btn-lg mt-2" onClick={() => join()}>Try again</button>

  return (
    <main className="flex h-dvh flex-col bg-page text-ink">
      <header className="flex items-center justify-between border-b border-line px-4 py-3">
        <p className="text-xl font-bold">G-One <span className="font-medium text-muted">· Buyer</span></p>
        <span className="flex items-center gap-2">
          {onLine && state.language && <span className="text-sm font-medium text-muted" data-testid="join-language">{languageLabel(state.language)}</span>}
          <span className="badge" data-testid="join-stage" data-stage={stage}>{BADGE[stage]}</span>
        </span>
      </header>

      {stage === 'bad-link' && <Notice title="This link has no call in it">Scan the QR code on the laptop again.</Notice>}
      {stage === 'insecure' && <Notice title="This page is not secure">A phone can only use its microphone on https. Open the https link from the QR code.</Notice>}
      {stage === 'ready' && (
        <div className="flex flex-1 flex-col items-center justify-center gap-5 p-6 text-center">
          <div>
            <h1 className="text-3xl leading-tight font-bold">A farmer is on the line</h1>
            <p className="mt-2 text-lg text-muted">G-One translates between you. It will ask which language you want, then ask you to say yes before you are connected.</p>
          </div>
          {failed && <p role="alert" className="font-medium text-bad">Could not reach the laptop. Check that the backend is running.</p>}
          <button type="button" aria-label="Join call" className="btn-primary grid size-40 place-items-center rounded-full text-xl font-bold" onClick={() => join()}>
            <span className="flex flex-col items-center gap-1"><MicIcon className="size-10" /> Join call</span>
          </button>
          <p className="text-sm text-muted">Your browser will ask for the microphone.</p>
        </div>
      )}
      {stage === 'connecting' && <Notice title="Connecting…">{state.message}</Notice>}
      {stage === 'reconnecting' && <Notice title="Reconnecting…">{state.message}</Notice>}
      {stage === 'ended' && <Notice title="The call ended">{state.message === 'You hung up.' ? 'You left the call.' : 'Thank you.'}</Notice>}
      {stage === 'mic-denied' && (
        <Notice title="The microphone is blocked" action={<div className="flex flex-col items-center gap-2">{tryAgain}{typingOffered && <button type="button" className="font-semibold underline" onClick={() => join(true)}>Type instead (demo)</button>}</div>}>
          Allow the microphone for this site in your browser settings, then try again.
        </Notice>
      )}
      {stage === 'refused' && <Notice title="Could not join this call">{state.message} Ask for a new QR code on the laptop.</Notice>}
      {stage === 'unreachable' && (
        <Notice title="Could not reach the laptop" action={tryAgain}>
          Stay on the same hotspot. If the browser warned about the certificate, open the page again and accept it once.
        </Notice>
      )}

      {stage === 'waiting' && <Notice title="On the line">G-One will speak first and ask which language you want.</Notice>}
      {stage === 'in-call' && <Transcript lines={state.lines} />}

      {onLine && (
        <div className="space-y-2 border-t border-line bg-surface p-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]">
          {typingOffered && (
            <>
              <div className="scrollbar-none flex gap-2 overflow-x-auto">
                {quickLines.map((line, i) => <button key={i} type="button" className="btn-quiet btn-sm shrink-0 font-medium" onClick={() => say(line)}><span className="max-w-56 truncate">{line}</span></button>)}
              </div>
              <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); send() }}>
                <label className="min-w-0 flex-1">
                  <span className="sr-only">Type what you say (demo)</span>
                  <input className="field" value={text} onChange={(e) => setText(e.target.value)} placeholder="Type what you say (demo)" />
                </label>
                <button type="submit" className="btn-primary w-11 shrink-0 px-0" disabled={!text.trim()} aria-label="Send"><SendIcon /></button>
              </form>
            </>
          )}
          <div className="grid grid-cols-[1fr_auto] gap-2">
            {state.textOnly ? (
              <p className="flex min-h-14 items-center justify-center font-semibold text-muted">Typing only</p>
            ) : (
              <button type="button" aria-pressed={state.muted} className={`${state.muted ? 'btn-quiet' : 'btn-primary'} min-h-14 text-lg font-bold`} onClick={() => setMuted(!state.muted)}>
                {state.muted ? <><MicOffIcon /> Muted, tap to talk</> : <><MicIcon /> Mic on</>}
              </button>
            )}
            <button type="button" className="btn-danger min-h-14 px-5 font-bold" onClick={stop}><PhoneIcon className="rotate-[135deg]" /> Leave</button>
          </div>
        </div>
      )}
    </main>
  )
}
