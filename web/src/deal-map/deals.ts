/**
 * Synthetic example deals for the deal map. Made-up people, businesses, slips and payments: not real transactions,
 * transporters or payments. Town positions are approximate; road distances, freight and times are rough estimates.
 */
import { freightRupees } from './freight'
import type { LonLat } from './geo'
import type { LanguageCode } from './languages'

export type DealStatus = 'delivered-paid' | 'in-transit' | 'awaiting-pickup' | 'cold-storage' | 'delayed' | 'payment-pending' | 'part-rejected'
export type Tone = 'ok' | 'info' | 'warn' | 'bad'

export const STATUS: Record<DealStatus, { label: string; tone: Tone }> = {
  'delivered-paid': { label: 'Delivered, paid', tone: 'ok' },
  'in-transit': { label: 'In transit', tone: 'info' },
  'awaiting-pickup': { label: 'Awaiting pickup', tone: 'info' },
  'cold-storage': { label: 'Held in cold storage', tone: 'info' },
  delayed: { label: 'Delayed', tone: 'warn' },
  'payment-pending': { label: 'Delivered, payment pending', tone: 'warn' },
  'part-rejected': { label: 'Part rejected', tone: 'bad' },
}

export type StopKind = 'farm' | 'weighbridge' | 'hub' | 'cold-storage' | 'toll' | 'roadside' | 'mandi' | 'godown'

export const STOP_KIND: Record<StopKind, string> = {
  farm: 'Farm pickup',
  weighbridge: 'Weighbridge',
  hub: 'Transporter hub, driver changeover',
  'cold-storage': 'Cold storage',
  toll: 'Toll plaza',
  roadside: 'Unplanned stop',
  mandi: 'Mandi yard',
  godown: 'Buyer godown',
}

export type Payer = 'farmer' | 'buyer' | 'transporter' | 'split'
export const PAYER: Record<Payer, string> = { farmer: 'farmer', buyer: 'buyer', transporter: 'transporter', split: 'farmer and buyer, half each' }

export interface Cost {
  label: string
  rupees: number
  paidBy: Payer
  /** Storage fees add up into the journey summary. */
  storage?: boolean
  /** Already inside the quoted freight, so not added again. */
  inFreight?: boolean
}

export interface Difficulty {
  cause: string
  delayHours: number
  /** Who was told, and how. */
  told: string
  resolution: string
  costImpact: string
}

export interface Record_ {
  item: string
  /** Whose record it is. */
  heldBy: string
}

export interface Stop {
  kind: StopKind
  /** Full place name for the detail list. */
  place: string
  /** Short name for the map label. */
  short: string
  at: LonLat
  /** Road points from the previous stop to this one, so the line follows the highway roughly. */
  via?: readonly LonLat[]
  /** Road distance from the previous stop, a rough estimate. */
  km: number
  /** Local time, IST, as YYYY-MM-DDTHH:mm. */
  arrive?: string
  depart?: string
  /** Not reached yet: the times are estimates. */
  planned?: boolean
  handler: { role: string; name: string }
  handoff: string
  records: readonly Record_[]
  difficulty?: Difficulty
  costs?: readonly Cost[]
  /** The deal's status once this stop is done; used when a demo deal plays. */
  statusAfter?: DealStatus
}

export type PaymentState = 'paid' | 'part-paid' | 'pending' | 'not-due'
export const PAYMENT: Record<PaymentState, string> = { paid: 'Paid', 'part-paid': 'Part paid', pending: 'Pending', 'not-due': 'Not due yet' }

export interface Deal {
  id: string
  /** Short origin and destination names for the list. */
  lane: { from: string; to: string }
  /** A demo deal can be played step by step on the map. */
  demo?: boolean
  crop: string
  quantity: { amount: number; unit: 'quintal' | 'crate'; /** Load weight, for freight. */ quintals: number }
  price: { rupees: number; per: 'quintal' | 'crate'; basis: string }
  farmer: { name: string; village: string; language: LanguageCode }
  buyer: { business: string; contact: string; city: string; language: LanguageCode }
  call: { at: string; minutes: number; note: string }
  status: DealStatus
  freight: { rupees: number; paidBy: Payer; vehicle: string }
  loss?: { quintals: number; cause: string; borneBy: Payer }
  payment: { state: PaymentState; method: string; rupees: number; note: string }
  /** For a truck on the road: the stop it is heading to and how far along that leg it is. */
  truck?: { leg: number; fraction: number }
  stops: readonly Stop[]
}

/** What G-One keeps from the deal call, for every deal: terms and operational data only. */
export const G_ONE_RECORDS: readonly Record_[] = [
  { item: 'Deal card: crop, quantity, price and its basis, payment terms', heldBy: 'G-One' },
  { item: 'Call log: time, length and languages of the call', heldBy: 'G-One' },
]
export const G_ONE_NOT_STORED = 'No transcript, no audio and no phone numbers are stored.'
const CALL_LOG = (who: string): Record_ => ({ item: `Call log of ${who}'s call to G-One: time, length, language`, heldBy: 'G-One' })

export const rupees = (n: number) => `₹${Math.round(n).toLocaleString('en-IN')}`

export const totalOf = (d: Deal) => d.quantity.amount * d.price.rupees
export const kmOf = (d: Deal) => d.stops.reduce((sum, s) => sum + s.km, 0)
export const storageFeeOf = (d: Deal) => d.stops.flatMap((s) => s.costs ?? []).filter((c) => c.storage).reduce((sum, c) => sum + c.rupees, 0)
export const difficultiesOf = (d: Deal) => d.stops.filter((s) => s.difficulty).length
export const originOf = (d: Deal) => d.stops[0]
export const destinationOf = (d: Deal) => d.stops[d.stops.length - 1]

/** Minutes since 2026-01-01 for a YYYY-MM-DDTHH:mm stamp. Plain arithmetic, so no time zone can shift it. */
export function minutesOf(stamp: string): number {
  const [date, time] = stamp.split('T')
  const [, m, d] = date.split('-').map(Number)
  const [hh, mm] = time.split(':').map(Number)
  const monthStart = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334]
  return ((monthStart[m - 1] + d - 1) * 24 + hh) * 60 + mm
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
/** "3 Nov, 07:00" */
export function formatStamp(stamp: string): string {
  const [date, time] = stamp.split('T')
  const [, m, d] = date.split('-').map(Number)
  return `${d} ${MONTHS[m - 1]}, ${time}`
}

/** Hours from the first departure to the last recorded arrival or departure. */
export function journeyHours(d: Deal): number | null {
  const first = d.stops[0].depart ?? d.stops[0].arrive
  const done = d.stops.filter((s) => !s.planned)
  const lastStop = done[done.length - 1]
  const last = lastStop?.depart ?? lastStop?.arrive
  if (!first || !last || done.length < 2) return null
  return Math.round((minutesOf(last) - minutesOf(first)) / 60)
}

const freightFor = (km: number, quintals: number) => freightRupees(km, quintals)
const legs = (stops: readonly Stop[]) => stops.reduce((sum, s) => sum + s.km, 0)

function deal(d: Omit<Deal, 'freight'> & { freight: Omit<Deal['freight'], 'rupees'> }): Deal {
  return { ...d, freight: { ...d.freight, rupees: freightFor(legs(d.stops), d.quantity.quintals) } }
}

const WEIGHT_SLIP = (no: string, who: string): Record_ => ({ item: `Weight slip ${no}`, heldBy: `${who}; copies with the farmer and the driver` })
const LORRY_RECEIPT = (no: string, who: string): Record_ => ({ item: `Lorry receipt ${no}`, heldBy: who })

const onion = deal({
  id: 'onion-gadag-kolkata',
  lane: { from: 'Gadag', to: 'Kolkata' },
  demo: true,
  crop: 'Onion',
  quantity: { amount: 40, unit: 'quintal', quintals: 40 },
  price: { rupees: 1900, per: 'quintal', basis: "per quintal at the farm gate, graded, buyer's truck, up to 1% road shrinkage accepted, pay on delivery" },
  farmer: { name: 'Basavaraj', village: 'a village near Gadag, Karnataka', language: 'kn' },
  buyer: { business: 'Mondal Traders', contact: 'Subrata', city: 'Kolkata', language: 'bn' },
  call: { at: '2026-11-02T09:11', minutes: 11, note: 'Basavaraj spoke Kannada, Subrata spoke Bengali and switched to Hindi for the numbers. G-One translated both ways.' },
  status: 'delivered-paid',
  freight: { paidBy: 'buyer', vehicle: 'Shared truck, part load' },
  loss: { quintals: 0.35, cause: 'Road shrinkage (moisture loss), inside the 1% both sides accepted', borneBy: 'buyer' },
  payment: { state: 'paid', method: 'UPI to the farmer, simulated', rupees: 76_000, note: 'Paid on delivery after the weighment at the mandi weighbridge.' },
  stops: [
    {
      kind: 'farm', place: 'Farm, a village near Gadag', short: 'Farm near Gadag', at: [75.72, 15.47], km: 0, arrive: '2026-11-03T06:40', depart: '2026-11-03T07:50',
      handler: { role: 'Driver', name: 'Imran, Tungabhadra Roadlines' },
      handoff: 'Farmer and driver counted 80 bags of 50 kg together; the driver signed the farmer’s bag count.',
      records: [{ item: 'Bag count, signed by the driver', heldBy: 'Farmer’s copy; the driver keeps the other' }],
      difficulty: { cause: 'The truck was late from its previous load, so the pickup slipped from 2 Nov afternoon to 3 Nov morning.', delayHours: 15, told: 'The transporter told G-One on a call; G-One called Basavaraj in Kannada and Subrata in Bengali.', resolution: 'G-One mentioned a cold store near Gadag as an option. Basavaraj kept the onion in his shaded shed overnight instead.', costImpact: 'None. No storage was used.' },
      statusAfter: 'in-transit',
    },
    {
      kind: 'weighbridge', place: 'Weighbridge on the Gadag APMC road', short: 'Gadag weighbridge', at: [75.62, 15.41], km: 12, arrive: '2026-11-03T08:15', depart: '2026-11-03T08:40',
      handler: { role: 'Weighbridge operator', name: 'Ravi, Shree Veerabhadra Weighbridge' },
      handoff: 'Loaded truck weighed: 4,020 kg net of the tare weight. The slip went to both the farmer and the driver.',
      records: [WEIGHT_SLIP('GDG-0412', 'The weighbridge')],
      costs: [{ label: 'Weighing fee', rupees: 150, paidBy: 'farmer' }],
      statusAfter: 'in-transit',
    },
    {
      kind: 'hub', place: 'Transporter hub, Hyderabad outer ring road', short: 'Hyderabad hub', at: [78.38, 17.48],
      via: [[76.15, 15.35], [76.39, 15.27], [76.92, 15.14], [77.55, 15.6], [78.04, 15.83], [78.14, 16.76]], km: 470, arrive: '2026-11-03T19:30', depart: '2026-11-03T20:30',
      handler: { role: 'Hub supervisor', name: 'Narsimha, Tungabhadra Roadlines' },
      handoff: 'Driver changeover: Imran handed the lorry receipt and the sealed tarpaulin to the second driver, Salim, who checked the seal number.',
      records: [LORRY_RECEIPT('TRL-2611-0457', 'The transporter'), { item: 'Seal number on the trip sheet', heldBy: 'The transporter' }],
      statusAfter: 'in-transit',
    },
    {
      kind: 'roadside', place: 'NH 16 near Rajahmundry', short: 'Near Rajahmundry', at: [81.72, 17.03],
      via: [[79.0, 17.25], [79.62, 17.14], [80.65, 16.51], [81.1, 16.75]], km: 420, arrive: '2026-11-04T06:30', depart: '2026-11-04T11:30',
      handler: { role: 'Driver', name: 'Salim, Tungabhadra Roadlines' },
      handoff: 'No hand-off. The load stayed sealed on the truck.',
      records: [CALL_LOG('the driver'), { item: 'Tyre repair bill', heldBy: 'The transporter' }],
      difficulty: { cause: 'A rear tyre burst on the highway.', delayHours: 5, told: 'Salim called G-One; G-One told Subrata in Bengali and Basavaraj in Kannada that the truck would be about 5 hours late.', resolution: 'A tyre shop from the next town fitted a new tyre at the roadside.', costImpact: 'The transporter paid for the tyre. No change to the freight or the deal.' },
      costs: [{ label: 'Tyre and fitting', rupees: 9200, paidBy: 'transporter' }],
      statusAfter: 'delayed',
    },
    {
      kind: 'toll', place: 'Toll plaza on NH 16 near Balasore', short: 'Balasore toll', at: [86.85, 21.42],
      via: [[82.3, 17.3], [83.22, 17.73], [83.9, 18.3], [84.79, 19.31], [85.82, 20.3], [86.4, 20.95]], km: 690, arrive: '2026-11-05T05:00', depart: '2026-11-05T05:10',
      handler: { role: 'Driver', name: 'Salim, Tungabhadra Roadlines' },
      handoff: 'No hand-off. Toll paid by FASTag.',
      records: [{ item: 'FASTag toll entry', heldBy: 'The transporter’s FASTag account' }],
      costs: [{ label: 'Tolls along the route', rupees: 6800, paidBy: 'transporter', inFreight: true }],
      statusAfter: 'in-transit',
    },
    {
      kind: 'mandi', place: 'Mandi weighbridge near Kolkata', short: 'Kolkata mandi', at: [88.2, 22.55],
      via: [[87.32, 22.33], [87.8, 22.45]], km: 220, arrive: '2026-11-05T11:30', depart: '2026-11-05T13:00',
      handler: { role: 'Mandi commission agent', name: 'Tapan, a commission agent at the mandi' },
      handoff: 'Weighed again: 3,985 kg, within the shrinkage both sides accepted on the call. Graded by the commission agent, not by G-One.',
      records: [WEIGHT_SLIP('KOL-M-2290', 'The mandi weighbridge'), { item: 'Grading note', heldBy: 'The commission agent' }],
      costs: [{ label: 'Unloading and weighing at the mandi', rupees: 1200, paidBy: 'buyer' }],
      statusAfter: 'in-transit',
    },
    {
      kind: 'godown', place: 'Mondal Traders godown, Kolkata', short: 'Buyer godown', at: [88.34, 22.62], via: [[88.28, 22.6]], km: 15, arrive: '2026-11-05T13:40', depart: '2026-11-05T15:30',
      handler: { role: 'Godown manager', name: 'Subrata, Mondal Traders' },
      handoff: 'Subrata signed the lorry receipt. G-One read the agreed terms back to both sides on a short call, and Subrata paid.',
      records: [LORRY_RECEIPT('TRL-2611-0457, signed', 'The transporter and the buyer'), CALL_LOG('the buyer')],
      statusAfter: 'delivered-paid',
    },
  ],
})

const tomato = deal({
  id: 'tomato-nashik-chennai',
  lane: { from: 'Nashik', to: 'Chennai' },
  demo: true,
  crop: 'Tomato',
  quantity: { amount: 30, unit: 'quintal', quintals: 30 },
  price: { rupees: 1200, per: 'quintal', basis: 'per quintal, firm red-ripe grade, loaded at the farm, pay on delivery for what is accepted' },
  farmer: { name: 'Sunita Jadhav', village: 'a village near Nashik, Maharashtra', language: 'mr' },
  buyer: { business: 'Selvam Vegetable Traders', contact: 'Selvam', city: 'Chennai', language: 'ta' },
  call: { at: '2026-11-06T07:30', minutes: 10, note: 'Sunita spoke Marathi, Selvam spoke Tamil and moved to English. G-One translated both ways.' },
  status: 'part-rejected',
  freight: { paidBy: 'buyer', vehicle: 'Ventilated truck, part load' },
  loss: { quintals: 2.5, cause: 'Soft and cracked fruit rejected at the Chennai yard', borneBy: 'farmer' },
  payment: { state: 'part-paid', method: 'UPI to the farmer, simulated', rupees: 33_000, note: 'Paid for the 27.5 quintal accepted. The rejected 2.5 quintal was sold at the yard for ₹1,250, paid to the farmer by the commission agent.' },
  stops: [
    {
      kind: 'farm', place: 'Farm, a village near Nashik', short: 'Farm near Nashik', at: [73.83, 20.2], km: 0, arrive: '2026-11-06T11:00', depart: '2026-11-06T12:30',
      handler: { role: 'Driver', name: 'Ganesh, Sahyadri Roadways' },
      handoff: 'Sunita and the driver counted 120 crates of 25 kg together; the driver signed the crate count.',
      records: [{ item: 'Crate count, signed by the driver', heldBy: 'Farmer’s copy; the driver keeps the other' }],
      statusAfter: 'in-transit',
    },
    {
      kind: 'cold-storage', place: 'Pre-cooling cold store near Nashik', short: 'Nashik pre-cooling', at: [73.75, 20.03], via: [[73.8, 20.12]], km: 22, arrive: '2026-11-06T13:10', depart: '2026-11-06T21:00',
      handler: { role: 'Cold store manager', name: 'Kiran, Godavari Pre-cool Centre' },
      handoff: 'Crates checked in by count and pre-cooled for 8 hours, so the fruit travels overnight while it is cool. Checked out against the same count.',
      records: [{ item: 'Storage receipt and temperature log, 8 to 10 °C', heldBy: 'The cold store; a copy with the driver' }],
      costs: [{ label: 'Pre-cooling, 8 hours', rupees: 1800, paidBy: 'split', storage: true }],
      statusAfter: 'in-transit',
    },
    {
      kind: 'toll', place: 'Toll plaza on NH 48 near Kolhapur', short: 'Kolhapur toll', at: [74.3, 16.62],
      via: [[73.99, 19.6], [74.2, 19.0], [73.86, 18.52], [74.0, 17.68], [74.18, 17.2]], km: 470, arrive: '2026-11-07T06:10', depart: '2026-11-07T06:20',
      handler: { role: 'Driver', name: 'Ganesh, Sahyadri Roadways' },
      handoff: 'No hand-off. Toll paid by FASTag.',
      records: [{ item: 'FASTag toll entry', heldBy: 'The transporter’s FASTag account' }],
      costs: [{ label: 'Tolls along the route', rupees: 5400, paidBy: 'transporter', inFreight: true }],
      statusAfter: 'in-transit',
    },
    {
      kind: 'hub', place: 'Transporter hub, Bengaluru outskirts', short: 'Bengaluru hub', at: [77.45, 13.08],
      via: [[74.5, 15.85], [75.12, 15.36], [75.92, 14.46], [76.6, 13.8], [77.1, 13.34]], km: 610, arrive: '2026-11-07T20:30', depart: '2026-11-07T21:15',
      handler: { role: 'Hub supervisor', name: 'Manjunath, Sahyadri Roadways' },
      handoff: 'Driver changeover: Ganesh handed the lorry receipt and the crate count to the second driver, Murugan, who recounted the crates.',
      records: [LORRY_RECEIPT('SRW-2611-1183', 'The transporter')],
      difficulty: { cause: 'Heavy traffic and a road repair diversion before Bengaluru.', delayHours: 4, told: 'Ganesh called G-One; G-One told Selvam in English and Sunita in Marathi.', resolution: 'The truck left Bengaluru after dark, so the fruit ran in the cool night.', costImpact: 'None to the deal; the transporter absorbed the extra hours.' },
      statusAfter: 'in-transit',
    },
    {
      kind: 'mandi', place: 'Wholesale vegetable yard, Chennai', short: 'Chennai yard', at: [80.17, 13.07],
      via: [[78.21, 12.52], [79.13, 12.92], [79.7, 12.98]], km: 340, arrive: '2026-11-08T04:00', depart: '2026-11-08T06:30',
      handler: { role: 'Yard commission agent', name: 'Arumugam, a commission agent at the yard' },
      handoff: 'Unloaded and graded by the commission agent. 10 crates (2.5 quintal) were soft or cracked and rejected; 110 crates passed.',
      records: [{ item: 'Grading note: 110 crates passed, 10 rejected', heldBy: 'The commission agent; photo copy with the driver' }, CALL_LOG('the buyer')],
      difficulty: { cause: 'Part of the load failed the firm red-ripe grade named in the deal.', delayHours: 1, told: 'Selvam called G-One. G-One read the agreed grade back to both sides in Tamil and Marathi; it did not judge the fruit.', resolution: 'Both sides agreed on the call to pay for the 27.5 quintal accepted and to let the agent sell the rejected crates at the yard for the farmer.', costImpact: 'Farmer received ₹33,000 plus ₹1,250 from the rejected crates instead of ₹36,000.' },
      costs: [{ label: 'Unloading and grading at the yard', rupees: 900, paidBy: 'buyer' }],
      statusAfter: 'part-rejected',
    },
    {
      kind: 'godown', place: 'Selvam Vegetable Traders godown, Chennai', short: 'Buyer godown', at: [80.22, 13.11], km: 9, arrive: '2026-11-08T07:00', depart: '2026-11-08T08:10',
      handler: { role: 'Godown supervisor', name: 'Selvam, Selvam Vegetable Traders' },
      handoff: 'Selvam signed the lorry receipt for 110 crates and paid for the accepted quantity.',
      records: [LORRY_RECEIPT('SRW-2611-1183, signed for 110 crates', 'The transporter and the buyer')],
      statusAfter: 'part-rejected',
    },
  ],
})

const chilli = deal({
  id: 'chilli-guntur-delhi',
  lane: { from: 'Guntur', to: 'Delhi' },
  crop: 'Dry red chilli',
  quantity: { amount: 60, unit: 'quintal', quintals: 60 },
  price: { rupees: 14_500, per: 'quintal', basis: 'per quintal, out of the cold store at Guntur, graded, payment within 7 days of delivery' },
  farmer: { name: 'Venkata Rao', village: 'a village near Guntur, Andhra Pradesh', language: 'te' },
  buyer: { business: 'Saini Spice House', contact: 'Harpreet', city: 'Delhi', language: 'hi' },
  call: { at: '2026-10-27T16:05', minutes: 14, note: 'Venkata Rao spoke Telugu and Harpreet spoke Hindi. G-One translated both ways.' },
  status: 'payment-pending',
  freight: { paidBy: 'buyer', vehicle: 'Full truck' },
  payment: { state: 'pending', method: 'Bank transfer, simulated', rupees: 870_000, note: 'Due by 7 Nov, 7 days after delivery as agreed on the call.' },
  stops: [
    {
      kind: 'cold-storage', place: 'Chilli cold store near Guntur', short: 'Guntur cold store', at: [80.4, 16.32], km: 0, arrive: '2026-10-29T08:00', depart: '2026-10-29T11:00',
      handler: { role: 'Cold store clerk', name: 'Lakshmi, Krishna Agro Cold Storage' },
      handoff: 'The chilli had been stored here since harvest. 150 bags released against the farmer’s storage receipt and loaded.',
      records: [{ item: 'Release slip against storage receipt KCS-7731', heldBy: 'The cold store; a copy with the farmer' }],
      costs: [{ label: 'Storage rent, 4 months', rupees: 9000, paidBy: 'farmer', storage: true }],
    },
    {
      kind: 'weighbridge', place: 'Weighbridge, Guntur', short: 'Guntur weighbridge', at: [80.44, 16.28], km: 6, arrive: '2026-10-29T11:20', depart: '2026-10-29T11:45',
      handler: { role: 'Weighbridge operator', name: 'Prasad, Sri Durga Weighbridge' },
      handoff: 'Loaded truck weighed: 6,010 kg net. Slip to the farmer and the driver.',
      records: [WEIGHT_SLIP('GNT-1187', 'The weighbridge')],
      costs: [{ label: 'Weighing fee', rupees: 200, paidBy: 'farmer' }],
    },
    {
      kind: 'hub', place: 'Transporter hub, Nagpur', short: 'Nagpur hub', at: [79.05, 21.1],
      via: [[80.0, 16.9], [79.62, 17.6], [78.47, 17.38], [78.5, 18.3], [78.55, 19.6], [78.9, 20.4]], km: 790, arrive: '2026-10-30T09:00', depart: '2026-10-30T10:00',
      handler: { role: 'Hub supervisor', name: 'Rahul, Deccan Cargo Movers' },
      handoff: 'Driver changeover. The lorry receipt and the bag count passed to the second driver.',
      records: [LORRY_RECEIPT('DCM-2610-3302', 'The transporter')],
    },
    {
      kind: 'toll', place: 'Toll plaza on NH 44 near Agra', short: 'Agra toll', at: [77.95, 27.1],
      via: [[79.0, 22.6], [78.75, 23.8], [78.58, 25.45], [78.18, 26.2]], km: 790, arrive: '2026-10-31T06:30', depart: '2026-10-31T06:40',
      handler: { role: 'Driver', name: 'Vikram, Deccan Cargo Movers' },
      handoff: 'No hand-off. Toll paid by FASTag.',
      records: [{ item: 'FASTag toll entry', heldBy: 'The transporter’s FASTag account' }],
      costs: [{ label: 'Tolls along the route', rupees: 14_500, paidBy: 'transporter', inFreight: true }],
    },
    {
      kind: 'godown', place: 'Saini Spice House godown, Delhi', short: 'Buyer godown', at: [77.18, 28.7], via: [[77.7, 27.6], [77.32, 28.4]], km: 220, arrive: '2026-10-31T13:00', depart: '2026-10-31T16:00',
      handler: { role: 'Godown manager', name: 'Harpreet, Saini Spice House' },
      handoff: 'Weighed on the buyer’s own scale: 6,000 kg. Harpreet signed the lorry receipt.',
      records: [LORRY_RECEIPT('DCM-2610-3302, signed', 'The transporter and the buyer'), { item: 'Buyer’s scale ticket', heldBy: 'The buyer' }],
    },
  ],
})

const potato = deal({
  id: 'potato-agra-mumbai',
  lane: { from: 'Agra', to: 'Mumbai' },
  crop: 'Potato',
  quantity: { amount: 100, unit: 'quintal', quintals: 100 },
  price: { rupees: 1350, per: 'quintal', basis: 'per quintal, out of the cold store at Agra, buyer pays freight, pay on delivery' },
  farmer: { name: 'Ramesh', village: 'a village near Agra, Uttar Pradesh', language: 'hi' },
  buyer: { business: 'Konkan Fresh Supply', contact: 'Sachin', city: 'Bhiwandi, near Mumbai', language: 'mr' },
  call: { at: '2026-11-10T10:20', minutes: 9, note: 'Ramesh spoke Hindi and Sachin spoke Marathi. G-One translated both ways.' },
  status: 'in-transit',
  freight: { paidBy: 'buyer', vehicle: 'Full truck' },
  payment: { state: 'not-due', method: 'UPI to the farmer, simulated', rupees: 135_000, note: 'Due on delivery.' },
  truck: { leg: 3, fraction: 0.55 },
  stops: [
    {
      kind: 'cold-storage', place: 'Potato cold store near Agra', short: 'Agra cold store', at: [78.05, 27.2], km: 0, arrive: '2026-11-12T07:00', depart: '2026-11-12T10:30',
      handler: { role: 'Cold store clerk', name: 'Shyam, Yamuna Cold Storage' },
      handoff: '200 bags of 50 kg released against Ramesh’s storage receipt and loaded under the clerk’s count.',
      records: [{ item: 'Release slip against storage receipt YCS-4410', heldBy: 'The cold store; a copy with the farmer' }],
      costs: [{ label: 'Storage rent, 8 months', rupees: 22_000, paidBy: 'farmer', storage: true }],
    },
    {
      kind: 'weighbridge', place: 'Weighbridge, Agra', short: 'Agra weighbridge', at: [77.98, 27.17], km: 8, arrive: '2026-11-12T10:50', depart: '2026-11-12T11:15',
      handler: { role: 'Weighbridge operator', name: 'Deepak, Taj Road Weighbridge' },
      handoff: 'Loaded truck weighed: 10,040 kg net. Slip to the farmer and the driver.',
      records: [WEIGHT_SLIP('AGR-5520', 'The weighbridge')],
      costs: [{ label: 'Weighing fee', rupees: 200, paidBy: 'farmer' }],
    },
    {
      kind: 'hub', place: 'Transporter hub, Indore bypass', short: 'Indore hub', at: [75.9, 22.68],
      via: [[78.17, 26.22], [77.65, 25.42], [77.0, 24.6], [76.3, 23.6]], km: 600, arrive: '2026-11-13T05:30', depart: '2026-11-13T06:30',
      handler: { role: 'Hub supervisor', name: 'Aslam, Malwa Freight Lines' },
      handoff: 'Driver changeover. Lorry receipt and bag count passed to the second driver.',
      records: [LORRY_RECEIPT('MFL-2611-0912', 'The transporter')],
    },
    {
      kind: 'toll', place: 'Toll plaza on NH 52 near Dhule', short: 'Dhule toll', at: [74.78, 20.92],
      via: [[75.55, 22.2], [75.3, 21.6]], km: 300, arrive: '2026-11-14T13:00', depart: '2026-11-14T13:10', planned: true,
      handler: { role: 'Driver', name: 'Sandeep, Malwa Freight Lines' },
      handoff: 'No hand-off expected. Toll by FASTag.',
      records: [{ item: 'FASTag toll entry', heldBy: 'The transporter’s FASTag account' }],
    },
    {
      kind: 'godown', place: 'Konkan Fresh Supply godown, Bhiwandi', short: 'Buyer godown', at: [73.07, 19.3],
      via: [[74.3, 20.6], [73.79, 20.0], [73.5, 19.6]], km: 330, arrive: '2026-11-15T01:00', planned: true,
      handler: { role: 'Godown manager', name: 'Sachin, Konkan Fresh Supply' },
      handoff: 'Planned: weighment on the buyer’s scale, signed lorry receipt, then payment.',
      records: [LORRY_RECEIPT('MFL-2611-0912', 'The transporter and the buyer, once signed')],
    },
  ],
})

const banana = deal({
  id: 'banana-jalgaon-lucknow',
  lane: { from: 'Jalgaon', to: 'Lucknow' },
  crop: 'Banana',
  quantity: { amount: 80, unit: 'quintal', quintals: 80 },
  price: { rupees: 1600, per: 'quintal', basis: 'per quintal, green mature bunches loaded at the farm, pay on delivery' },
  farmer: { name: 'Prakash Patil', village: 'a village near Jalgaon, Maharashtra', language: 'mr' },
  buyer: { business: 'Awadh Fruit Company', contact: 'Imtiaz', city: 'Lucknow', language: 'hi' },
  call: { at: '2026-11-11T08:45', minutes: 12, note: 'Prakash spoke Marathi and Imtiaz spoke Hindi. G-One translated both ways.' },
  status: 'delayed',
  freight: { paidBy: 'buyer', vehicle: 'Full truck, ventilated' },
  payment: { state: 'not-due', method: 'Bank transfer, simulated', rupees: 128_000, note: 'Due on delivery.' },
  truck: { leg: 3, fraction: 0.25 },
  stops: [
    {
      kind: 'farm', place: 'Farm, a village near Jalgaon', short: 'Farm near Jalgaon', at: [75.55, 21.05], km: 0, arrive: '2026-11-12T05:30', depart: '2026-11-12T08:00',
      handler: { role: 'Driver', name: 'Rafiq, Satpura Transport' },
      handoff: 'Bunches cut, padded and loaded; the driver signed the bunch count.',
      records: [{ item: 'Bunch count, signed by the driver', heldBy: 'Farmer’s copy; the driver keeps the other' }],
    },
    {
      kind: 'weighbridge', place: 'Weighbridge, Jalgaon', short: 'Jalgaon weighbridge', at: [75.57, 21.0], km: 7, arrive: '2026-11-12T08:20', depart: '2026-11-12T08:45',
      handler: { role: 'Weighbridge operator', name: 'Nitin, Tapi Weighbridge' },
      handoff: 'Loaded truck weighed: 8,060 kg net. Slip to both.',
      records: [WEIGHT_SLIP('JLG-3307', 'The weighbridge')],
      costs: [{ label: 'Weighing fee', rupees: 150, paidBy: 'farmer' }],
    },
    {
      kind: 'roadside', place: 'Highway dhaba near Jhansi', short: 'Near Jhansi', at: [78.57, 25.47],
      via: [[76.23, 21.3], [76.35, 21.83], [77.2, 22.9], [77.4, 23.25], [78.2, 24.2]], km: 720, arrive: '2026-11-13T07:00', depart: '2026-11-13T17:00',
      handler: { role: 'Driver', name: 'Rafiq, then Kallu, Satpura Transport' },
      handoff: 'Driver change on the road: the relief driver, Kallu, took the lorry receipt and checked the load.',
      records: [CALL_LOG('the transporter'), LORRY_RECEIPT('STP-2611-0388', 'The transporter')],
      difficulty: { cause: 'The driver fell ill and could not drive on.', delayHours: 10, told: 'The transporter called G-One; G-One told Imtiaz in Hindi and Prakash in Marathi, with the new arrival estimate.', resolution: 'The transporter sent a relief driver from its Jhansi office.', costImpact: 'Transporter paid the relief driver. Ripening risk: the buyer may ask for a ripening-chamber check on arrival.' },
    },
    {
      kind: 'toll', place: 'Toll plaza near Kanpur', short: 'Kanpur toll', at: [80.25, 26.42], via: [[79.15, 25.9], [79.75, 26.25]], km: 280, arrive: '2026-11-14T22:00', depart: '2026-11-14T22:10', planned: true,
      handler: { role: 'Driver', name: 'Kallu, Satpura Transport' },
      handoff: 'No hand-off expected. Toll by FASTag.',
      records: [{ item: 'FASTag toll entry', heldBy: 'The transporter’s FASTag account' }],
    },
    {
      kind: 'godown', place: 'Awadh Fruit Company ripening godown, Lucknow', short: 'Buyer godown', at: [80.9, 26.82], via: [[80.6, 26.62]], km: 85, arrive: '2026-11-15T01:00', planned: true,
      handler: { role: 'Godown manager', name: 'Imtiaz, Awadh Fruit Company' },
      handoff: 'Planned: weighment, a ripeness check, signed lorry receipt, then payment.',
      records: [LORRY_RECEIPT('STP-2611-0388', 'The transporter and the buyer, once signed')],
    },
  ],
})

const cauliflower = deal({
  id: 'cauliflower-sonipat-delhi',
  lane: { from: 'Sonipat', to: 'Delhi' },
  crop: 'Cauliflower',
  quantity: { amount: 18, unit: 'quintal', quintals: 18 },
  price: { rupees: 1100, per: 'quintal', basis: 'per quintal, delivered to the buyer’s stall at the Delhi yard, farmer arranges transport' },
  farmer: { name: 'Sukhbir', village: 'a village near Sonipat, Haryana', language: 'hi' },
  buyer: { business: 'Malik Vegetable Co.', contact: 'Javed', city: 'Delhi', language: 'hi' },
  call: { at: '2026-11-12T18:30', minutes: 6, note: 'Both spoke Hindi. G-One listened in, read the terms back, and kept private advice for the farmer.' },
  status: 'delivered-paid',
  freight: { paidBy: 'farmer', vehicle: 'Mini truck' },
  payment: { state: 'paid', method: 'UPI to the farmer, simulated', rupees: 19_800, note: 'Paid at the stall after the weighment.' },
  stops: [
    {
      kind: 'farm', place: 'Farm, a village near Sonipat', short: 'Farm near Sonipat', at: [77.02, 29.0], km: 0, arrive: '2026-11-13T02:30', depart: '2026-11-13T03:15',
      handler: { role: 'Mini truck driver', name: 'Bittu, hired by the farmer' },
      handoff: 'Sukhbir loaded 90 sacks himself and rode along with the load.',
      records: [{ item: 'Sack count in the farmer’s notebook', heldBy: 'The farmer' }],
    },
    {
      kind: 'toll', place: 'Toll plaza on NH 44 near Murthal', short: 'Murthal toll', at: [77.08, 28.9], km: 14, arrive: '2026-11-13T03:35', depart: '2026-11-13T03:40',
      handler: { role: 'Mini truck driver', name: 'Bittu' },
      handoff: 'No hand-off. Toll paid by FASTag.',
      records: [{ item: 'FASTag toll entry', heldBy: 'The driver’s FASTag account' }],
      costs: [{ label: 'Toll', rupees: 140, paidBy: 'farmer', inFreight: true }],
    },
    {
      kind: 'mandi', place: 'Wholesale vegetable yard, Delhi', short: 'Delhi yard', at: [77.17, 28.71], via: [[77.13, 28.8]], km: 32, arrive: '2026-11-13T04:30', depart: '2026-11-13T06:00',
      handler: { role: 'Stall owner', name: 'Javed, Malik Vegetable Co.' },
      handoff: 'Weighed on the yard scale: 1,800 kg. Javed checked the heads and paid Sukhbir at the stall.',
      records: [WEIGHT_SLIP('DLY-8841', 'The yard scale clerk'), CALL_LOG('the farmer')],
      costs: [{ label: 'Yard entry and unloading', rupees: 300, paidBy: 'buyer' }],
    },
  ],
})

const pomegranate = deal({
  id: 'pomegranate-sangola-bengaluru',
  lane: { from: 'Sangola', to: 'Bengaluru' },
  crop: 'Pomegranate',
  quantity: { amount: 500, unit: 'crate', quintals: 50 },
  price: { rupees: 1150, per: 'crate', basis: 'per 10 kg crate, graded at the farm, buyer’s truck, pay on delivery to the buyer’s godown' },
  farmer: { name: 'Sanjay Shinde', village: 'a village near Sangola, Maharashtra', language: 'mr' },
  buyer: { business: 'Brindavan Fruit Mart', contact: 'Nagaraj', city: 'Bengaluru', language: 'kn' },
  call: { at: '2026-11-08T12:10', minutes: 13, note: 'Sanjay spoke Marathi and Nagaraj spoke Kannada. G-One translated both ways.' },
  status: 'cold-storage',
  freight: { paidBy: 'buyer', vehicle: 'Refrigerated truck, part load' },
  payment: { state: 'not-due', method: 'Bank transfer, simulated', rupees: 575_000, note: 'Due when the crates reach the buyer’s godown.' },
  stops: [
    {
      kind: 'farm', place: 'Orchard, a village near Sangola', short: 'Orchard near Sangola', at: [75.2, 17.43], km: 0, arrive: '2026-11-09T06:00', depart: '2026-11-09T09:00',
      handler: { role: 'Driver', name: 'Shivu, Kaveri Cool Carriers' },
      handoff: 'Crates graded and counted at the orchard; the driver signed for 500 crates.',
      records: [{ item: 'Crate count and grade sheet, signed by the driver', heldBy: 'Farmer’s copy; the driver keeps the other' }],
    },
    {
      kind: 'weighbridge', place: 'Weighbridge, Sangola', short: 'Sangola weighbridge', at: [75.19, 17.44], km: 4, arrive: '2026-11-09T09:15', depart: '2026-11-09T09:35',
      handler: { role: 'Weighbridge operator', name: 'Ajay, Mangalwedha Road Weighbridge' },
      handoff: 'Loaded truck weighed: 5,030 kg net. Slip to both.',
      records: [WEIGHT_SLIP('SGL-2093', 'The weighbridge')],
      costs: [{ label: 'Weighing fee', rupees: 150, paidBy: 'farmer' }],
    },
    {
      kind: 'cold-storage', place: 'Multi-crop cold store, Hoskote near Bengaluru', short: 'Hoskote cold store', at: [77.8, 13.07],
      via: [[75.71, 17.0], [75.92, 15.95], [76.4, 14.95], [76.6, 13.95], [77.25, 13.3]], km: 640, arrive: '2026-11-10T05:00',
      handler: { role: 'Cold store manager', name: 'Prakash, Nandi Cold Chain' },
      handoff: 'The buyer’s godown was full after a festival rush, so the crates went into cold storage under a storage receipt in the buyer’s name.',
      records: [{ item: 'Storage receipt NCC-1188 and daily temperature log, 5 to 7 °C', heldBy: 'The cold store; a copy with the buyer' }, CALL_LOG('the buyer')],
      difficulty: { cause: 'The buyer could not take delivery on time: the godown was full.', delayHours: 72, told: 'Nagaraj called G-One; G-One told Sanjay in Marathi and read back the "pay on delivery to the buyer’s godown" term.', resolution: 'Both agreed on a call to hold the crates at the cold store for up to 4 days, fee shared half each.', costImpact: 'Storage at ₹4 per crate a day, shared. Payment moves to delivery day.' },
      costs: [{ label: 'Cold storage, up to 4 days', rupees: 8000, paidBy: 'split', storage: true }],
    },
    {
      kind: 'godown', place: 'Brindavan Fruit Mart godown, Bengaluru', short: 'Buyer godown', at: [77.62, 12.98], km: 25, arrive: '2026-11-15T09:00', planned: true,
      handler: { role: 'Godown manager', name: 'Nagaraj, Brindavan Fruit Mart' },
      handoff: 'Planned: release from the cold store against the receipt, count at the godown, then payment.',
      records: [{ item: 'Release slip against NCC-1188', heldBy: 'The cold store and the buyer, once released' }],
    },
  ],
})

const soybean = deal({
  id: 'soybean-latur-indore',
  lane: { from: 'Latur', to: 'Indore' },
  crop: 'Soybean',
  quantity: { amount: 120, unit: 'quintal', quintals: 120 },
  price: { rupees: 4750, per: 'quintal', basis: 'per quintal at the mill gate, moisture up to 10%, buyer pays freight, payment within 3 days' },
  farmer: { name: 'Dnyaneshwar', village: 'a village near Latur, Maharashtra', language: 'mr' },
  buyer: { business: 'Malwa Agro Processors', contact: 'Rajendra', city: 'Indore', language: 'hi' },
  call: { at: '2026-11-13T15:40', minutes: 10, note: 'Dnyaneshwar spoke Marathi and Rajendra spoke Hindi. G-One translated both ways.' },
  status: 'awaiting-pickup',
  freight: { paidBy: 'buyer', vehicle: 'Full truck' },
  payment: { state: 'not-due', method: 'Bank transfer, simulated', rupees: 570_000, note: 'Due within 3 days of the mill-gate weighment.' },
  stops: [
    {
      kind: 'farm', place: 'Farm, a village near Latur', short: 'Farm near Latur', at: [76.6, 18.35], km: 0, arrive: '2026-11-15T07:00', depart: '2026-11-15T10:00', planned: true,
      handler: { role: 'Driver', name: 'Mahesh, Manjara Logistics' },
      handoff: 'Planned: 240 bags counted at loading; the driver signs the count.',
      records: [{ item: 'Bag count, to be signed by the driver', heldBy: 'Farmer and driver' }],
    },
    {
      kind: 'weighbridge', place: 'Weighbridge, Latur', short: 'Latur weighbridge', at: [76.57, 18.4], km: 9, arrive: '2026-11-15T10:20', depart: '2026-11-15T10:45', planned: true,
      handler: { role: 'Weighbridge operator', name: 'Sunil, Manjara Weighbridge' },
      handoff: 'Planned: loaded truck weighed, slip to both.',
      records: [{ item: 'Weight slip', heldBy: 'The weighbridge; copies with the farmer and the driver' }],
    },
    {
      kind: 'toll', place: 'Toll plaza near Aurangabad', short: 'Aurangabad toll', at: [75.4, 19.85], via: [[76.2, 18.9], [75.75, 19.4]], km: 250, arrive: '2026-11-15T16:30', depart: '2026-11-15T16:40', planned: true,
      handler: { role: 'Driver', name: 'Mahesh, Manjara Logistics' },
      handoff: 'No hand-off expected. Toll by FASTag.',
      records: [{ item: 'FASTag toll entry', heldBy: 'The transporter’s FASTag account' }],
    },
    {
      kind: 'godown', place: 'Malwa Agro Processors mill gate, Indore', short: 'Mill gate, Indore', at: [75.85, 22.75], via: [[75.55, 21.05], [75.7, 21.9]], km: 420, arrive: '2026-11-16T05:00', planned: true,
      handler: { role: 'Mill gate supervisor', name: 'Rajendra, Malwa Agro Processors' },
      handoff: 'Planned: weighment and moisture test at the mill gate, gate pass, signed lorry receipt.',
      records: [{ item: 'Gate pass and moisture test slip', heldBy: 'The mill' }],
    },
  ],
})

const cotton = deal({
  id: 'cotton-adilabad-akola',
  lane: { from: 'Adilabad', to: 'Akola' },
  crop: 'Raw cotton',
  quantity: { amount: 45, unit: 'quintal', quintals: 45 },
  price: { rupees: 7200, per: 'quintal', basis: 'per quintal at the ginning mill, moisture up to 8%, deduction above that, payment within 2 days' },
  farmer: { name: 'Bheem Rao', village: 'a village near Adilabad, Telangana', language: 'te' },
  buyer: { business: 'Varhad Ginning Works', contact: 'Ashok', city: 'Akola', language: 'mr' },
  call: { at: '2026-11-03T11:00', minutes: 15, note: 'Bheem Rao spoke Telugu and Ashok spoke Marathi. G-One translated both ways.' },
  status: 'delivered-paid',
  freight: { paidBy: 'farmer', vehicle: 'Hired truck, part load' },
  payment: { state: 'paid', method: 'Bank transfer, simulated', rupees: 319_680, note: '₹3,24,000 less ₹4,320 moisture deduction, paid two days after delivery.' },
  stops: [
    {
      kind: 'farm', place: 'Farm, a village near Adilabad', short: 'Farm near Adilabad', at: [78.55, 19.65], km: 0, arrive: '2026-11-04T06:00', depart: '2026-11-04T08:30',
      handler: { role: 'Driver', name: 'Yadagiri, hired by the farmer' },
      handoff: 'Cotton loaded loose and covered; the driver signed the farmer’s load note.',
      records: [{ item: 'Load note, signed by the driver', heldBy: 'The farmer' }],
    },
    {
      kind: 'weighbridge', place: 'Weighbridge, Adilabad', short: 'Adilabad weighbridge', at: [78.53, 19.67], km: 6, arrive: '2026-11-04T08:50', depart: '2026-11-04T09:10',
      handler: { role: 'Weighbridge operator', name: 'Srinivas, Penganga Weighbridge' },
      handoff: 'Loaded truck weighed: 4,510 kg net. Slip to the farmer and the driver.',
      records: [WEIGHT_SLIP('ADB-1460', 'The weighbridge')],
      costs: [{ label: 'Weighing fee', rupees: 150, paidBy: 'farmer' }],
    },
    {
      kind: 'godown', place: 'Varhad Ginning Works, Akola', short: 'Ginning mill, Akola', at: [77.0, 20.7], via: [[78.4, 19.95], [78.12, 20.38], [77.75, 20.6], [77.3, 20.7]], km: 250, arrive: '2026-11-04T15:00', depart: '2026-11-04T18:00',
      handler: { role: 'Mill gate supervisor', name: 'Ashok, Varhad Ginning Works' },
      handoff: 'Weighed at the mill gate: 4,500 kg. Moisture test: 9.2%, above the 8% in the deal.',
      records: [{ item: 'Gate pass and moisture test slip, 9.2%', heldBy: 'The mill; a photo copy with the farmer' }, CALL_LOG('the farmer')],
      difficulty: { cause: 'Moisture above the agreed limit, so the mill applied a deduction.', delayHours: 2, told: 'Bheem Rao called G-One. G-One read back the moisture term from the deal card in Telugu and Marathi; it did not test the cotton.', resolution: 'Bheem Rao accepted a 0.6 quintal weight deduction, per the moisture term both sides had agreed.', costImpact: '₹4,320 less than the full ₹3,24,000.' },
      costs: [{ label: 'Moisture deduction, 0.6 quintal', rupees: 4320, paidBy: 'farmer' }],
    },
  ],
})

const potatoEast = deal({
  id: 'potato-hooghly-guwahati',
  lane: { from: 'Tarakeswar', to: 'Guwahati' },
  crop: 'Potato',
  quantity: { amount: 90, unit: 'quintal', quintals: 90 },
  price: { rupees: 1250, per: 'quintal', basis: 'per quintal, out of the cold store at Tarakeswar, buyer pays freight, pay on delivery' },
  farmer: { name: 'Tapas', village: 'a village near Tarakeswar, West Bengal', language: 'bn' },
  buyer: { business: 'Brahmaputra Veg Supply', contact: 'Hiren', city: 'Guwahati', language: 'hi' },
  call: { at: '2026-11-11T13:15', minutes: 9, note: 'Tapas spoke Bengali and Hiren spoke Hindi. G-One translated both ways.' },
  status: 'in-transit',
  freight: { paidBy: 'buyer', vehicle: 'Full truck' },
  payment: { state: 'not-due', method: 'UPI to the farmer, simulated', rupees: 112_500, note: 'Due on delivery.' },
  truck: { leg: 3, fraction: 0.3 },
  stops: [
    {
      kind: 'cold-storage', place: 'Potato cold store near Tarakeswar', short: 'Tarakeswar cold store', at: [88.0, 22.88], km: 0, arrive: '2026-11-12T08:00', depart: '2026-11-12T11:00',
      handler: { role: 'Cold store clerk', name: 'Biswajit, Damodar Cold Storage' },
      handoff: '180 bags released against Tapas’s storage receipt and loaded.',
      records: [{ item: 'Release slip against storage receipt DCS-6620', heldBy: 'The cold store; a copy with the farmer' }],
      costs: [{ label: 'Storage rent, 7 months', rupees: 17_500, paidBy: 'farmer', storage: true }],
    },
    {
      kind: 'weighbridge', place: 'Weighbridge, Tarakeswar', short: 'Tarakeswar weighbridge', at: [88.02, 22.9], km: 5, arrive: '2026-11-12T11:20', depart: '2026-11-12T11:40',
      handler: { role: 'Weighbridge operator', name: 'Gopal, Tarakeswar Weighbridge' },
      handoff: 'Loaded truck weighed: 9,030 kg net. Slip to both.',
      records: [WEIGHT_SLIP('TKS-0745', 'The weighbridge')],
      costs: [{ label: 'Weighing fee', rupees: 150, paidBy: 'farmer' }],
    },
    {
      kind: 'roadside', place: 'NH 27 near Siliguri', short: 'Near Siliguri', at: [88.4, 26.68],
      via: [[88.1, 23.6], [88.15, 24.3], [87.95, 24.9], [88.1, 25.6], [87.95, 26.1], [88.25, 26.5]], km: 590, arrive: '2026-11-13T06:00', depart: '2026-11-13T12:00',
      handler: { role: 'Driver', name: 'Abdul, Teesta Transport' },
      handoff: 'No hand-off. The load stayed on the truck.',
      records: [CALL_LOG('the driver')],
      difficulty: { cause: 'A road repair diversion near Siliguri held traffic for hours.', delayHours: 6, told: 'Abdul called G-One; G-One told Hiren in Hindi and Tapas in Bengali.', resolution: 'The truck waited for the diversion to open; potato keeps well for a few days.', costImpact: 'None to the deal.' },
    },
    {
      kind: 'toll', place: 'Toll plaza on NH 27 near Bongaigaon', short: 'Bongaigaon toll', at: [90.55, 26.47], via: [[89.2, 26.6], [89.9, 26.48]], km: 260, arrive: '2026-11-14T20:00', depart: '2026-11-14T20:10', planned: true,
      handler: { role: 'Driver', name: 'Abdul, Teesta Transport' },
      handoff: 'No hand-off expected. Toll by FASTag.',
      records: [{ item: 'FASTag toll entry', heldBy: 'The transporter’s FASTag account' }],
    },
    {
      kind: 'godown', place: 'Brahmaputra Veg Supply godown, Guwahati', short: 'Buyer godown', at: [91.7, 26.15], via: [[91.2, 26.3]], km: 180, arrive: '2026-11-15T03:00', planned: true,
      handler: { role: 'Godown manager', name: 'Hiren, Brahmaputra Veg Supply' },
      handoff: 'Planned: weighment, signed lorry receipt, then payment.',
      records: [LORRY_RECEIPT('TTR-2611-0233', 'The transporter and the buyer, once signed')],
    },
  ],
})

/** The two demo deals first, then the rest with the newest deal call first. */
export const DEALS: readonly Deal[] = [
  onion,
  tomato,
  ...[chilli, potato, banana, cauliflower, pomegranate, soybean, cotton, potatoEast].sort((a, b) => minutesOf(b.call.at) - minutesOf(a.call.at)),
]

export const dealById = (id: string | null | undefined) => DEALS.find((d) => d.id === id) ?? null
