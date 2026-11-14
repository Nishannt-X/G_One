import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { accessCode, api, ApiError, setAccessCode, type ApiFailure } from '../lib/api'
import { callOutcome, cropSummary, forgetSeen, fromRecord, maskedKey, openFollowUps, parseReceipt, placeOf, readSeen, searchFarmers, toFarmer, type Farmer, type FarmerDeal, type FarmerValue, type ProfileField } from '../lib/farmers'
import { englishText, initialOf } from '../lib/farmersScript'
import { dealPrice, duration, languageLabel, shortDate } from '../lib/format'
import { forgetCaller, forgetNotice, parseMemory } from '../lib/memory'
import type { Origin } from '../types/console'
import { AlertIcon, CheckIcon, ChevronIcon, CrossIcon, LockIcon, PhoneIcon, PlayIcon, SearchIcon, TrashIcon, WheatIcon } from '../ui/icons'
import { Dialog, Drawer, Empty, ErrorNote, Skeleton } from '../ui/parts'

const NOT_TOLD = 'Not told yet'

function Provenance({ origin }: { origin: Origin }) {
  return <span className={`badge ${origin === 'said' ? 'badge-ok' : ''}`}>{origin === 'said' ? 'Told' : 'Inferred'}</span>
}

function OriginBadge({ farmer }: { farmer: Farmer }) {
  if (farmer.origin === 'memory') return null
  return <span className="badge badge-warn">{farmer.origin === 'sample' ? 'Sample call' : 'Synthetic'}</span>
}

function Initial({ farmer, large = false }: { farmer: Farmer; large?: boolean }) {
  const letter = initialOf(farmer.name)
  return (
    <span aria-hidden="true" className={`grid shrink-0 place-items-center rounded-full bg-farmer-soft font-semibold text-farmer ${large ? 'h-14 w-14 text-2xl' : 'h-11 w-11 text-xl'}`}>
      {letter ?? <WheatIcon />}
    </span>
  )
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

/** One farmer in the list. `deals` is undefined while their deals are still being looked up. */
export function FarmerRow({ farmer }: { farmer: Farmer }) {
  const { shown, more } = cropSummary(farmer.crops)
  const followUps = openFollowUps(farmer.gists).length
  const deals = farmer.deals?.length
  const place = placeOf(farmer)
  return (
    <li>
      <a href={`#/farmers/${farmer.callerKey}`} className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1.5 rounded-xl px-3 py-3 transition-colors hover:bg-sunk lg:grid-cols-[auto_minmax(0,1.4fr)_minmax(0,0.8fr)_minmax(0,1.3fr)_5.5rem_3.5rem_5.5rem_auto]">
        <Initial farmer={farmer} />
        <div className="min-w-0">
          <p className="flex min-w-0 items-center gap-2">
            <span className="truncate text-lg leading-tight font-semibold">{farmer.name ?? 'Name not given'}</span>
            <OriginBadge farmer={farmer} />
          </p>
          <p className="truncate text-sm text-muted">{place ?? maskedKey(farmer.callerKey)}<span className="lg:hidden"> · {languageLabel(farmer.language)}</span></p>
        </div>
        <p className="hidden truncate text-muted lg:block">{languageLabel(farmer.language)}</p>
        <div className="col-start-2 row-start-2 flex min-w-0 flex-wrap items-center gap-1 lg:col-auto lg:row-auto">
          {shown.length > 0 ? shown.map((c) => <span key={c} className="badge capitalize">{c}</span>) : <span className="text-sm text-faint">No crops yet</span>}
          {more > 0 && <span className="badge" aria-label={`${more} more crops`}>+{more}</span>}
        </div>
        <p className="col-start-2 row-start-3 text-sm text-muted tabular-nums lg:col-auto lg:row-auto lg:text-base">
          <span className="lg:hidden">Last call </span>{shortDate(farmer.lastCallMs)}
          <span className="lg:hidden"> · {deals === undefined ? 'deals…' : plural(deals, 'deal', 'deals')} · {plural(followUps, 'follow-up', 'follow-ups')}</span>
        </p>
        <p className="hidden text-muted tabular-nums lg:block" aria-label={deals === undefined ? 'Deals loading' : plural(deals, 'deal', 'deals')}>{deals ?? '…'}</p>
        <p className="hidden tabular-nums lg:block" aria-label={plural(followUps, 'open follow-up', 'open follow-ups')}>
          {followUps > 0 ? <span className="badge badge-brand">{followUps} open</span> : <span className="text-faint">None</span>}
        </p>
        <ChevronIcon className="col-start-3 row-start-1 -rotate-90 text-faint lg:col-auto lg:row-auto" />
      </a>
    </li>
  )
}

function Card({ title, note, children }: { title: string; note?: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="tile p-4">
      <h3 className="text-lg leading-tight font-semibold">{title}</h3>
      {note && <p className="mt-0.5 text-sm text-faint">{note}</p>}
      <div className="mt-3">{children}</div>
    </section>
  )
}

function Field({ label, fact, show }: { label: string; fact: FarmerValue | undefined; show?: (value: string) => string }) {
  return (
    <div className="flex items-start justify-between gap-3 py-2 first:pt-0 last:pb-0">
      <div className="min-w-0">
        <dt className="text-sm text-muted">{label}</dt>
        {fact ? <dd className="font-semibold break-words">{show ? show(fact.value) : fact.value}</dd> : <dd className="text-faint italic">{NOT_TOLD}</dd>}
      </div>
      {fact && <Provenance origin={fact.source} />}
    </div>
  )
}

function Fields({ farmer, rows }: { farmer: Farmer; rows: [ProfileField, string, ((v: string) => string)?][] }) {
  // No stored language: the language of their calls, which G-One inferred.
  const fact = (field: ProfileField) => farmer.profile[field] ?? (field === 'language' && farmer.language ? { value: farmer.language, source: 'inferred' as const } : undefined)
  return (
    <dl className="divide-y divide-line">
      {rows.map(([field, label, show]) => <Field key={field} label={label} fact={fact(field)} show={show} />)}
    </dl>
  )
}

function DealItem({ deal }: { deal: FarmerDeal }) {
  const who = deal.buyer ?? (deal.market ? `Market: ${englishText(deal.market) ?? deal.market}` : null)
  return (
    <li className="rounded-xl border border-line bg-surface p-3">
      <p className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-semibold">{[deal.quantity, englishText(deal.commodity)].filter(Boolean).join(' ')}</span>
        {deal.state === 'agreed' ? <span className="chip bg-ok-soft text-ok"><CheckIcon /> Agreed</span> : <span className="badge badge-warn capitalize">{deal.state ? deal.state.replace(/_/g, ' ') : 'Not agreed'}</span>}
      </p>
      <p className="mt-0.5 text-sm text-muted">
        {deal.price !== null ? dealPrice(deal.price, deal.unit || null, ' per ') : 'Price not stated'}
        {who ? ` · ${who}` : ''}
        {deal.atMs ? <span className="whitespace-nowrap"> · {shortDate(deal.atMs)}</span> : null}
      </p>
      {deal.pickup && <p className="text-sm text-faint">Pickup: {englishText(deal.pickup) ?? deal.pickup}</p>}
    </li>
  )
}

/** The card-style detail of one farmer: profile, crops, how they work, deals, calls and open follow-ups. */
export function FarmerDetail({ farmer, deals, checking = false, onClose, onForget }: { farmer: Farmer; deals: FarmerDeal[]; checking?: boolean; onClose: () => void; onForget: () => void }) {
  const followUps = openFollowUps(farmer.gists)
  return (
    <div className="flex min-h-full flex-col">
      <header className="sticky top-0 z-10 flex items-start gap-3 border-b border-line bg-surface p-4 sm:p-5">
        <Initial farmer={farmer} large />
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-2xl leading-tight font-semibold">{farmer.name ?? 'Name not given'}</h2>
          <p className="truncate text-sm text-muted">{[placeOf(farmer), maskedKey(farmer.callerKey)].filter(Boolean).join(' · ')}</p>
          <p className="mt-1 flex flex-wrap gap-1"><OriginBadge farmer={farmer} /><span className="badge">{plural(farmer.callCount, 'call', 'calls')}</span></p>
        </div>
        <button type="button" className="btn-ghost w-11 px-0" aria-label="Close" onClick={onClose} autoFocus><CrossIcon /></button>
      </header>

      <div className="flex-1 space-y-3 p-4 sm:p-5">
        <Card title="Profile">
          <Fields farmer={farmer} rows={[['name', 'Name'], ['language', 'Language', (v) => languageLabel(v)], ['village', 'Village'], ['district', 'District'], ['state', 'State'], ['usualMandi', 'Usual mandi']]} />
        </Card>

        <Card title="What they sell">
          <dl className="divide-y divide-line">
            <div className="py-2 first:pt-0">
              <dt className="text-sm text-muted">Crops</dt>
              {farmer.cropFacts.length === 0 ? (
                <dd className="text-faint italic">{NOT_TOLD}</dd>
              ) : (
                <dd className="mt-1 flex flex-wrap gap-1.5">
                  {farmer.cropFacts.map((c) => (
                    <span key={c.name} className="inline-flex items-center gap-1.5 rounded-full border border-line bg-surface py-0.5 pr-1 pl-2.5 font-semibold capitalize">
                      {c.name} <Provenance origin={c.source} />
                    </span>
                  ))}
                </dd>
              )}
            </div>
            <Field label="Typical quantity" fact={farmer.profile.typicalQuantity} />
          </dl>
        </Card>

        <Card title="How they work">
          <Fields farmer={farmer} rows={[['transport', 'Transport', (v) => v[0].toUpperCase() + v.slice(1)], ['paymentPreference', 'Payment preference', (v) => (v === 'UPI' ? v : v[0].toUpperCase() + v.slice(1))], ['storageAccess', 'Cold storage', (v) => v[0].toUpperCase() + v.slice(1)]]} />
        </Card>

        <Card title="Deals">
          {checking ? (
            <Skeleton className="h-16 w-full" />
          ) : deals.length === 0 ? (
            <p className="text-faint italic">No recorded deals yet.</p>
          ) : (
            <ul className="space-y-2">{deals.map((d) => <DealItem key={d.id} deal={d} />)}</ul>
          )}
        </Card>

        <Card title="Calls" note="Each line holds G-One's short note, not a transcript.">
          {farmer.gists.length === 0 ? (
            <p className="text-faint italic">No calls saved yet.</p>
          ) : (
            <ol className="divide-y divide-line">
              {farmer.gists.map((g) => {
                const outcome = callOutcome(g.callId, deals)
                const note = englishText(g.summary)
                return (
                  <li key={g.callId} className="py-2.5 first:pt-0 last:pb-0">
                    <p className="flex flex-wrap items-center gap-x-2 gap-y-1">
                      <span className="font-semibold tabular-nums">{shortDate(g.startedMs)}</span>
                      {g.endedMs > g.startedMs && <span className="text-sm text-muted tabular-nums">{duration(g.endedMs - g.startedMs)}</span>}
                      <span className={`badge ${outcome.tone === 'ok' ? 'badge-ok' : outcome.tone === 'warn' ? 'badge-warn' : ''}`}>{outcome.label}</span>
                    </p>
                    {note && <p className="mt-0.5 line-clamp-2 text-sm text-muted"><span className="font-semibold">Short note:</span> {note}</p>}
                  </li>
                )
              })}
            </ol>
          )}
        </Card>

        <Card title="Follow-ups">
          {followUps.length === 0 ? (
            <p className="text-faint italic">Nothing open.</p>
          ) : (
            <ul className="space-y-1.5">
              {followUps.map((f) => <li key={f} className="flex items-start gap-2"><span aria-hidden="true" className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-brand" />{englishText(f) ?? f}</li>)}
            </ul>
          )}
        </Card>
      </div>

      <footer className="border-t border-line p-4 sm:p-5">
        <button type="button" className="btn-quiet w-full text-bad" onClick={onForget}><TrashIcon /> Forget this farmer</button>
        <p className="mt-2 text-sm text-faint">Removes their facts and call summaries from G-One's memory.</p>
      </footer>
    </div>
  )
}

/** The deals made on a memory farmer's newest calls, from their receipts. */
async function loadDeals(farmer: Farmer): Promise<FarmerDeal[]> {
  const calls = farmer.gists.slice(0, 6).map((g) => g.callId)
  const rows = await Promise.all(calls.map((id) => api(`/api/deals/${encodeURIComponent(id)}/receipt`).then((body) => parseReceipt(body, id)).catch(() => null)))
  return rows.filter((d): d is FarmerDeal => d !== null)
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
      const list = [...seeded, ...rows.filter((f): f is Farmer => f !== null)].sort((a, b) => b.lastCallMs - a.lastCallMs)
      // A sample caller is not in the store and has no receipts; a memory farmer's deals come from their receipts.
      setFarmers(list.map((f) => (f.origin === 'memory' || f.deals ? f : { ...f, deals: [] })))
      const withDeals = await Promise.all(list.map(async (f) => (f.origin === 'memory' && !f.deals ? { ...f, deals: await loadDeals(f) } : f)))
      if (!cancelled) setFarmers((current) => (current ? withDeals.filter((f) => current.some((c) => c.callerKey === f.callerKey)) : current))
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
      const result = target.origin === 'memory' ? await forgetCaller(target.callerKey) : 'done'
      forgetSeen(target.callerKey)
      setFarmers((list) => list?.filter((f) => f.callerKey !== target.callerKey) ?? null)
      setForgetting(null)
      setNotice(forgetNotice(target.name ?? maskedKey(target.callerKey), result))
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
          <p className="mt-3 text-lg text-muted">Open a farmer to see their profile, what they sell, how they work, their deals and calls. Each fact says whether the farmer told G-One or G-One inferred it. Memory sits against a hash of the number, and any farmer can be forgotten.</p>
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
          <div aria-hidden="true" className="hidden grid-cols-[2.75rem_minmax(0,1.4fr)_minmax(0,0.8fr)_minmax(0,1.3fr)_5.5rem_3.5rem_5.5rem_1.25rem] gap-x-3 border-b border-line px-6 py-2 text-sm font-semibold text-muted lg:grid">
            <span /><span>Farmer</span><span>Language</span><span>Crops</span><span>Last call</span><span>Deals</span><span>Follow-ups</span><span />
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
              {shown.map((f) => <FarmerRow key={f.callerKey} farmer={f} />)}
            </ul>
          )}
        </section>
      </div>

      <Drawer open={Boolean(open)} onClose={closeDetail} label={open?.name ?? 'Farmer details'}>
        {open && <FarmerDetail farmer={open} deals={open.deals ?? []} checking={open.deals === undefined} onClose={closeDetail} onForget={() => { setForgetFailed(false); setForgetting(open) }} />}
        <Dialog open={forgetting !== null} onClose={() => setForgetting(null)} title="Forget this farmer?">
          <div className="space-y-4">
            <p className="flex items-start gap-3">
              <AlertIcon className="mt-0.5 text-bad" />
              <span>G-One will lose <strong className="font-semibold">{forgetting?.name ?? 'this caller'}</strong>'s facts and {forgetting?.gists.length ?? 0} call {forgetting?.gists.length === 1 ? 'summary' : 'summaries'}. If they call again, they start from zero. This cannot be undone.</span>
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
