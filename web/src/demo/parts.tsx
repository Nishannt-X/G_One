import type { ReactNode } from 'react'
import type { LegRole } from '../types/console'
import { Logo, StoreIcon, WheatIcon } from '../ui/icons'

const BARS = [0.45, 0.8, 0.35, 1, 0.6, 0.9, 0.4]

export function Waveform({ active, className = '' }: { active: boolean; className?: string }) {
  return (
    <span aria-hidden="true" className={`flex h-5 items-center gap-[3px] ${className}`}>
      {BARS.map((h, i) => (
        <span key={i} className={`block h-full w-[3px] origin-center rounded-full bg-current transition-opacity duration-300 ${active ? 'animate-eq opacity-100' : 'opacity-25'}`} style={active ? { height: `${h * 100}%`, animationDelay: `${(i * 90) % 500}ms` } : { transform: 'scaleY(0.18)' }} />
      ))}
    </span>
  )
}

export type Speaker = LegRole | 'assistant'

const AVATAR: Record<Speaker, { icon: ReactNode; tone: string }> = {
  farmer: { icon: <WheatIcon />, tone: 'bg-farmer-soft text-farmer' },
  buyer: { icon: <StoreIcon />, tone: 'bg-buyer-soft text-buyer' },
  assistant: { icon: <Logo className="text-[1.05em] text-line-strong" />, tone: 'bg-assistant-soft text-assistant' },
}

export function Avatar({ who, speaking = false, size = 'md' }: { who: Speaker; speaking?: boolean; size?: 'sm' | 'md' }) {
  const a = AVATAR[who]
  const box = size === 'sm' ? 'h-8 w-8 text-base' : 'h-11 w-11 text-xl'
  return (
    <span className={`relative grid shrink-0 place-items-center rounded-full ${box} ${a.tone}`}>
      {speaking && <span aria-hidden="true" className="animate-beat absolute inset-0 rounded-full border-2 border-current" />}
      {a.icon}
    </span>
  )
}
