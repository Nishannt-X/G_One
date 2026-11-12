import { useEffect, useState } from 'react'
import { api, ApiError } from '../lib/api'
import { filterBuyers, parseBuyers, uniqueSorted, type Buyer, type BuyerFilter, type BuyerSort } from '../lib/buyers'
import { rupees } from '../lib/format'
import { useI18n } from '../lib/i18n'
import { BuyersIcon, SearchIcon } from '../ui/icons'
import { Card, Empty, ErrorNote, Skeleton } from '../ui/parts'

export default function BuyersView() {
  const { t } = useI18n()
  const [buyers, setBuyers] = useState<Buyer[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState<BuyerFilter>({
    search: '',
    crop: '',
    district: '',
    sort: 'name',
  })
  const [reloadToken, setReloadToken] = useState(0)

  useEffect(() => {
    let cancelled = false
    api('/api/buyers')
      .then((data) => {
        if (!cancelled) {
          setBuyers(parseBuyers(data))
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
  }, [reloadToken])

  const filtered = filterBuyers(buyers, filter)
  const allCrops = uniqueSorted(buyers.flatMap((b) => b.crops))
  const allDistricts = uniqueSorted(buyers.map((b) => b.district))

  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold md:text-3xl">{t('buyers_title')}</h1>
          <p className="text-sm text-muted">Every buyer, name and price range here is invented for this demo. These are not real traders or market quotes.</p>
        </div>
        <a href="#/call" className="btn-quiet">
          <BuyersIcon /> {t('open_simulator')}
        </a>
      </header>

      {error && (
        <ErrorNote
          title={t(error === 'unreachable' ? 'backend_unreachable' : 'sample_failed')}
          onRetry={() => {
            setLoading(true)
            setReloadToken((k) => k + 1)
          }}
        >
          {error === 'unreachable' ? t('backend_unreachable_fix') : 'Could not fetch buyer directory.'}
        </ErrorNote>
      )}

      {/* Filter Toolbar */}
      <section className="card p-3 md:p-4">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <div className="relative">
            <span className="pointer-events-none absolute inset-y-0 left-3 grid place-items-center text-muted">
              <SearchIcon />
            </span>
            <input
              type="text"
              className="field pl-9"
              placeholder={t('buyers_search')}
              value={filter.search}
              onChange={(e) => setFilter((f) => ({ ...f, search: e.target.value }))}
            />
          </div>

          <div>
            <label className="sr-only">{t('buyers_crop')}</label>
            <select
              className="field"
              value={filter.crop}
              onChange={(e) => setFilter((f) => ({ ...f, crop: e.target.value }))}
            >
              <option value="">{t('buyers_crop')}</option>
              {allCrops.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="sr-only">{t('buyers_district')}</label>
            <select
              className="field"
              value={filter.district}
              onChange={(e) => setFilter((f) => ({ ...f, district: e.target.value }))}
            >
              <option value="">{t('buyers_district')}</option>
              {allDistricts.map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="sr-only">{t('buyers_sort')}</label>
            <select
              className="field"
              value={filter.sort}
              onChange={(e) => setFilter((f) => ({ ...f, sort: e.target.value as BuyerSort }))}
            >
              <option value="name">Sort: Name (A-Z)</option>
              <option value="price_high">Sort: Price (Highest)</option>
              <option value="price_low">Sort: Price (Lowest)</option>
            </select>
          </div>
        </div>
      </section>

      {/* Buyer Cards Grid */}
      {loading ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {[1, 2, 3, 4, 5, 6].map((i) => (
            <Card key={i} className="space-y-3">
              <Skeleton className="h-6 w-3/4" />
              <Skeleton className="h-4 w-1/2" />
              <div className="flex gap-2">
                <Skeleton className="h-6 w-16" />
                <Skeleton className="h-6 w-16" />
              </div>
            </Card>
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <Card>
          <Empty title={t('buyers_empty')} icon={<BuyersIcon />}>
            Try adjusting your search query or filters.
          </Empty>
        </Card>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {filtered.map((b) => (
            <Card key={b.id} className="flex flex-col justify-between">
              <div>
                <header className="flex items-start justify-between gap-2">
                  <h2 className="text-lg font-bold">{b.name} <span className="badge badge-warn align-middle text-xs">Synthetic</span></h2>
                  {b.district && (
                    <span className="chip bg-sunk text-muted">{b.district}</span>
                  )}
                </header>

                <p className="mt-1 text-sm text-muted">
                  Language: {b.language || 'Hindi / English'}
                </p>

                <div className="mt-3">
                  <p className="text-xs font-semibold text-muted">Crops Wanted</p>
                  <div className="mt-1 flex flex-wrap gap-1.5">
                    {b.crops.map((crop) => (
                      <span key={crop} className="chip bg-farmer-soft text-farmer">
                        {crop}
                      </span>
                    ))}
                  </div>
                </div>
              </div>

              <div className="mt-4 border-t border-line pt-3">
                <p className="text-xs font-semibold text-muted">Invented price range</p>
                <p className="text-lg font-bold">
                  {b.priceMin != null && b.priceMax != null
                    ? `${rupees(b.priceMin)} – ${rupees(b.priceMax)} / ${b.unit}`
                    : b.priceMin != null
                      ? `From ${rupees(b.priceMin)} / ${b.unit}`
                      : b.priceMax != null
                        ? `Up to ${rupees(b.priceMax)} / ${b.unit}`
                        : 'On inquiry'}
                </p>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
