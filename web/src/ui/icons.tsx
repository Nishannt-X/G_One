import type { ReactNode, SVGProps } from 'react'

type IconProps = SVGProps<SVGSVGElement>

function Svg({ children, ...props }: IconProps & { children: ReactNode }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" width="1.25em" height="1.25em" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="shrink-0" {...props}>
      {children}
    </svg>
  )
}

export const LockIcon = (p: IconProps) => <Svg {...p}><rect x="4" y="11" width="16" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></Svg>
export const PhoneIcon = (p: IconProps) => <Svg {...p}><path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3 19.5 19.5 0 0 1-6-6 19.8 19.8 0 0 1-3-8.7A2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L8.1 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2Z" /></Svg>
export const MicIcon = (p: IconProps) => <Svg {...p}><rect x="9" y="2" width="6" height="12" rx="3" /><path d="M5 11a7 7 0 0 0 14 0M12 18v4M8 22h8" /></Svg>
export const MicOffIcon = (p: IconProps) => <Svg {...p}><path d="m2 2 20 20M9 9v2a3 3 0 0 0 5 2.2M15 9.3V5a3 3 0 0 0-5.9-.7M19 11a7 7 0 0 1-1 3.6M5 11a7 7 0 0 0 10.6 6M12 18v4M8 22h8" /></Svg>
export const MemoryIcon = (p: IconProps) => <Svg {...p}><path d="M12 8a4 4 0 1 0-4 4M12 8a4 4 0 1 1 4 4M12 8v13M8 12a4 4 0 1 0 4 4M16 12a4 4 0 1 1-4 4" /></Svg>
export const BuyersIcon = (p: IconProps) => <Svg {...p}><path d="M3 9.5 5 4h14l2 5.5M4 9.5V20h16V9.5M3 9.5a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0M9 20v-5h6v5" /></Svg>
export const SunIcon = (p: IconProps) => <Svg {...p}><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></Svg>
export const MoonIcon = (p: IconProps) => <Svg {...p}><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" /></Svg>
export const CheckIcon = (p: IconProps) => <Svg {...p}><path d="m5 12 5 5L20 7" /></Svg>
export const CrossIcon = (p: IconProps) => <Svg {...p}><path d="M6 6l12 12M18 6 6 18" /></Svg>
export const AlertIcon = (p: IconProps) => <Svg {...p}><path d="M12 3 2 20h20L12 3ZM12 10v4M12 17h.01" /></Svg>
export const PlayIcon = (p: IconProps) => <Svg {...p}><path d="M7 4v16l13-8L7 4Z" /></Svg>
export const SearchIcon = (p: IconProps) => <Svg {...p}><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></Svg>
export const ChevronIcon = (p: IconProps) => <Svg {...p}><path d="m6 9 6 6 6-6" /></Svg>
export const SendIcon = (p: IconProps) => <Svg {...p}><path d="M22 2 11 13M22 2l-7 20-4-9-9-4 20-7Z" /></Svg>
export const TrashIcon = (p: IconProps) => <Svg {...p}><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" /></Svg>
export const MenuIcon = (p: IconProps) => <Svg {...p}><path d="M4 7h16M4 12h16M4 17h16" /></Svg>
export const StoreIcon = (p: IconProps) => <Svg {...p}><path d="M3 9.5 5 4h14l2 5.5M4 9.5V20h16V9.5M3 9.5a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0M9 20v-5h6v5" /></Svg>
export const PauseIcon = (p: IconProps) => <Svg {...p}><path d="M8 5v14M16 5v14" /></Svg>
export const ArrowIcon = (p: IconProps) => <Svg {...p}><path d="M5 12h14M13 6l6 6-6 6" /></Svg>
export const LanguageIcon = (p: IconProps) => <Svg {...p}><path d="M4 5h9M8.5 3v2M11 5c-.8 4-3.4 7.4-7 9M6 9c1.2 2.3 3.2 4.1 5.5 5M13 21l4-10 4 10M14.5 18h5" /></Svg>
export const ShieldIcon = (p: IconProps) => <Svg {...p}><path d="M12 3 4 6v6c0 4.5 3.2 7.8 8 9 4.8-1.2 8-4.5 8-9V6l-8-3Z" /></Svg>
export const HandshakeIcon = (p: IconProps) => <Svg {...p}><path d="m11 17 2 2a2 2 0 0 0 3-3M14 14l2.5 2.5a2 2 0 0 0 3-3L15 9l-2 2a2.1 2.1 0 0 1-3-3l3-3h3l5 5M3 5l5 0 3 3M3 14l5 5 2-2" /></Svg>
export const ClockIcon = (p: IconProps) => <Svg {...p}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></Svg>
export const EarIcon = (p: IconProps) => <Svg {...p}><path d="M6 9a6 6 0 1 1 11 3.3c-.9 1.3-2 2-2.5 3.2-.5 1.3-.9 3.5-3 4.3-1.6.6-3.1-.3-3.5-1.8" /><path d="M9.5 10a3 3 0 0 1 6 0c0 1.2-1 1.8-1.7 2.4" /></Svg>
export const EarOffIcon = (p: IconProps) => <Svg {...p}><path d="m3 3 18 18" /><path d="M6.3 7A6 6 0 0 1 17 12.3M15 15.2c-.2.1-.4.2-.5.3-.5 1.3-.9 3.5-3 4.3-1.6.6-3.1-.3-3.5-1.8" /></Svg>
export const SkipIcon = (p: IconProps) => <Svg {...p}><path d="M5 5v14l11-7L5 5ZM19 5v14" /></Svg>
export const RestartIcon = (p: IconProps) => <Svg {...p}><path d="M3 12a9 9 0 1 0 3-6.7L3 8" /><path d="M3 3v5h5" /></Svg>
export const WheatIcon = (p: IconProps) => <Svg {...p}><path d="M12 22V9M12 13c-3 0-5-2-5-5 3 0 5 2 5 5ZM12 13c3 0 5-2 5-5-3 0-5 2-5 5ZM12 19c-3 0-5-2-5-5 3 0 5 2 5 5ZM12 19c3 0 5-2 5-5-3 0-5 2-5 5ZM12 7c-1.5-1.2-1.5-3.3 0-5 1.5 1.7 1.5 3.8 0 5Z" /></Svg>

export const BeakerIcon = (p: IconProps) => <Svg {...p}><path d="M9 3h6M10 3v6L4.5 18.5A1.7 1.7 0 0 0 6 21h12a1.7 1.7 0 0 0 1.5-2.5L14 9V3M7.5 14h9" /></Svg>
export const GlobeIcon = (p: IconProps) => <Svg {...p}><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3Z" /></Svg>
export const UserCheckIcon = (p: IconProps) => <Svg {...p}><circle cx="9" cy="8" r="4" /><path d="M2 21a7 7 0 0 1 14 0M16 11l2 2 4-4" /></Svg>
export const ExternalIcon = (p: IconProps) => <Svg {...p}><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" /></Svg>
export const DoubleCheckIcon = (p: IconProps) => <Svg {...p}><path d="m2 12 5 5L18 6M12 16l1 1L22 8" /></Svg>

/** The G-One mark: two people joined through the assistant in the middle. */
export function Logo(p: IconProps) {
  return (
    <svg aria-hidden="true" viewBox="0 0 32 32" width="1.75em" height="1.75em" className="shrink-0" {...p}>
      <path d="M6 16h20" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
      <circle cx="5.5" cy="16" r="3.5" fill="var(--farmer)" />
      <circle cx="26.5" cy="16" r="3.5" fill="var(--buyer)" />
      <circle cx="16" cy="16" r="7" fill="var(--assistant)" />
      <path d="M19 13.6a3.7 3.7 0 1 0 .7 3.2h-3" fill="none" stroke="var(--surface)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}
