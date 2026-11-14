import { etaHours, etaLabel, freightRupees } from './freight'
import type { LonLat } from './geo'
import type { LanguageCode } from './languages'

/** The eight stages of a deal on the map, in order. */
export const STAGES = [
  { id: 'call', name: 'The call' },
  { id: 'deal', name: 'The deal locks' },
  { id: 'dispatch', name: 'Truck dispatched' },
  { id: 'storage', name: 'Cold storage option' },
  { id: 'pickup', name: 'Pickup' },
  { id: 'transit', name: 'In transit' },
  { id: 'arrival', name: 'Arrival and hand-off' },
  { id: 'payment', name: 'Payment on delivery' },
] as const

export type StageId = (typeof STAGES)[number]['id']

export type StepIcon = 'phone' | 'language' | 'ear' | 'shield' | 'handshake' | 'send' | 'store' | 'wheat' | 'arrow' | 'weigh' | 'pay'

export type StepId = 'ring' | 'connect' | 'price-ref' | 'switch' | 'caution' | 'lock' | 'dispatch' | 'storage' | 'pickup' | 'transit' | 'arrival' | 'payment'

/** The steps every scenario plays, in order; each belongs to one stage. */
export const STEP_ORDER: readonly { id: StepId; stage: StageId; title: string; icon: StepIcon; seconds: number }[] = [
  { id: 'ring', stage: 'call', title: 'Call rings and connects', icon: 'phone', seconds: 3.5 },
  { id: 'connect', stage: 'call', title: 'Translation in the middle', icon: 'language', seconds: 3.5 },
  { id: 'price-ref', stage: 'call', title: 'Private price reference', icon: 'ear', seconds: 4 },
  { id: 'switch', stage: 'call', title: 'Language switch', icon: 'language', seconds: 3.5 },
  { id: 'caution', stage: 'call', title: 'Private caution', icon: 'shield', seconds: 4 },
  { id: 'lock', stage: 'deal', title: 'The deal locks', icon: 'handshake', seconds: 4 },
  { id: 'dispatch', stage: 'dispatch', title: 'Truck dispatched', icon: 'send', seconds: 4 },
  { id: 'storage', stage: 'storage', title: 'Cold storage option', icon: 'store', seconds: 4 },
  { id: 'pickup', stage: 'pickup', title: 'Pickup', icon: 'wheat', seconds: 3 },
  { id: 'transit', stage: 'transit', title: 'In transit', icon: 'arrow', seconds: 8 },
  { id: 'arrival', stage: 'arrival', title: 'Arrival and hand-off', icon: 'weigh', seconds: 3.5 },
  { id: 'payment', stage: 'payment', title: 'Payment on delivery', icon: 'pay', seconds: 3.5 },
]

export interface Place {
  name: string
  /** Where the pin sits. Real towns, approximate coordinates. */
  at: LonLat
}

export interface Party extends Place {
  person: string
  language: LanguageCode
}

export interface StepCopy {
  /** Scenario clock, minutes since Day 1 00:00. */
  atMin: number
  who: string
  langs: LanguageCode[]
  /** How two languages relate: spoken across the call, or a switch from one to the other. */
  join?: 'across' | 'switch'
  line: string
}

export interface Step extends StepCopy {
  id: StepId
  stage: StageId
  title: string
  icon: StepIcon
  seconds: number
}

export interface Scenario {
  id: string
  label: string
  summary: string
  farmer: Party
  buyer: Party
  /** One side changes language part-way through the call. */
  languageSwitch: { side: 'farmer' | 'buyer'; to: LanguageCode }
  deal: { crop: string; quantityQuintals: number; pricePerQuintal: number; basis: string; total: number }
  transporter: Place & { language: LanguageCode }
  storage: Place & { reason: string }
  weighbridge: Place
  /** Synthetic road distance estimate, not from the sample table. */
  km: number
  freight: number
  etaHours: number
  steps: Step[]
}

export const rupees = (n: number) => `₹${n.toLocaleString('en-IN')}`

type Draft = Omit<Scenario, 'steps' | 'freight' | 'etaHours' | 'deal'> & {
  deal: Omit<Scenario['deal'], 'total'>
  copy: (s: Scenario) => Record<StepId, Omit<StepCopy, 'atMin'> & { atMin?: number }>
  /** Clock for the steps before transit; transit and later follow from the ETA. */
  times: Partial<Record<StepId, number>>
}

const HOUR = 60
const DAY = 24 * HOUR

function build(draft: Draft): Scenario {
  const { copy, times, deal, ...rest } = draft
  const total = deal.quantityQuintals * deal.pricePerQuintal
  const scenario: Scenario = {
    ...rest,
    deal: { ...deal, total },
    freight: freightRupees(draft.km, deal.quantityQuintals),
    etaHours: etaHours(draft.km),
    steps: [],
  }
  const lines = copy(scenario)
  const transitStart = (times.pickup ?? 0) + HOUR
  const arrival = transitStart + scenario.etaHours * HOUR
  const clock: Record<StepId, number> = {
    ...(times as Record<StepId, number>),
    transit: transitStart,
    arrival,
    payment: arrival + 3 * HOUR,
  }
  scenario.steps = STEP_ORDER.map((step) => ({ ...step, ...lines[step.id], atMin: clock[step.id] }))
  return scenario
}

const onion = build({
  id: 'onion-gadag-kolkata',
  label: 'Onion, Gadag to Kolkata',
  summary: 'A Kannada-speaking onion farmer near Gadag sells to a Bengali-speaking trader in Kolkata.',
  farmer: { person: 'Basavaraj', name: 'Village near Gadag', at: [75.63, 15.43], language: 'kn' },
  buyer: { person: 'Mondal Traders', name: 'Kolkata', at: [88.36, 22.57], language: 'bn' },
  languageSwitch: { side: 'buyer', to: 'hi' },
  deal: { crop: 'Onion', quantityQuintals: 40, pricePerQuintal: 1900, basis: "per quintal at the farm gate, buyer's truck, pay on delivery" },
  transporter: { name: 'Tungabhadra Roadlines, Hubballi', at: [75.12, 15.36], language: 'hi' },
  storage: { name: 'Cold store near Gadag', at: [75.95, 15.12], reason: 'pickup slips' },
  weighbridge: { name: 'Mandi agent weighbridge near Kolkata', at: [88.1, 22.82] },
  km: 1880,
  times: { ring: 9 * HOUR, connect: 9 * HOUR + 1, 'price-ref': 9 * HOUR + 4, switch: 9 * HOUR + 6, caution: 9 * HOUR + 8, lock: 9 * HOUR + 11, dispatch: 10 * HOUR + 30, storage: 18 * HOUR, pickup: DAY + 7 * HOUR },
  copy: (s) => ({
    ring: { who: 'Basavaraj (farmer) calls G-One', langs: ['kn'], line: 'G-One answers in Kannada and rings Mondal Traders on a second line.' },
    connect: { who: 'G-One between both callers', langs: ['kn', 'bn'], join: 'across', line: 'Each side hears the other in its own language. G-One translates in the middle.' },
    'price-ref': { who: 'G-One, privately to Basavaraj', langs: ['kn'], line: 'Only the farmer hears: the sample price reference for onion is ₹1,750 to ₹2,050 a quintal.' },
    switch: { who: 'Mondal Traders (buyer)', langs: ['bn', 'hi'], join: 'switch', line: 'The buyer switches to Hindi for the numbers. G-One follows the switch.' },
    caution: { who: 'G-One, privately to Basavaraj', langs: ['kn'], line: 'Only the farmer hears: never share an OTP or pay any fee to receive money.' },
    lock: { who: 'Both say yes', langs: ['kn', 'hi'], join: 'across', line: `${s.deal.quantityQuintals} quintal onion at ${rupees(s.deal.pricePerQuintal)} a quintal: ${rupees(s.deal.total)} in total. G-One records the agreed terms.` },
    dispatch: { who: 'G-One to Tungabhadra Roadlines', langs: ['hi'], line: `A truck is booked for the buyer: about ${s.km.toLocaleString('en-IN')} km, ${etaLabel(s.etaHours)}, freight about ${rupees(s.freight)} paid by the buyer.` },
    storage: { who: 'G-One to Basavaraj', langs: ['kn'], line: 'The truck is late and the pickup slips to tomorrow morning. G-One mentions a cold store near the farm as an option; the farmer decides.' },
    pickup: { who: 'Driver and Basavaraj', langs: ['kn', 'hi'], join: 'across', line: 'The onion is loaded at the farm. The farmer and the driver count the bags together.' },
    transit: { who: 'Truck, simulated', langs: ['hi'], line: `On the road to Kolkata, ${etaLabel(s.etaHours)}. The position on the map is simulated.` },
    arrival: { who: 'Mandi agent near Kolkata', langs: ['bn'], line: 'Hand-off at the weighbridge: weighment and grading happen at the mandi, not by G-One.' },
    payment: { who: 'Mondal Traders to Basavaraj', langs: ['bn', 'kn'], join: 'across', line: `${rupees(s.deal.total)} paid on delivery, simulated. G-One moves no money; it reads back what was agreed.` },
  }),
})

const tomato = build({
  id: 'tomato-nashik-chennai',
  label: 'Tomato, Nashik to Chennai',
  summary: 'A Marathi-speaking tomato farmer near Nashik sells to a Tamil-speaking trader in Chennai.',
  farmer: { person: 'Sunita Jadhav', name: 'Village near Nashik', at: [73.79, 20.0], language: 'mr' },
  buyer: { person: 'Selvam Vegetable Traders', name: 'Chennai', at: [80.27, 13.08], language: 'ta' },
  languageSwitch: { side: 'buyer', to: 'en' },
  deal: { crop: 'Tomato', quantityQuintals: 30, pricePerQuintal: 1200, basis: "per quintal at the farm gate, buyer's truck, pay on delivery" },
  transporter: { name: 'Sahyadri Roadways, Pimpalgaon', at: [74.25, 20.3], language: 'hi' },
  storage: { name: 'Pre-cooling cold store near Nashik', at: [73.45, 19.65], reason: 'tomato is perishable' },
  weighbridge: { name: 'Mandi agent weighbridge near Chennai', at: [79.95, 13.3] },
  km: 1250,
  times: { ring: 7 * HOUR + 30, connect: 7 * HOUR + 31, 'price-ref': 7 * HOUR + 33, switch: 7 * HOUR + 35, caution: 7 * HOUR + 37, lock: 7 * HOUR + 40, dispatch: 8 * HOUR + 15, storage: 8 * HOUR + 30, pickup: 13 * HOUR },
  copy: (s) => ({
    ring: { who: 'Sunita Jadhav (farmer) calls G-One', langs: ['mr'], line: 'G-One answers in Marathi and rings Selvam Vegetable Traders on a second line.' },
    connect: { who: 'G-One between both callers', langs: ['mr', 'ta'], join: 'across', line: 'Each side hears the other in its own language. G-One translates in the middle.' },
    'price-ref': { who: 'G-One, privately to Sunita', langs: ['mr'], line: 'Only the farmer hears: the sample price reference for tomato is ₹1,050 to ₹1,350 a quintal.' },
    switch: { who: 'Selvam Vegetable Traders (buyer)', langs: ['ta', 'en'], join: 'switch', line: 'The buyer switches to English. G-One follows the switch.' },
    caution: { who: 'G-One, privately to Sunita', langs: ['mr'], line: 'Only the farmer hears: agree the payment day before loading, and never pay a fee to receive money.' },
    lock: { who: 'Both say yes', langs: ['mr', 'en'], join: 'across', line: `${s.deal.quantityQuintals} quintal tomato at ${rupees(s.deal.pricePerQuintal)} a quintal: ${rupees(s.deal.total)} in total. G-One records the agreed terms.` },
    dispatch: { who: 'G-One to Sahyadri Roadways', langs: ['hi'], line: `A truck is booked for the buyer: about ${s.km.toLocaleString('en-IN')} km, ${etaLabel(s.etaHours)}, freight about ${rupees(s.freight)} paid by the buyer.` },
    storage: { who: 'G-One to Sunita', langs: ['mr'], line: 'Tomato spoils fast. G-One mentions a pre-cooling cold store near the farm as an option; the farmer decides.' },
    pickup: { who: 'Driver and Sunita', langs: ['mr', 'hi'], join: 'across', line: 'The tomato is loaded at the farm. The farmer and the driver count the crates together.' },
    transit: { who: 'Truck, simulated', langs: ['hi'], line: `On the road to Chennai, ${etaLabel(s.etaHours)}. The position on the map is simulated.` },
    arrival: { who: 'Mandi agent near Chennai', langs: ['ta'], line: 'Hand-off at the weighbridge: weighment and grading happen at the mandi, not by G-One.' },
    payment: { who: 'Selvam Vegetable Traders to Sunita', langs: ['en', 'mr'], join: 'across', line: `${rupees(s.deal.total)} paid on delivery, simulated. G-One moves no money; it reads back what was agreed.` },
  }),
})

export const SCENARIOS: readonly Scenario[] = [onion, tomato]

