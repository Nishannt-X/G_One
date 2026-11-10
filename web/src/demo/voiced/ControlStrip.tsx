import { useEffect, useRef, type PointerEvent } from 'react'
import { duration } from '../../lib/format'
import { CrossIcon, PauseIcon, PlayIcon, RestartIcon, SkipIcon } from '../../ui/icons'
import type { Speed, VoicedEngine, VoicedState } from './engine'

const SPEEDS: Speed[] = [0.9, 1, 1.15]

function SoundIcon({ muted }: { muted: boolean }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className="h-[1.15em] w-[1.15em] fill-none stroke-current" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5H4Z" />
      {muted ? <path d="m16 9.5 5 5m0-5-5 5" /> : <path d="M16 9a4.2 4.2 0 0 1 0 6m2.8-8.8a8.2 8.2 0 0 1 0 11.6" />}
    </svg>
  )
}

export default function ControlStrip({ engine, state }: { engine: VoicedEngine; state: VoicedState }) {
  const fill = useRef<HTMLDivElement>(null)
  const time = useRef<HTMLSpanElement>(null)
  const { status, plan, playing, active, finished, loaded, total, speed, cursor, needsTap } = state
  const totalMs = plan ? plan.total * 1000 : 0

  useEffect(() => engine.onProgress((fraction, seconds) => {
    if (fill.current) fill.current.style.transform = `scaleX(${fraction})`
    if (time.current) time.current.textContent = duration(seconds * 1000)
  }), [engine])

  const ready = status === 'ready'
  const failed = status === 'failed'
  const seek = (e: PointerEvent<HTMLDivElement>) => {
    const box = e.currentTarget.getBoundingClientRect()
    engine.seekToFraction(Math.min(1, Math.max(0, (e.clientX - box.left) / box.width)))
  }
  const soundLabel = state.muted ? 'Unmute' : 'Mute'
  const statusText = failed ? 'The voices did not load.' : !ready ? `Loading voices ${loaded}/${total || '…'}` : 'Audio ready'

  return (
    <div className="flex flex-wrap items-center justify-between gap-x-2 gap-y-2 border-t sm:gap-x-3 border-line px-3 py-2 sm:px-5">
      <div className="flex items-center gap-1.5">
        {failed ? (
          <button type="button" className="btn-quiet btn-sm" onClick={() => void engine.load()}><RestartIcon /> Try again</button>
        ) : (
          <button type="button" className="btn-primary btn-sm w-10 px-0 sm:w-11" disabled={!ready} aria-label={playing ? 'Pause' : 'Play'} onClick={() => engine.toggle()}>
            {playing ? <PauseIcon /> : <PlayIcon />}
          </button>
        )}
        <button type="button" className="btn-quiet btn-sm w-10 px-0 sm:w-11" disabled={!ready || !active || needsTap} aria-label="Restart" title="Restart" onClick={() => engine.restart()}><RestartIcon /></button>
        <button type="button" className="btn-quiet btn-sm w-10 px-0 sm:w-11" disabled={!ready || !active || finished || needsTap} aria-label="Skip to next beat" title="Skip beat" onClick={() => engine.skip()}><SkipIcon /></button>
        <button type="button" className="btn-quiet btn-sm hidden w-11 px-0 sm:inline-flex" aria-label={soundLabel} aria-pressed={state.muted} title={soundLabel} onClick={() => engine.setMuted(!state.muted)}><SoundIcon muted={state.muted} /></button>
      </div>
      <div className="order-last flex w-full items-center gap-2.5 md:max-[1399px]:order-none md:max-[1399px]:w-auto md:max-[1399px]:flex-1">
        <div role="progressbar" aria-label="Sample progress" aria-valuemin={0} aria-valuemax={plan?.beatStarts.length ?? 0} aria-valuenow={Math.max(0, cursor.beat + 1)} onPointerDown={seek} className={`relative h-5 min-w-0 flex-1 touch-none ${ready ? 'cursor-pointer' : ''}`}>
          <div className="absolute inset-x-0 top-1.5 h-2 overflow-hidden rounded-full bg-sunk">
            <div ref={fill} className="h-full w-full origin-left bg-brand" style={{ transform: 'scaleX(0)' }} />
          </div>
          {plan?.beatStarts.map((start, i) => (
            <span key={i} aria-hidden="true" className={`absolute top-1 h-3 w-0.5 rounded-full ${i <= cursor.beat ? 'bg-brand' : 'bg-line-strong'}`} style={{ left: `${(start / plan.total) * 100}%` }} />
          ))}
        </div>
        <span className="shrink-0 text-sm font-semibold text-muted tabular-nums"><span ref={time}>0:00</span> / {duration(totalMs)}</span>
      </div>
      <div className="flex items-center gap-2">
        <div role="radiogroup" aria-label="Speed" className="grid grid-cols-3 rounded-lg bg-sunk p-0.5">
          {SPEEDS.map((s) => (
            <label key={s} className={`flex min-h-8 cursor-pointer items-center justify-center rounded-md px-1 text-sm font-semibold tabular-nums sm:px-2 transition-colors has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-brand ${speed === s ? 'bg-surface text-ink shadow-card' : 'text-muted'}`}>
              <input type="radio" className="sr-only" checked={speed === s} onChange={() => engine.setSpeed(s)} />
              {s}x
            </label>
          ))}
        </div>
        <span role="status" className={`hidden text-sm font-medium whitespace-nowrap lg:inline ${failed ? 'text-bad' : 'text-muted'}`}>{statusText}</span>
        {!ready && <span role="status" className={`text-xs font-medium whitespace-nowrap lg:hidden ${failed ? 'text-bad' : 'text-muted'}`}>{failed ? 'No audio' : `${loaded}/${total || '…'}`}</span>}
        {active && <button type="button" className="btn-quiet btn-sm w-10 px-0 sm:w-11" aria-label="Close sample" title="Close sample" onClick={() => engine.close()}><CrossIcon /></button>}
      </div>
    </div>
  )
}
