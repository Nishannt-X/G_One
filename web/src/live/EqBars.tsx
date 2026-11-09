const BARS = [0.5, 0.8, 0.35, 1, 0.6, 0.9, 0.4, 0.75, 0.55, 1, 0.45, 0.85, 0.3, 0.7, 0.95, 0.5, 0.65, 0.4]

/** Shows that a leg is active. Decorative: the console feed carries no audio, so these bars are not a level meter. */
export default function EqBars({ active, colorClass }: { active: boolean; colorClass: string }) {
  return (
    <div aria-hidden="true" className={`flex h-9 flex-1 items-center gap-[3px] ${colorClass}`}>
      {BARS.map((height, i) => (
        <span
          key={i}
          className={`block h-full w-1 origin-center rounded-full bg-current transition-opacity duration-300 ${active ? 'animate-eq opacity-100' : 'opacity-25'}`}
          style={active ? { animationDelay: `${(i * 67) % 600}ms`, height: `${height * 100}%` } : { transform: 'scaleY(0.14)' }}
        />
      ))}
    </div>
  )
}
