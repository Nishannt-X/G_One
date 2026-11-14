import type { Theme } from '../lib/theme'
import { bowControl, indiaDots, MAP_HEIGHT, MAP_WIDTH, project, quadPath, quadPoint, splitQuad, type Point } from './geo'
import { languageColour, languageName } from './languages'
import { clockAt, formatClock, progressOf, reached, threadLanguages, whisperOn, type Position } from './playback'
import { rupees, type Scenario } from './scenarios'

const DOTS = indiaDots()

type Side = 'left' | 'right' | 'below' | 'above'

/** Where each pin's label sits, so the labels near one town do not collide. */
const LABEL_SIDES: Record<string, { farmer: Side; buyer: Side; transporter: Side; storage: Side; weighbridge: Side }> = {
  'onion-gadag-kolkata': { farmer: 'left', buyer: 'right', transporter: 'right', storage: 'below', weighbridge: 'left' },
  'tomato-nashik-chennai': { farmer: 'left', buyer: 'right', transporter: 'right', storage: 'below', weighbridge: 'left' },
}

const LABEL = 'fill-ink font-semibold text-[21px] md:text-[13px] [paint-order:stroke] [stroke:var(--page)] [stroke-width:4px] [stroke-linejoin:round]'
const SUB = 'fill-muted text-[17px] md:text-[11px] [paint-order:stroke] [stroke:var(--page)] [stroke-width:4px] [stroke-linejoin:round]'

function Label({ at, side, text, sub, className = LABEL, colour }: { at: Point; side: Side; text: string; sub?: string; className?: string; colour?: string }) {
  const gap = 12
  const anchor = side === 'left' ? 'end' : side === 'right' ? 'start' : 'middle'
  const x = side === 'left' ? at.x - gap : side === 'right' ? at.x + gap : at.x
  const y = side === 'below' ? at.y + gap + 12 : side === 'above' ? at.y - gap - (sub ? 14 : 0) : at.y + 4
  return (
    <text x={x} y={y} textAnchor={anchor} className={className} style={colour ? { fill: colour } : undefined}>
      {text}
      {sub && <tspan x={x} dy="1.25em" className={SUB}>{sub}</tspan>}
    </text>
  )
}

function Pin({ at, fill, r = 7 }: { at: Point; fill: string; r?: number }) {
  return (
    <g>
      <circle cx={at.x} cy={at.y} r={r + 4} fill={fill} opacity={0.18} />
      <circle cx={at.x} cy={at.y} r={r} fill={fill} stroke="var(--surface)" strokeWidth={2} />
    </g>
  )
}

function Square({ at, fill }: { at: Point; fill: string }) {
  return <rect x={at.x - 5.5} y={at.y - 5.5} width={11} height={11} rx={2.5} fill={fill} stroke="var(--surface)" strokeWidth={2} />
}

function Truck({ at }: { at: Point }) {
  return (
    <g transform={`translate(${at.x - 13} ${at.y - 9})`} aria-hidden="true">
      <rect x={0} y={2} width={17} height={11} rx={2} fill="var(--accent)" stroke="var(--ink)" strokeWidth={1.5} />
      <path d="M17 6h5l4 4v3h-9z" fill="var(--accent)" stroke="var(--ink)" strokeWidth={1.5} strokeLinejoin="round" />
      <circle cx={6} cy={15} r={2.6} fill="var(--ink)" />
      <circle cx={21} cy={15} r={2.6} fill="var(--ink)" />
    </g>
  )
}

const lerp = (a: Point, b: Point, t: number): Point => ({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t })

/** The map of India with the deal drawn on it at one moment of the playback. */
export default function DealMap({ scenario, at, theme }: { scenario: Scenario; at: Position; theme: Theme }) {
  const sides = LABEL_SIDES[scenario.id] ?? LABEL_SIDES['onion-gadag-kolkata']
  const farm = project(scenario.farmer.at)
  const buyer = project(scenario.buyer.at)
  const transporter = project(scenario.transporter.at)
  const storage = project(scenario.storage.at)
  const weighbridge = project(scenario.weighbridge.at)

  // The call: a curve from the farmer to the buyer, split at G-One in the middle.
  const callControl = bowControl(farm, buyer, 0.22)
  const [farmerHalf, buyerHalf] = splitQuad(farm, callControl, buyer)
  const gOne = farmerHalf[2]
  const langs = threadLanguages(scenario, at)
  const farmerColour = languageColour(langs.farmer, theme)
  const buyerColour = languageColour(langs.buyer, theme)
  const farmerDrawn = progressOf(scenario, at, 'ring')
  const buyerDrawn = progressOf(scenario, at, 'connect')
  const locked = reached(scenario, at, 'lock', 0.5)
  const callLive = !locked && farmerDrawn > 0

  // The road: a flatter curve the other way, from the farm to the weighbridge near the buyer.
  const routeControl = bowControl(farm, weighbridge, -0.12)
  const routeShown = reached(scenario, at, 'dispatch')
  const transit = progressOf(scenario, at, 'transit')

  let truck: Point | null = null
  if (reached(scenario, at, 'dispatch')) {
    const toFarm = 0.7 * progressOf(scenario, at, 'dispatch') + 0.3 * progressOf(scenario, at, 'pickup')
    truck = transit > 0 ? quadPoint(farm, routeControl, weighbridge, transit) : lerp(transporter, farm, toFarm)
  }
  const paying = progressOf(scenario, at, 'payment')
  const coin = paying > 0 ? quadPoint(buyer, callControl, farm, paying) : null
  const whisper = whisperOn(scenario, at)
  const farmerLabelPoint = quadPoint(farm, callControl, buyer, 0.2)
  const buyerLabelPoint = quadPoint(farm, callControl, buyer, 0.8)

  return (
    <svg viewBox={`0 0 ${MAP_WIDTH} ${MAP_HEIGHT}`} role="img" aria-labelledby="deal-map-title deal-map-desc" className="mx-auto block h-auto max-h-[78vh] w-full">
      <title id="deal-map-title">Simplified map of India with the simulated deal</title>
      <desc id="deal-map-desc">{`${scenario.summary} Call thread, truck route and pins are drawn on a simplified outline, not to scale.`}</desc>
      <defs>
        <radialGradient id="gone-glow">
          <stop offset="0%" stopColor="var(--accent)" stopOpacity={0.75} />
          <stop offset="100%" stopColor="var(--accent)" stopOpacity={0} />
        </radialGradient>
        <radialGradient id="whisper-glow">
          <stop offset="55%" stopColor="var(--whisper-edge)" stopOpacity={0.28} />
          <stop offset="100%" stopColor="var(--whisper-edge)" stopOpacity={0} />
        </radialGradient>
      </defs>

      <g fill="var(--line-strong)" opacity={0.4}>
        {DOTS.map((d, i) => <circle key={i} cx={d.x} cy={d.y} r={1.9} />)}
      </g>

      {routeShown && (
        <g>
          <path d={quadPath([farm, routeControl, weighbridge])} fill="none" stroke="var(--line-strong)" strokeWidth={3} strokeDasharray="2 7" strokeLinecap="round" />
          <path d={quadPath([farm, routeControl, weighbridge])} fill="none" stroke="var(--accent)" strokeWidth={4} strokeLinecap="round" pathLength={1} strokeDasharray="1 1" strokeDashoffset={1 - transit} />
        </g>
      )}

      {/* The call thread, one colour per language, changing colour at G-One. */}
      <g opacity={locked ? 0.35 : 1} className="transition-opacity duration-700">
        {farmerDrawn > 0 && (
          <path d={quadPath(farmerHalf)} fill="none" stroke={farmerColour} strokeWidth={5} strokeLinecap="round" pathLength={1} strokeDasharray="1 1" strokeDashoffset={1 - farmerDrawn} className="transition-[stroke] duration-700" />
        )}
        {buyerDrawn > 0 && (
          <path d={quadPath(buyerHalf)} fill="none" stroke={buyerColour} strokeWidth={5} strokeLinecap="round" pathLength={1} strokeDasharray="1 1" strokeDashoffset={1 - buyerDrawn} className="transition-[stroke] duration-700" />
        )}
        {callLive && buyerDrawn >= 1 && (
          <g stroke="var(--surface)" strokeWidth={2} strokeDasharray="3 9" fill="none" opacity={0.7}>
            <path d={quadPath(farmerHalf)} className="motion-safe:animate-flow" />
            <path d={quadPath(buyerHalf)} className="motion-safe:animate-flow-back" />
          </g>
        )}
        {farmerDrawn > 0.5 && <Label at={farmerLabelPoint} side="above" text={languageName(langs.farmer)} colour={farmerColour} />}
        {buyerDrawn > 0.5 && <Label at={buyerLabelPoint} side="above" text={languageName(langs.buyer)} colour={buyerColour} />}
      </g>

      {/* Pins that join as the deal moves on. */}
      {reached(scenario, at, 'storage') && (
        <g>
          <Square at={storage} fill="var(--buyer)" />
          <Label at={storage} side={sides.storage} text="Cold store (option)" />
        </g>
      )}
      {reached(scenario, at, 'dispatch') && (
        <g>
          <Square at={transporter} fill="var(--ink)" />
          <Label at={transporter} side={sides.transporter} text="Transporter" />
        </g>
      )}
      {reached(scenario, at, 'transit') && (
        <g>
          <Square at={weighbridge} fill="var(--line-strong)" />
          <Label at={weighbridge} side={sides.weighbridge} text="Weighbridge" sub="mandi agent" />
        </g>
      )}

      {whisper && (
        <g>
          <circle cx={farm.x} cy={farm.y} r={38} fill="url(#whisper-glow)" />
          <circle cx={farm.x} cy={farm.y} r={24} fill="none" stroke="var(--whisper-edge)" strokeWidth={2} strokeDasharray="4 4" />
          <circle cx={farm.x} cy={farm.y} r={24} fill="none" stroke="var(--whisper-edge)" strokeWidth={2} className="motion-safe:animate-beat [transform-box:fill-box] [transform-origin:center] motion-reduce:hidden" />
          <Label at={{ x: farm.x, y: farm.y + 18 }} side="below" text="private to the farmer" colour="var(--whisper)" />
        </g>
      )}

      <g>
        <Pin at={farm} fill="var(--farmer)" />
        <Label at={farm} side={sides.farmer} text={scenario.farmer.person} sub="farmer" />
      </g>
      <g opacity={buyerDrawn > 0 ? 1 : 0.55}>
        <Pin at={buyer} fill="var(--buyer)" />
        <Label at={buyer} side={sides.buyer} text={scenario.buyer.person} sub="buyer" />
      </g>

      {farmerDrawn >= 1 && (
        <g>
          <circle cx={gOne.x} cy={gOne.y} r={30} fill="url(#gone-glow)" className={callLive ? 'motion-safe:animate-shimmer' : ''} />
          <circle cx={gOne.x} cy={gOne.y} r={10} fill="var(--assistant)" stroke="var(--surface)" strokeWidth={2.5} />
          <Label at={gOne} side="above" text="G-One" />
        </g>
      )}

      {truck && <Truck at={truck} />}
      {coin && (
        <g>
          <circle cx={coin.x} cy={coin.y} r={11} fill="var(--accent)" stroke="var(--ink)" strokeWidth={1.5} />
          <text x={coin.x} y={coin.y + 4.5} textAnchor="middle" className="fill-on-accent text-[13px] font-bold">₹</text>
          <Label at={coin} side="right" text={`${rupees(scenario.deal.total)}`} sub="simulated" />
        </g>
      )}

      <text x={MAP_WIDTH - 10} y={MAP_HEIGHT - 12} textAnchor="end" className={SUB}>{`${formatClock(clockAt(scenario, at))}, simulated clock`}</text>
    </svg>
  )
}
