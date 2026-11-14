// Development only: FarmersPage imports this behind `import.meta.env.DEV` and #/farmers?seed=1. Every person, place and
// number here is invented, and nothing here is sent to the backend. Two names are stored in native script on purpose,
// to show the tab always displays English letters.
import { toFarmer, type Farmer, type FarmerDeal } from '../lib/farmers'
import type { MemoryFact, MemoryGist } from '../lib/memory'

const DAY = 86_400_000
const MIN = 60_000

type Row = [string, string, 'said' | 'inferred']

function facts(atMs: number, rows: Row[]): MemoryFact[] {
  return rows.map(([field, value, source]) => ({ field, value, source, callId: 'seed-call', atMs }))
}

function gist(callId: string, startedMs: number, minutes: number, summary: string, followUps: string[] = []): MemoryGist {
  return { callId, startedMs, endedMs: startedMs + minutes * MIN, summary, followUps }
}

function deal(callId: string, atMs: number, over: Partial<FarmerDeal>): FarmerDeal {
  return { id: callId, callId, commodity: '', quantity: '', price: null, unit: 'quintal', pickup: '', state: 'agreed', atMs, ...over }
}

export function seedFarmers(now: number): Farmer[] {
  const key = (c: string) => c.repeat(64).slice(0, 64)
  const ago = (days: number) => now - days * DAY
  return [
    // Many fields, a native-script (Devanagari) name, two deals and an open follow-up.
    toFarmer(
      key('a1'), 'seed', 'hi-IN', ago(1),
      facts(ago(1), [
        ['name', 'रामेश्वर पाटिल', 'said'], ['village', 'Pimpalgaon', 'said'], ['district', 'Nashik', 'said'], ['state', 'Maharashtra', 'inferred'],
        ['usualMandi', 'Lasalgaon APMC', 'said'], ['crop:red onion', 'red onion', 'said'], ['crop:tomato', 'tomato', 'said'], ['crop:grapes', 'grapes', 'inferred'],
        ['crop:garlic', 'garlic', 'said'], ['typicalQuantity', '40 quintal per sale', 'said'], ['transport', 'needs truck', 'said'],
        ['paymentPreference', 'UPI', 'said'], ['storageAccess', 'cold storage nearby', 'inferred'], ['language', 'HI', 'inferred'],
      ]),
      [
        gist('seed-1', ago(1), 6, 'Agreed 10 quintal red onion with a Pune trader, pickup at the farm the next morning.', ['Check the pickup and UPI payment with the Pune trader (due ~tomorrow)']),
        gist('seed-2', ago(9), 3, 'Asked the red onion reference at Lasalgaon and chose to wait for harvest.'),
        gist('seed-2b', ago(21), 8, 'Agreed 6 quintal tomato with a Nashik commission agent.'),
      ],
      [
        deal('seed-1', ago(1), { commodity: 'red onion', quantity: '10 quintal', price: 2200, buyer: 'Trader, Pune', pickup: 'farm, next morning' }),
        deal('seed-2b', ago(21), { commodity: 'tomato', quantity: '6 quintal', price: 1200, buyer: 'Commission agent, Nashik', pickup: 'at farm' }),
      ],
    ),
    // A native-script (Bengali) name, one deal priced as a total.
    toFarmer(
      key('b2'), 'seed', 'bn-IN', ago(3),
      facts(ago(3), [['name', 'মিতা দাস', 'said'], ['district', 'Bardhaman', 'said'], ['state', 'West Bengal', 'inferred'], ['crop:potato', 'potato', 'said'], ['paymentPreference', 'cash', 'said'], ['language', 'BN', 'inferred']]),
      [gist('seed-3', ago(3), 4, 'Agreed 20 quintal potato with a Kolkata wholesaler; a caution was given when a caller asked for an OTP.', ['Share the next dated potato reference'])],
      [deal('seed-3', ago(3), { commodity: 'potato', quantity: '20 quintal', price: 26000, unit: 'total', buyer: 'Wholesaler, Kolkata' })],
    ),
    // No deals yet.
    toFarmer(
      key('c3'), 'seed', 'hi-en', ago(12),
      facts(ago(12), [['name', 'Gurpreet Singh', 'said'], ['district', 'Ludhiana', 'inferred'], ['state', 'Punjab', 'inferred'], ['crop:wheat', 'wheat', 'said'], ['transport', 'own vehicle', 'said'], ['storageAccess', 'no cold storage', 'said']]),
      [gist('seed-4', ago(12), 2, 'Asked about rain before the wheat harvest; no sale discussed.')],
      [],
    ),
    // Four crops ("+1" in the row), Hinglish, a deal still open.
    toFarmer(
      key('d4'), 'seed', 'hi-en', ago(5),
      facts(ago(5), [['name', 'Kavita Yadav', 'said'], ['district', 'Indore', 'said'], ['state', 'Madhya Pradesh', 'inferred'], ['crop:soybean', 'soybean', 'said'], ['crop:wheat', 'wheat', 'said'], ['crop:gram', 'gram', 'said'], ['crop:maize', 'maize', 'inferred'], ['typicalQuantity', '15 quintal per sale', 'said'], ['transport', 'own vehicle', 'said'], ['language', 'HI_EN', 'inferred']]),
      [gist('seed-5', ago(5), 5, 'Offered 15 quintal soybean to an Indore processor; the price was not agreed yet.', ['Call back when the processor answers on soybean'])],
      [deal('seed-5', ago(5), { commodity: 'soybean', quantity: '15 quintal', price: 4600, state: 'offer_open', buyer: 'Processor, Indore' })],
    ),
    // A Tamil-script name.
    toFarmer(
      key('e5'), 'seed', 'ta-IN', ago(7),
      facts(ago(7), [['name', 'செல்வி', 'said'], ['district', 'Erode', 'said'], ['state', 'Tamil Nadu', 'inferred'], ['crop:turmeric', 'turmeric', 'said'], ['crop:banana', 'banana', 'said'], ['storageAccess', 'has cold storage', 'said']]),
      [gist('seed-6', ago(7), 4, 'Asked the turmeric reference at Erode and wants a buyer after drying.', ['Find a turmeric buyer after drying'])],
      [],
    ),
    // Sparse: a name and a language only.
    toFarmer(key('f6'), 'seed', 'kn-IN', ago(15), facts(ago(15), [['name', 'Basavaraj', 'said']]), [gist('seed-7', ago(15), 1, 'Called to ask what G-One does; nothing else discussed.')], []),
    // Sparse: no name given, one inferred crop.
    toFarmer(key('a7'), 'seed', 'hi-IN', ago(30), facts(ago(30), [['crop:paddy', 'paddy', 'inferred']]), [gist('seed-8', ago(30), 2, 'Asked the paddy reference; did not give a name.')], []),
  ]
}
