import { useMemo, type ReactNode } from 'react'
import { translationKey, type CallView, type TimelineItem } from '../lib/callState'
import { cropsOf, openFollowUps } from '../lib/farmers'
import { duration, languageLabel } from '../lib/format'
import { useStickToBottom } from '../lib/useStickToBottom'
import { CheckIcon, ChevronIcon, CrossIcon, LanguageIcon, LockIcon, MemoryIcon, SearchIcon, StoreIcon } from '../ui/icons'
import { sampleGloss } from './gloss'
import { notPassedOn } from './notPassedOn'
import { savedGistText, type SavedGist } from './savedGist'
import { toolNote } from './toolNote'
import { Avatar } from './parts'

type Row =
  | { kind: 'item'; key: string; atMs: number; item: TimelineItem }
  | { kind: 'note'; key: string; atMs: number; tone: 'plain' | 'ok' | 'bad'; icon: ReactNode; text: string }

function buildRows(call: CallView, saved: SavedGist | null): Row[] {
  const rows: Row[] = call.items.map((item) => ({ kind: 'item', key: item.key, atMs: item.atMs, item }))
  for (const tool of call.tools) {
    const note = toolNote(tool)
    rows.push({ kind: 'note', key: tool.key, atMs: tool.atMs, tone: note.ok ? 'plain' : 'bad', icon: note.ok ? <SearchIcon /> : <CrossIcon />, text: note.text })
  }
  for (const note of call.languageNotes) {
    const label = languageLabel(note.to)
    rows.push({ kind: 'note', key: note.key, atMs: note.atMs, tone: 'plain', icon: <LanguageIcon />, text: note.from === 'auto' ? `${note.role === 'farmer' ? 'Farmer' : 'Buyer'} speaks ${label}` : `${note.role === 'farmer' ? 'Farmer' : 'Buyer'}: switched to ${label}` })
  }
  if (call.memory?.returning) {
    const gist = call.memory.gists[0]
    const crops = cropsOf(call.memory.facts)
    const open = openFollowUps(call.memory.gists)
    const parts = ['Returning caller.', gist && `Last call: ${gist.summary}`, crops.length > 0 && `Crops: ${crops.join(', ')}.`, open.length > 0 && `Open: ${open.join('; ')}`]
    rows.push({ kind: 'note', key: 'memory', atMs: call.memory.atMs, tone: 'plain', icon: <MemoryIcon />, text: parts.filter(Boolean).join(' ') })
  }
  for (const leg of call.legs) {
    if (leg.role === 'buyer') rows.push({ kind: 'note', key: `join-${leg.legId}`, atMs: leg.joinedMs, tone: 'ok', icon: <StoreIcon />, text: 'Buyer joined on a second line' })
  }
  if (call.deal?.state === 'agreed') rows.push({ kind: 'note', key: 'agreed', atMs: call.deal.atMs, tone: 'ok', icon: <CheckIcon />, text: 'Both sides said yes. The terms are recorded.' })
  if (call.ended) rows.push({ kind: 'note', key: 'ended', atMs: call.ended.atMs, tone: 'plain', icon: <CheckIcon />, text: `Call ended after ${duration(call.ended.durationMs)}` })
  if (call.ended && saved) rows.push({ kind: 'note', key: 'saved', atMs: call.ended.atMs + 1, tone: saved.state === 'saved' ? 'ok' : 'plain', icon: <MemoryIcon />, text: savedGistText(saved) })
  return rows.sort((a, b) => a.atMs - b.atMs)
}

function langOf(code: string): string {
  return code.split('-')[0]
}

function isOther(from: string, text: string): boolean {
  return from.length > 0 && text.length > 0
}

const sideTone = {
  farmer: 'bg-farmer-soft',
  buyer: 'bg-buyer-soft',
}

function Turn({ call, item }: { call: CallView; item: Extract<TimelineItem, { kind: 'turn' }> }) {
  const translation = call.translations[translationKey(item.legId, item.startedMs)]
  const other = item.role === 'farmer' ? 'buyer' : 'farmer'
  const listening = call.legs.some((l) => l.role === other && !l.ended)
  const gloss = call.origin !== 'feed' && !translation ? sampleGloss(item.text) : null
  const kept = notPassedOn(call.items, item.key, translation !== undefined)
  const right = item.role === 'buyer'
  return (
    <div className={`animate-rise flex gap-3 ${right ? 'flex-row-reverse pl-8 lg:ml-auto lg:w-1/2 lg:pl-8' : 'pr-8 lg:w-1/2 lg:pr-8'}`}>
      <Avatar who={item.role} size="sm" />
      <div className={`min-w-0 rounded-2xl px-4 py-3 ${sideTone[item.role]} ${right ? 'rounded-tr-md' : 'rounded-tl-md'}`}>
        <p className="text-sm font-bold text-muted">{item.role === 'farmer' ? 'Farmer' : 'Buyer'}</p>
        <p lang={langOf(item.language)} className="text-xl leading-snug font-semibold">{item.text}</p>
        {translation ? (
          <p lang={langOf(translation.toLanguage)} className="mt-1.5 border-t border-ink/10 pt-1.5 text-[1.0625rem] leading-snug text-muted">
            <span className="sr-only">Translated: </span>{translation.text}
          </p>
        ) : gloss ? (
          <p lang={gloss.lang} className="mt-1.5 border-t border-ink/10 pt-1.5 text-[1.0625rem] leading-snug text-muted">{gloss.text}</p>
        ) : kept ? (
          <p className="mt-1.5 border-t border-ink/10 pt-1.5 text-sm font-semibold text-muted">Not passed on to the {other}</p>
        ) : listening && isOther(item.language, item.text) && !call.ended ? (
          <p className="animate-shimmer mt-1.5 border-t border-ink/10 pt-1.5 text-[1.0625rem] leading-snug text-muted">Translating…</p>
        ) : null}
      </div>
    </div>
  )
}

function Said({ call, item }: { call: CallView; item: Extract<TimelineItem, { kind: 'assistant' }> }) {
  const gloss = call.origin !== 'feed' ? sampleGloss(item.text) : null
  const toBuyer = item.role === 'buyer'
  return (
    <div className="animate-rise relative z-10 mx-auto w-full max-w-[34rem]">
      <div className="rounded-2xl border border-assistant/30 bg-assistant-soft px-4 py-3 text-center">
        <p className="flex items-center justify-center gap-1 text-sm font-bold text-assistant">
          {!toBuyer && <ChevronIcon className="rotate-90" />} G-One to {toBuyer ? 'buyer' : 'farmer'} {toBuyer && <ChevronIcon className="-rotate-90" />}
        </p>
        <p lang={langOf(item.language)} className="text-xl leading-snug font-semibold">{item.text}</p>
        {gloss && <p lang={gloss.lang} className="mt-1 text-[1.0625rem] leading-snug text-muted">{gloss.text}</p>}
      </div>
    </div>
  )
}

const WHISPER_LABEL = { price_reference: 'Price note', caution: 'Caution', tip: 'Tip' }

function Whisper({ call, item }: { call: CallView; item: Extract<TimelineItem, { kind: 'whisper' }> }) {
  const gloss = call.origin !== 'feed' ? sampleGloss(item.text) : null
  return (
    <div className="animate-rise grid items-stretch gap-3 lg:grid-cols-2">
      <div className="flex gap-3 pr-8 lg:pr-8">
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-whisper-soft text-base text-whisper"><LockIcon /></span>
        <div className="min-w-0 rounded-2xl rounded-tl-md border-2 border-dashed border-whisper-edge bg-whisper-soft px-4 py-3">
          <p className="text-sm font-bold text-whisper">Only the farmer hears this · {WHISPER_LABEL[item.whisperKind]}</p>
          <p lang={langOf(item.language)} className="text-xl leading-snug font-semibold">{item.text}</p>
          {gloss && <p lang={gloss.lang} className="mt-1.5 text-[1.0625rem] leading-snug text-muted">{gloss.text}</p>}
        </div>
      </div>
      <div className="hatch-quiet hidden items-center justify-center rounded-2xl pl-0 text-sm font-semibold text-faint lg:ml-8 lg:flex">
        <span className="rounded-md bg-surface px-2 py-0.5">The buyer hears nothing</span>
      </div>
    </div>
  )
}

const NOTE_TONE = { plain: 'text-muted', ok: 'text-ok', bad: 'text-bad' }

export function Note({ row }: { row: Pick<Extract<Row, { kind: 'note' }>, 'tone' | 'icon' | 'text'> }) {
  return (
    <div className="animate-rise relative z-10 flex justify-center">
      <p className={`flex max-w-[34rem] items-start gap-1.5 rounded-xl border border-line bg-surface px-3 py-1 text-[0.9375rem] font-medium ${NOTE_TONE[row.tone]}`}>
        <span className="mt-0.5">{row.icon}</span> <span>{row.text}</span>
      </p>
    </div>
  )
}

export default function Timeline({ call, empty, saved = null }: { call: CallView | null; empty: ReactNode; saved?: SavedGist | null }) {
  const rows = useMemo(() => (call ? buildRows(call, saved) : []), [call, saved])
  const { ref, onScroll, away, jump } = useStickToBottom(rows.length + (call ? Object.keys(call.translations).length : 0))
  return (
    <div className="relative min-h-0 flex-1">
      <div ref={ref} onScroll={onScroll} tabIndex={0} role="log" aria-label="Call conversation" className="absolute inset-0 overflow-y-auto overscroll-contain px-3 py-4 outline-none sm:px-5">
        {call && rows.length > 0 ? (
          <div className="relative min-h-full space-y-3.5">
            <span aria-hidden="true" className="absolute inset-y-0 left-1/2 hidden w-px bg-line lg:block" />
            {rows.map((row) =>
              row.kind === 'note' ? (
                <Note key={row.key} row={row} />
              ) : row.item.kind === 'turn' ? (
                <Turn key={row.key} call={call} item={row.item} />
              ) : row.item.kind === 'assistant' ? (
                <Said key={row.key} call={call} item={row.item} />
              ) : (
                <Whisper key={row.key} call={call} item={row.item} />
              ),
            )}
          </div>
        ) : (
          empty
        )}
      </div>
      {away && <button type="button" onClick={jump} className="btn-primary btn-sm absolute bottom-3 left-1/2 z-20 -translate-x-1/2 shadow-lift">Jump to latest</button>}
    </div>
  )
}
