/**
 * The hero illustration, drawn for G-One: a sunrise over the fields, a phone on a call, speech in three scripts and a
 * mandi crate. Inline SVG coloured by the theme's art tokens, so it follows light and dark with no image request.
 */
const s = (name: string) => ({ fill: `var(--${name})` })

function Bubble({ x, y, w, label, lang, tone, tail }: { x: number; y: number; w: number; label: string; lang: string; tone: 'farmer' | 'buyer' | 'assistant'; tail: 'left' | 'right' }) {
  const h = 54
  const tx = tail === 'left' ? x + 22 : x + w - 22
  const dir = tail === 'left' ? -1 : 1
  return (
    <g>
      <rect x={x} y={y} width={w} height={h} rx="18" style={s(`${tone}-soft`)} stroke={`var(--${tone})`} strokeWidth="2" />
      <path d={`M${tx - 9} ${y + h - 1} L${tx + dir * 6} ${y + h + 16} L${tx + 9} ${y + h - 1} Z`} style={s(`${tone}-soft`)} />
      <path d={`M${tx - 9} ${y + h} L${tx + dir * 6} ${y + h + 16} L${tx + 9} ${y + h}`} fill="none" stroke={`var(--${tone})`} strokeWidth="2" strokeLinejoin="round" />
      <text x={x + w / 2} y={y + 35} textAnchor="middle" lang={lang} fontSize="22" fontWeight="600" fontFamily="inherit" style={s(tone)}>{label}</text>
    </g>
  )
}

const WAVE = [10, 22, 34, 18, 40, 26, 14, 30, 20, 12]

export default function HeroArt({ className = '' }: { className?: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 560 520" className={`h-auto w-full ${className}`}>
      <defs>
        <linearGradient id="hero-sky" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" style={{ stopColor: 'var(--art-sky-2)' }} />
          <stop offset="1" style={{ stopColor: 'var(--art-sky)' }} />
        </linearGradient>
        <radialGradient id="hero-glow">
          <stop offset="0" style={{ stopColor: 'var(--art-sun)', stopOpacity: 0.55 }} />
          <stop offset="1" style={{ stopColor: 'var(--art-sun)', stopOpacity: 0 }} />
        </radialGradient>
        <clipPath id="hero-frame">
          <rect width="560" height="520" rx="36" />
        </clipPath>
      </defs>

      <g clipPath="url(#hero-frame)">
        <rect width="560" height="520" fill="url(#hero-sky)" />
        <circle cx="408" cy="300" r="190" fill="url(#hero-glow)" />
        <circle cx="408" cy="300" r="84" style={s('art-sun')} />

        {/* Hills and the field, back to front. */}
        <path d="M0 318 C 90 280 190 300 270 318 S 450 288 560 304 V520 H0 Z" style={s('art-hill-1')} />
        <path d="M0 360 C 120 330 230 350 330 362 S 480 336 560 350 V520 H0 Z" style={s('art-hill-2')} />
        <path d="M0 402 C 140 384 300 392 560 396 V520 H0 Z" style={s('art-hill-3')} />
        <g stroke="var(--art-hill-2)" strokeWidth="2" opacity="0.7">
          {[-6, -4, -2, 0, 2, 4, 6, 8].map((n) => (
            <line key={n} x1={300 + n * 14} y1="398" x2={300 + n * 110} y2="520" />
          ))}
        </g>
      </g>

      {/* The phone on the call. */}
      <g transform="translate(64 112) rotate(-6 80 150)">
        <rect width="160" height="300" rx="28" style={s('art-phone')} />
        <rect x="9" y="12" width="142" height="276" rx="21" style={s('night')} />
        <rect x="60" y="20" width="40" height="7" rx="3.5" style={s('art-phone')} />
        <circle cx="80" cy="82" r="28" style={s('assistant')} />
        <path d="M89 75.5a10 10 0 1 0 1.9 8.6h-8.1" fill="none" stroke="var(--night)" strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" />
        <text x="80" y="136" textAnchor="middle" fontSize="17" fontWeight="700" fontFamily="inherit" style={s('on-night')}>G-One</text>
        <text x="80" y="156" textAnchor="middle" fontSize="12" fontFamily="inherit" style={s('on-night-muted')}>on the call</text>
        <g transform="translate(29 186)">
          {WAVE.map((h, i) => (
            <rect key={i} className="hero-wave" x={i * 10.5} y={20 - h / 2} width="5" height={h} rx="2.5" style={{ ...s('accent'), animationDelay: `${(i * 110) % 600}ms` }} />
          ))}
        </g>
        <circle cx="48" cy="252" r="15" style={s('bad')} />
        <path d="M41 252h14" stroke="var(--on-bad)" strokeWidth="3" strokeLinecap="round" />
        <circle cx="112" cy="252" r="15" style={s('night-raised')} />
        <path d="M107 246v12M117 246v12" stroke="var(--on-night-muted)" strokeWidth="2.5" strokeLinecap="round" />
      </g>

      {/* What only the farmer hears. */}
      <g transform="translate(36 428)">
        <rect width="196" height="40" rx="20" style={s('whisper-soft')} stroke="var(--whisper-edge)" strokeWidth="2" strokeDasharray="6 4" />
        <rect x="16" y="18" width="14" height="11" rx="2" style={s('whisper')} />
        <path d="M19 18v-3a4 4 0 0 1 8 0v3" fill="none" stroke="var(--whisper)" strokeWidth="2.2" />
        <text x="40" y="26" fontSize="14" fontWeight="700" fontFamily="inherit" style={s('whisper')}>Only the farmer hears</text>
      </g>

      {/* Speech in three scripts: decoration, not a list of tested languages. */}
      <Bubble x={250} y={54} w={120} label="हिंदी" lang="hi" tone="farmer" tail="left" />
      <Bubble x={396} y={92} w={128} label="বাংলা" lang="bn" tone="buyer" tail="right" />
      <Bubble x={262} y={176} w={118} label="ಕನ್ನಡ" lang="kn" tone="assistant" tail="left" />

      {/* A mandi crate of onions, with grain. */}
      <g transform="translate(338 368)">
        <ellipse cx="96" cy="112" rx="104" ry="12" fill="var(--art-phone)" opacity="0.18" />
        {[
          [34, 30], [62, 22], [92, 28], [122, 20], [150, 30], [48, 10], [78, 4], [108, 6], [136, 12],
        ].map(([cx, cy], i) => (
          <g key={i}>
            <circle cx={cx} cy={cy + 20} r="17" style={s('art-onion')} />
            <path d={`M${cx} ${cy + 3} q 3 -8 7 -10`} fill="none" stroke="var(--art-hill-2)" strokeWidth="3" strokeLinecap="round" />
            <ellipse cx={cx - 6} cy={cy + 14} rx="4" ry="6" fill="#fff" opacity="0.28" />
          </g>
        ))}
        <rect x="8" y="44" width="176" height="64" rx="8" style={s('art-crate')} />
        <rect x="8" y="44" width="176" height="12" rx="6" style={s('art-crate-dark')} />
        <path d="M8 76h176M8 92h176" stroke="var(--art-crate-dark)" strokeWidth="3" />
        <path d="M44 56v52M96 56v52M148 56v52" stroke="var(--art-crate-dark)" strokeWidth="3" opacity="0.6" />
        <g style={s('art-grain')}>
          <ellipse cx="-8" cy="104" rx="5" ry="3" transform="rotate(-30 -8 104)" />
          <ellipse cx="2" cy="110" rx="5" ry="3" transform="rotate(20 2 110)" />
          <ellipse cx="200" cy="106" rx="5" ry="3" transform="rotate(35 200 106)" />
        </g>
      </g>
    </svg>
  )
}
