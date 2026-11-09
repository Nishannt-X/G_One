import { dealValue } from '../../lib/pricing'
import type { Manifest } from './manifest'
import { callViewAt } from './timeline'

/** A hash route, so opening the sample's receipt needs nothing from the server and can never show a real deal. */
export const SAMPLE_RECEIPT_HREF = '#/sample-receipt'

export interface SampleReceipt {
  farmer: string
  buyer: string
  commodity: string | null
  quantity: string | null
  price: number | null
  unit: string | null
  total: number | null
  market: string | null
  pickup: string | null
  date: string | null
  farmerYes: boolean
  buyerYes: boolean
}

/** The deal the sample ends on, built from the same beats the deal panel shows. Null if the script never reaches one. */
export function sampleReceipt(manifest: Manifest): SampleReceipt | null {
  const deal = callViewAt(manifest, manifest.beats.length - 1, 0, true).deal
  if (!deal) return null
  return {
    farmer: manifest.farmer.name,
    buyer: manifest.buyer.name,
    commodity: deal.commodity,
    quantity: deal.quantity,
    price: deal.price,
    unit: deal.unit,
    total: dealValue(deal.quantity, deal.price, deal.unit),
    market: deal.market,
    pickup: deal.pickup,
    date: manifest.phones.farmer.rows.find((row) => row.label === 'Date')?.value ?? null,
    farmerYes: deal.farmerYes,
    buyerYes: deal.buyerYes,
  }
}
