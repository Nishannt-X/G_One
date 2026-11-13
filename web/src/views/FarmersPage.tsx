import { useCallback, useEffect, useMemo, useState } from 'react'
import { accessCode, api, ApiError, setAccessCode, type ApiFailure } from '../lib/api'
import { forgetSeen, fromRecord, maskedKey, readSeen, searchFarmers, toFarmer, type Farmer, type FarmerDeal } from '../lib/farmers'
import { humanize, languageLabel, rupees, shortDate } from '../lib/format'
import { parseMemory } from '../lib/memory'
import type { Origin } from '../types/console'
import { AlertIcon, CheckIcon, ChevronIcon, CrossIcon, LockIcon, PhoneIcon, PlayIcon, SearchIcon, TrashIcon, WheatIcon } from '../ui/icons'
import { Dialog, Drawer, Empty, ErrorNote, Skeleton } from '../ui/parts'

function scriptLang(text: string): string | undefined {
  if (/[ऀ-ॿ]/.test(text)) return 'hi'
  if (/[ঀ-৿]/.test(text)) return 'bn'
  return undefined
}

function Provenance({ origin }: { origin: Origin }) {
  return <span className={`badge ${origin === 'said' ? 'badge-ok' : ''}`}>{origin === 'said' ? 'Told' : 'Inferred'}</span>
}

function OriginBadge({ farmer }: { farmer: Farmer }) {
  if (farmer.origin === 'memory') return null
  return <span className="badge badge-warn">{farmer.origin === 'sample' ? 'Sample call' : 'Synthetic'}</span>
}

function Initial({ farmer }: { farmer: Farmer }) {
  // A whole grapheme: रा, not र with its vowel sign cut off.
  const letter = farmer.name ? [...new Intl.Segmenter().segment(farmer.name)][0]?.segment : null
  return (
    <span lang={farmer.name ? scriptLang(farmer.name) : undefined} className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-farmer-soft text-xl font-semibold text-farmer">
      {letter ?? <WheatIcon />}
    </span>
  )
}

function Row({ farmer }: { farmer: Farmer }) {
  return (
    <li>
      <a href={`#/farmers/${farmer.callerKey}`} className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1 rounded-xl px-3 py-3 transition-colors hover:bg-sunk md:grid-cols-[auto_minmax(0,1.5fr)_minmax(0,1fr)_minmax(0,1.2fr)_6rem_auto]">
        <Initial farmer={farmer} />
        <div className="min-w-0">
          <p className="flex items-center gap-2">
            <span lang={farmer.name ? scriptLang(farmer.name) : undefined} className="truncate text-lg leading-tight font-semibold">{farmer.name ?? 'Name not given'}</span>
            <OriginBadge farmer={farmer} />
          </p>
          <p className="truncate text-sm text-muted">{[maskedKey(farmer.callerKey), farmer.district].filter(Boolean).join(' · ')}</p>
        </div>
        <p className="hidden truncate text-muted md:block">{languageLabel(farmer.language)}</p>
        <div className="col-start-2 row-start-2 flex min-w-0 flex-wrap gap-1 md:col-auto md:row-auto">
          {farmer.crops.length > 0 ? farmer.crops.slice(0, 3).map((c) => <span key={c} className="badge capitalize">{c}</span>) : <span className="text-sm text-faint">No crops yet</span>}
        </div>
        <p className="hidden text-muted tabular-nums md:block">{shortDate(farmer.lastCallMs)}</p>
        <ChevronIcon className="col-start-3 row-start-1 -rotate-90 text-faint md:col-auto md:row-auto" />
      </a>
    </li>
  )
}

function useDeals(farmer: Farmer | null): { deals: FarmerDeal[]; checking: boolean } {
  const [found, setFound] = useState<{ key: string; deals: FarmerDeal[] } | null>(null)
  const key = farmer?.callerKey ?? null
  const seeded = farmer?.deals
  const calls = useMemo(() => farmer?.gists.slice(0, 6).map((g) => g.callId) ?? [], [farmer])
  const origin = farmer?.origin
  useEffect(() => {
    if (!key || seeded || origin !== 'memory') return
    let cancelled = false
    void Promise.all(calls.map((id) => api(`/api/deals/${encodeURIComponent(id)}/receipt`).catch(() => null))).then((rows) => {
      if (cancelled) return
      const deals = rows.flatMap((row) => {
        if (typeof row !== 'object' || row === null) return []
        const r = row as Record<string, unknown>
        return [{ id: String(r.id ?? ''), commodity: String(r.commodity ?? ''), quantity: String(r.quantity ?? ''), price: typeof r.price === 'number' ? r.price : null, unit: String(r.unit ?? ''), pickup: String(r.pickup ?? ''), state: String(r.state ?? ''), atMs: typeof r.atMs === 'number' ? r.atMs : 0 }]
      })
      setFound({ key, deals })
    })
    return () => {
      cancelled = true
    }
  }, [key, seeded, origin, calls])
  if (seeded) return { deals: seeded, checking: false }
  if (origin !== 'memory') return { deals: [], checking: false }
  return { deals: found?.key === key ? found.deals : [], checking: found?.key !== key }
}

function Detail({ farmer, onClose, onForget }: { farmer: Farmer; onClose: () => void; onForget: () => void }) {
  const { deals, checking } = useDeals(farmer)
  const facts = farmer.facts
  return (
    <div className="flex min-h-full flex-col">
      <header className="sticky top-0 z-10 flex items-start gap-3 border-b border-line bg-surface p-5">
        <Initial farmer={farmer} />
        <div className="min-w-0 flex-1">
          <h2 lang={farmer.name ? scriptLang(farmer.name) : undefined} className="truncate text-2xl leading-tight font-semibold">{farmer.name ?? 'Name not given'}</h2>
          <p className="truncate text-sm text-muted">{maskedKey(farmer.callerKey)} · {languageLabel(farmer.language)}</p>
        </div>
        <OriginBadge farmer={farmer} />
        <button type="button" className="btn-ghost w-11 px-0" aria-label="Close" onClick={onClose} autoFocus><CrossIcon /></button>
      </header>

      <div className="flex-1 space-y-7 p-5">
        <section aria-label="What G-One knows">
          <h3 className="text-lg font-semibold">What G-One knows</h3>
          {facts.length === 0 ? (
            <p className="mt-1 text-muted">No facts saved yet.</p>
          ) : (
            <dl className="mt-2 divide-y divide-line rounded-xl border border-line">
              {facts.map((f) => (
                <div key={f.field} className="flex items-center justify-between gap-3 px-3 py-2.5">
                  <div className="min-w-0">
                    <dt className="text-sm text-muted capitalize">{humanize(f.field)}</dt>
                    <dd lang={scriptLang(f.value)} className="font-semibold break-words">{f.value}</dd>
                  </div>
                  <div className="flex shrink-0 flex-col items-end gap-0.5">
                    <Provenance origin={f.source} />
                    {f.atMs > 0 && <span className="text-xs text-faint">Last confirmed {shortDate(f.atMs)}</span>}
                  </div>
                </div>
              ))}
            </dl>
          )}
        </section>

        <section aria-label="Past calls">
          <h3 className="text-lg font-semibold">Past calls</h3>
          {farmer.gists.length === 0 ? (
            <p className="mt-1 text-muted">No call summaries yet.</p>
          ) : (
            <ol className="mt-3 space-y-4 border-l-2 border-line pl-4">
              {farmer.gists.map((g) => (
                <li key={g.callId} className="relative">
                  <span aria-hidden="true" className="absolute top-2 -left-[1.4rem] h-2.5 w-2.5 rounded-full bg-brand ring-4 ring-surface" />
                  <p className="text-sm font-semibold text-muted">{shortDate(g.startedMs)}</p>
                  <p className="leading-snug">{g.summary}</p>
                  {g.followUps.length > 0 && <p className="mt-1 text-sm text-muted">To follow up: {g.followUps.join('; ')}</p>}
                </li>
              ))}
            </ol>
          )}
        </section>

        <section aria-label="Deals">
          <h3 className="text-lg font-semibold">Deals</h3>
          {checking ? (
            <Skeleton className="mt-2 h-16 w-full" />
          ) : deals.length === 0 ? (
            <p className="mt-1 text-muted">No recorded deals for these calls.</p>
          ) : (
            <ul className="mt-2 space-y-2">
              {deals.map((d) => (
                <li key={d.id} className="rounded-xl bg-sunk p-3">
                  <p className="flex items-center justify-between gap-2">
                    <span className="font-semibold capitalize">{d.quantity} {d.commodity}</span>
                    {d.state === 'agreed' && <span className="chip bg-ok-soft text-ok"><CheckIcon /> Agreed</span>}
                  </p>
                  <p className="text-sm text-muted">{d.price !== null ? `${rupees(d.price)} / ${d.unit}` : 'Price not stated'}{d.pickup ? ` · ${d.pickup}` : ''}{d.atMs ? ` · ${shortDate(d.atMs)}` : ''}</p>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <footer className="border-t border-line p-5">
        <button type="button" className="btn-quiet w-full text-bad" onClick={onForget}><TrashIcon /> Forget this farmer</button>
        <p className="mt-2 text-sm text-faint">Removes their facts and call summaries from G-One's memory.</p>
      </footer>
    </div>
  )
}

function AccessForm({ onSaved }: { onSaved: () => void }) {
  const [code, setCode] = useState(accessCode())
  return (
    <form className="card flex flex-wrap items-center gap-2 p-4" onSubmit={(e) => { e.preventDefault(); setAccessCode(code); onSaved() }}>
      <span className="flex items-center gap-2 font-semibold"><LockIcon /> This memory needs an access code</span>
      <label className="min-w-40 flex-1"><span className="sr-only">Access code</span><input type="password" className="field" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="off" /></label>
      <button type="submit" className="btn-primary" disabled={!code}>Unlock</button>
    </form>
  )
}

export default function FarmersPage({ openKey, seed }: { openKey: string | null; seed: boolean }) {
  const showSeed = import.meta.env.DEV && seed
  const [farmers, setFarmers] = useState<Farmer[] | null>(null)
  const [failure, setFailure] = useState<ApiFailure | null>(null)
  const [query, setQuery] = useState('')
  const [reload, setReload] = useState(0)
  const [forgetting, setForgetting] = useState<Farmer | null>(null)
  const [busy, setBusy] = useState(false)
  const [forgetFailed, setForgetFailed] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const seen = readSeen()
      let problem: ApiFailure | null = null
      const rows = await Promise.all(
        seen.map(async (entry): Promise<Farmer | null> => {
          if (entry.origin !== 'memory') return entry.snapshot ? toFarmer(entry.callerKey, entry.origin, entry.language, entry.seenMs, entry.snapshot.facts, entry.snapshot.gists) : null
          try {
            const record = parseMemory(await api(`/api/memory/${encodeURIComponent(entry.callerKey)}`), entry.callerKey)
            return record.known ? fromRecord(entry, record) : null
          } catch (error) {
            problem = error instanceof ApiError ? error.failure : 'server'
            return null
          }
        }),
      )
      if (cancelled) return
      const seeded = import.meta.env.DEV && showSeed ? (await import('../dev/seedFarmers')).seedFarmers(Date.now()) : []
      if (cancelled) return
      setFailure(problem)
      setFarmers([...seeded, ...rows.filter((f): f is Farmer => f !== null)].sort((a, b) => b.lastCallMs - a.lastCallMs))
    })()
    return () => {
      cancelled = true
    }
  }, [showSeed, reload])

  const shown = useMemo(() => searchFarmers(farmers ?? [], query), [farmers, query])
  const open = openKey ? farmers?.find((f) => f.callerKey === openKey) ?? null : null
  const closeDetail = useCallback(() => {
    if (location.hash.startsWith('#/farmers/')) location.hash = `#/farmers${showSeed ? '?seed=1' : ''}`
  }, [showSeed])

  const forget = async () => {
    if (!forgetting) return
    const target = forgetting
    setBusy(true)
    setForgetFailed(false)
    try {
      if (target.origin === 'memory') await api(`/api/memory/${encodeURIComponent(target.callerKey)}`, { method: 'DELETE' })
      forgetSeen(target.callerKey)
      setFarmers((list) => list?.filter((f) => f.callerKey !== target.callerKey) ?? null)
      setForgetting(null)
      setNotice(`${target.name ?? maskedKey(target.callerKey)} has been forgotten.`)
      closeDetail()
    } catch {
      setForgetFailed(true)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="shell py-8 md:py-12">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div className="max-w-2xl">
          <p className="kicker">Farmer memory</p>
          <h1 className="mt-2 text-headline font-semibold">Every farmer, picked up where they left off.</h1>
          <p className="mt-3 text-lg text-muted">Each fact says whether the farmer told G-One or G-One inferred it. Memory sits against a hash of the number, and any farmer can be forgotten.</p>
        </div>
        {import.meta.env.DEV && !showSeed && <a className="btn-quiet btn-sm" href="#/farmers?seed=1">Load synthetic farmers (dev only)</a>}
      </header>

      {showSeed && <p role="note" className="mt-5 rounded-xl border border-dashed border-whisper-edge bg-whisper-soft px-4 py-2 font-semibold text-whisper">Showing invented farmers for layout work. Development builds only.</p>}
      {notice && <p role="status" className="animate-rise mt-5 flex items-center gap-2 rounded-xl bg-ok-soft px-4 py-3 font-semibold text-ok"><CheckIcon /> {notice}</p>}

      <div className="mt-6 space-y-4">
        {failure === 'unauthorized' ? (
          <AccessForm onSaved={() => setReload((r) => r + 1)} />
        ) : failure ? (
          <ErrorNote title="Some farmers could not be loaded" onRetry={() => setReload((r) => r + 1)}>The memory service did not answer. Farmers already on screen are up to date.</ErrorNote>
        ) : null}

        <section aria-label="Farmers" className="card overflow-hidden">
          <div className="flex items-center gap-3 border-b border-line p-3">
            <label className="relative min-w-0 flex-1">
              <span className="sr-only">Search farmers</span>
              <span className="pointer-events-none absolute inset-y-0 left-3 grid place-items-center text-muted"><SearchIcon /></span>
              <input className="field pl-10" placeholder="Search by name, crop, district or language" value={query} onChange={(e) => setQuery(e.target.value)} />
            </label>
            <p className="hidden shrink-0 text-muted tabular-nums sm:block">{farmers ? `${shown.length} of ${farmers.length}` : ' '}</p>
          </div>
          <div className="hidden grid-cols-[2.75rem_minmax(0,1.5fr)_minmax(0,1fr)_minmax(0,1.2fr)_6rem_1.25rem] gap-x-3 border-b border-line px-6 py-2 text-sm font-semibold text-muted md:grid">
            <span /><span>Farmer</span><span>Language</span><span>Crops</span><span>Last call</span><span />
          </div>
          {farmers === null ? (
            <div className="space-y-3 p-4">
              <Skeleton className="h-12 w-full" /><Skeleton className="h-12 w-full" /><Skeleton className="h-12 w-full" />
            </div>
          ) : farmers.length === 0 ? (
            <Empty icon={<WheatIcon />} title="No farmers here yet" action={<div className="flex flex-wrap justify-center gap-2"><a className="btn-primary" href="#/demo?autoplay=1"><PlayIcon /> Play the sample call</a><a className="btn-quiet" href="#/call"><PhoneIcon /> Make a call</a></div>}>
              A farmer appears here after this browser has watched them call. Memory has no public list, so nothing shows until then.
            </Empty>
          ) : shown.length === 0 ? (
            <Empty icon={<SearchIcon />} title="No farmer matches that">Try a crop, a district or part of a name.</Empty>
          ) : (
            <ul className="divide-y divide-line p-1.5">
              {shown.map((f) => <Row key={f.callerKey} farmer={f} />)}
            </ul>
          )}
        </section>
      </div>

      <Drawer open={Boolean(open)} onClose={closeDetail} label={open?.name ?? 'Farmer details'}>
        {open && <Detail farmer={open} onClose={closeDetail} onForget={() => { setForgetFailed(false); setForgetting(open) }} />}
        <Dialog open={forgetting !== null} onClose={() => setForgetting(null)} title="Forget this farmer?">
          <div className="space-y-4">
            <p className="flex items-start gap-3">
              <AlertIcon className="mt-0.5 text-bad" />
              <span>G-One will lose <strong className="font-semibold" lang={forgetting?.name ? scriptLang(forgetting.name) : undefined}>{forgetting?.name ?? 'this caller'}</strong>'s facts and {forgetting?.gists.length ?? 0} call {forgetting?.gists.length === 1 ? 'summary' : 'summaries'}. If they call again, they start from zero. This cannot be undone.</span>
            </p>
            {forgetFailed && <p role="alert" className="font-semibold text-bad">That did not work. The memory was not changed.</p>}
            <div className="flex justify-end gap-2">
              <button type="button" className="btn-quiet" disabled={busy} onClick={() => setForgetting(null)}>Keep them</button>
              <button type="button" className="btn-danger" disabled={busy} onClick={() => void forget()}>{busy ? 'Forgetting…' : 'Forget this farmer'}</button>
            </div>
          </div>
        </Dialog>
      </Drawer>
    </div>
  )
}
