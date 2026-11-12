import { useEffect, useState } from 'react'
import { api, ApiError } from '../lib/api'
import { humanize, shortDate } from '../lib/format'
import { useI18n } from '../lib/i18n'
import { isCallerKey, parseMemory, type MemoryRecord } from '../lib/memory'
import { AlertIcon, CheckIcon, TrashIcon, WheatIcon } from '../ui/icons'
import { Card, Dialog, Empty, ErrorNote, Skeleton } from '../ui/parts'
import { ProvenanceChip } from './ProvenanceChip'

export default function MemoryView({ initialKey }: { initialKey?: string | null }) {
  const { t, lang } = useI18n()
  const [callerKey, setCallerKey] = useState(initialKey ?? '')
  const [activeKey, setActiveKey] = useState(initialKey ?? '')
  const [memory, setMemory] = useState<MemoryRecord | null>(null)
  const [loading, setLoading] = useState(Boolean(initialKey))
  const [error, setError] = useState<string | null>(null)
  const [confirmForget, setConfirmForget] = useState(false)
  const [forgetting, setForgetting] = useState(false)
  const [forgetSuccess, setForgetSuccess] = useState(false)

  useEffect(() => {
    if (!initialKey) return
    let cancelled = false
    api(`/api/memory/${encodeURIComponent(initialKey.trim())}`)
      .then((data) => {
        if (!cancelled) {
          setMemory(parseMemory(data, initialKey.trim()))
          setActiveKey(initialKey.trim())
          setCallerKey(initialKey.trim())
          setError(null)
          setLoading(false)
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof ApiError ? err.failure : 'unreachable')
          setLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [initialKey])

  const load = async (key: string) => {
    if (!key.trim()) return
    setLoading(true)
    setError(null)
    setForgetSuccess(false)
    try {
      const data = await api(`/api/memory/${encodeURIComponent(key.trim())}`)
      setMemory(parseMemory(data, key.trim()))
      setActiveKey(key.trim())
    } catch (err) {
      setError(err instanceof ApiError ? err.failure : 'unreachable')
    } finally {
      setLoading(false)
    }
  }

  const handleForget = async () => {
    if (!activeKey) return
    setForgetting(true)
    try {
      await api(`/api/memory/${encodeURIComponent(activeKey)}`, { method: 'DELETE' })
      setConfirmForget(false)
      setForgetSuccess(true)
      setMemory((prev) => (prev ? { ...prev, known: false, facts: [], gists: [] } : null))
    } catch (err) {
      setError(err instanceof ApiError ? err.failure : 'unreachable')
    } finally {
      setForgetting(false)
    }
  }

  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold md:text-3xl">{t('memory_title')}</h1>
          <p className="text-sm text-muted">Browse farmers this browser has seen by name, crop or district. Use a caller key here when you already have one.</p>
        </div>
        <a href="#/farmers" className="btn-primary">Browse farmer memory</a>
      </header>

      {/* Caller Key Input Form */}
      <section className="card p-3 md:p-4">
        <form
          className="flex flex-wrap items-center gap-3"
          onSubmit={(e) => {
            e.preventDefault()
            if (callerKey.trim()) void load(callerKey.trim())
          }}
        >
          <div className="min-w-64 flex-1">
            <label htmlFor="caller-key-input" className="sr-only">
              {t('memory_search')}
            </label>
            <input
              id="caller-key-input"
              type="text"
              className="field font-mono text-sm"
              placeholder={t('memory_search')}
              value={callerKey}
              onChange={(e) => setCallerKey(e.target.value)}
            />
          </div>
          <button type="submit" className="btn-primary" disabled={loading || !callerKey.trim()}>
            {loading ? 'Loading…' : 'Look Up Profile'}
          </button>
        </form>
        {callerKey && !isCallerKey(callerKey) && (
          <p className="mt-2 text-xs text-muted">
            Note: Standard caller key is 64 hex characters (SHA-256 of synthetic phone number).
          </p>
        )}
      </section>

      {error && (
        <ErrorNote
          title={t(error === 'unreachable' ? 'backend_unreachable' : 'sample_failed')}
          onRetry={() => void load(callerKey || activeKey)}
        >
          {error === 'unreachable' ? t('backend_unreachable_fix') : 'Could not fetch caller memory.'}
        </ErrorNote>
      )}

      {forgetSuccess && (
        <div role="status" className="flex items-center gap-2 rounded-xl bg-ok-soft p-4 font-semibold text-ok">
          <CheckIcon /> {t('memory_forgotten')}
        </div>
      )}

      {loading ? (
        <Card className="space-y-4">
          <Skeleton className="h-8 w-1/3" />
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </Card>
      ) : memory ? (
        <div className="space-y-4">
          {/* Header Card */}
          <Card
            title={<span className="font-mono text-base break-all">{memory.callerKey}</span>}
            aside={
              <div className="flex flex-wrap items-center gap-2">
                <span className={`chip ${memory.known ? 'bg-ok-soft text-ok' : 'bg-sunk text-muted'}`}>
                  {t(memory.known ? 'memory_returning' : 'memory_first')}
                </span>
                {memory.known && (
                  <button
                    type="button"
                    onClick={() => setConfirmForget(true)}
                    className="btn-danger text-sm"
                  >
                    <TrashIcon /> {t('memory_forget')}
                  </button>
                )}
              </div>
            }
          >
            {memory.facts.length === 0 && memory.gists.length === 0 ? (
              <Empty title={t('memory_empty')} icon={<WheatIcon />}>
                {t('memory_nothing_yet')}
              </Empty>
            ) : (
              <div className="grid gap-6 lg:grid-cols-2">
                {/* Profile Facts */}
                <div className="space-y-3">
                  <h2 className="text-lg font-bold">Known Farmer Facts</h2>
                  {memory.facts.length === 0 ? (
                    <p className="text-sm text-muted">No profile facts recorded.</p>
                  ) : (
                    <ul className="divide-y divide-line rounded-xl border border-line bg-sunk/30">
                      {memory.facts.map((fact, idx) => (
                        <li key={`${fact.field}-${idx}`} className="flex items-center justify-between p-3">
                          <div>
                            <p className="text-xs font-semibold text-muted capitalize">{humanize(fact.field)}</p>
                            <p className="text-base font-bold">{fact.value}</p>
                          </div>
                          <ProvenanceChip origin={fact.source} />
                        </li>
                      ))}
                    </ul>
                  )}
                </div>

                {/* Call Gists Timeline */}
                <div className="space-y-3">
                  <h2 className="text-lg font-bold">Past Call Summaries</h2>
                  {memory.gists.length === 0 ? (
                    <p className="text-sm text-muted">No call gists recorded.</p>
                  ) : (
                    <ol className="space-y-3">
                      {memory.gists.map((gist) => (
                        <li key={gist.callId} className="card p-3 space-y-2">
                          <div className="flex items-center justify-between text-xs text-muted">
                            <span className="font-semibold">{shortDate(gist.startedMs, lang)}</span>
                            <span className="font-mono">{gist.callId.slice(0, 16)}…</span>
                          </div>
                          <p className="text-base">{gist.summary}</p>
                          {gist.followUps.length > 0 && (
                            <div className="mt-2 border-t border-line pt-2">
                              <p className="text-xs font-semibold text-muted">Follow-ups</p>
                              <ul className="mt-1 list-disc pl-5 text-sm text-muted">
                                {gist.followUps.map((item, idx) => (
                                  <li key={idx}>{item}</li>
                                ))}
                              </ul>
                            </div>
                          )}
                        </li>
                      ))}
                    </ol>
                  )}
                </div>
              </div>
            )}
          </Card>
        </div>
      ) : (
        <Card>
          <Empty title="Look Up Caller Memory" icon={<WheatIcon />}>
            Enter a caller key above or click "View memory profile" from an active call to see stored facts and past call history.
          </Empty>
        </Card>
      )}

      {/* Forget Confirmation Modal */}
      <Dialog
        open={confirmForget}
        onClose={() => setConfirmForget(false)}
        title={t('memory_forget')}
      >
        <div className="space-y-4">
          <div className="flex items-start gap-3 text-bad">
            <AlertIcon className="text-2xl" />
            <p className="text-base text-ink">{t('memory_forget_confirm')}</p>
          </div>
          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              className="btn-quiet"
              onClick={() => setConfirmForget(false)}
              disabled={forgetting}
            >
              Cancel
            </button>
            <button
              type="button"
              className="btn-danger"
              onClick={() => void handleForget()}
              disabled={forgetting}
            >
              {forgetting ? 'Forgetting…' : t('memory_forget')}
            </button>
          </div>
        </div>
      </Dialog>
    </div>
  )
}
