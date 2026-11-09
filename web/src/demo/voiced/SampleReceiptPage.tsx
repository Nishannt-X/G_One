import { useEffect, useState } from 'react'
import { rupees } from '../../lib/format'
import { MANIFEST_BASE, type Manifest } from './manifest'
import { sampleReceipt, type SampleReceipt } from './sampleReceipt'

function Item({ hi, en, value }: { hi: string; en: string; value: string | null }) {
  return (
    <div className="min-w-0 rounded-xl bg-sunk px-3 py-2">
      <dt className="text-sm font-medium text-muted"><span lang="hi">{hi}</span> / {en}</dt>
      <dd className="text-lg leading-snug font-semibold break-words">{value ?? '—'}</dd>
    </div>
  )
}

export function ReceiptCard({ receipt }: { receipt: SampleReceipt }) {
  const agreed = (yes: boolean) => (yes ? 'Yes' : 'Not yet')
  return (
    <article className="card w-full max-w-[40rem] p-5 sm:p-6">
      <header className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-2xl font-semibold"><span lang="hi">सौदा पर्ची</span> / Sauda Parchi</h1>
        <span className="badge badge-warn">Scripted sample, synthetic</span>
      </header>
      <div className="mt-4 rounded-xl bg-ok-soft p-4 text-center">
        <p className="text-sm font-semibold text-ok"><span lang="hi">कुल राशि</span> / Total</p>
        <p className="text-3xl font-bold tabular-nums">{receipt.total !== null ? rupees(receipt.total) : '—'}</p>
        <p className="text-base font-medium">{receipt.quantity} × {receipt.price !== null ? rupees(receipt.price) : '—'} / {receipt.unit}</p>
      </div>
      <dl className="mt-3 grid grid-cols-2 gap-2">
        <Item hi="किसान" en="Farmer" value={receipt.farmer} />
        <Item hi="खरीदार" en="Buyer" value={receipt.buyer} />
        <Item hi="फसल" en="Crop" value={receipt.commodity} />
        <Item hi="मात्रा" en="Quantity" value={receipt.quantity} />
        <Item hi="मंडी" en="Market" value={receipt.market} />
        <Item hi="दिनांक" en="Date" value={receipt.date} />
        <Item hi="किसान की हाँ" en="Farmer agreed" value={agreed(receipt.farmerYes)} />
        <Item hi="खरीदार की हाँ" en="Buyer agreed" value={agreed(receipt.buyerYes)} />
      </dl>
      <div className="mt-2"><Item hi="माल उठाव" en="Pickup and payment" value={receipt.pickup} /></div>
      <p className="mt-4 text-sm text-muted">The receipt for the scripted sample conversation. The people and the deal are made up, nothing was sent, and this is not a contract.</p>
    </article>
  )
}

/** The sample's own receipt, opened from its phone mock-up. It reads only the sample's manifest, never the server's receipts. */
export default function SampleReceiptPage() {
  const [receipt, setReceipt] = useState<SampleReceipt | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let live = true
    fetch(`${MANIFEST_BASE}manifest.json`)
      .then((r) => (r.ok ? (r.json() as Promise<Manifest>) : Promise.reject(new Error(String(r.status)))))
      .then((manifest) => {
        if (!live) return
        const built = sampleReceipt(manifest)
        if (built) setReceipt(built)
        else setFailed(true)
      })
      .catch(() => {
        if (live) setFailed(true)
      })
    return () => {
      live = false
    }
  }, [])
  return (
    <main className="flex min-h-dvh items-start justify-center p-4 sm:p-6">
      {receipt ? <ReceiptCard receipt={receipt} /> : failed ? <p role="alert" className="font-medium text-bad">The sample receipt could not be loaded.</p> : <p className="text-muted">Loading…</p>}
    </main>
  )
}
