import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useI18n } from '../lib/i18n'
import { ADVANCED_VIEWS, MAIN_NAV, type Route } from '../lib/route'
import { useTheme } from '../lib/theme'
import { ChevronIcon, CrossIcon, Logo, MenuIcon, MoonIcon, PhoneIcon, SunIcon } from './icons'

const ADVANCED: { href: string; view: Route['view']; label: string; note: string }[] = [
  { href: '#/analytics', view: 'analytics', label: 'Analytics', note: 'The live call, and the calls kept with consent' },
  { href: '#/buyers', view: 'buyers', label: 'Buyer directory', note: 'The synthetic buyers G-One can match' },
  { href: '#/memory', view: 'memory', label: 'Memory lookup', note: 'Find a farmer by caller key' },
]

/** The full-width call pages, which already link to each other. */
const STAGE_VIEWS: readonly Route['view'][] = ['demo', 'call']

function Brand() {
  return (
    <a href="#/" className="flex items-center gap-2 rounded-lg text-ink" aria-label="G-One home">
      <Logo className="text-[1.15rem] text-line-strong" />
      <span className="text-xl font-bold tracking-tight">G-One</span>
    </a>
  )
}

function AdvancedMenu({ route }: { route: Route }) {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  const active = ADVANCED_VIEWS.includes(route.view)

  useEffect(() => {
    if (!open) return
    const onPointer = (e: PointerEvent) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false)
        box.current?.querySelector('button')?.focus()
      }
    }
    document.addEventListener('pointerdown', onPointer)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onPointer)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div ref={box} className="relative">
      <button type="button" className="nav-link" aria-expanded={open} aria-haspopup="true" aria-current={active ? 'page' : undefined} onClick={() => setOpen((o) => !o)}>
        Advanced <ChevronIcon className={`transition-transform duration-200 ${open ? 'rotate-180' : ''}`} />
      </button>
      {open && (
        <div className="card animate-rise absolute top-full left-0 z-50 mt-3 w-72 p-1.5 shadow-lift">
          {ADVANCED.map((item) => (
            <a key={item.href} href={item.href} onClick={() => setOpen(false)} aria-current={route.view === item.view ? 'page' : undefined} className="block rounded-xl px-3 py-2 transition-colors hover:bg-sunk aria-[current=page]:bg-sunk">
              <span className="block font-semibold">{item.label}</span>
              <span className="block text-sm text-muted">{item.note}</span>
            </a>
          ))}
        </div>
      )}
    </div>
  )
}

function ThemeButton() {
  const { theme, toggle } = useTheme()
  return (
    <button type="button" onClick={toggle} className="btn-ghost w-11 px-0" aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}>
      {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
    </button>
  )
}

/** The older views are translated, so only they get the language switch. */
function LanguageButton() {
  const { lang, setLang } = useI18n()
  return (
    <button type="button" className="btn-ghost px-3 text-sm" onClick={() => setLang(lang === 'en' ? 'hi' : 'en')} aria-label="Switch console language between English and Hindi">
      {lang === 'en' ? <span lang="hi">हिंदी</span> : 'English'}
    </button>
  )
}

function TopNav({ route }: { route: Route }) {
  const [menu, setMenu] = useState(false)
  const advanced = ADVANCED_VIEWS.includes(route.view)
  const close = () => setMenu(false)
  return (
    <header className="sticky top-0 z-40 border-b border-line bg-page/90 backdrop-blur-md">
      <div className={`shell flex h-16 items-center justify-between gap-3 ${STAGE_VIEWS.includes(route.view) ? 'max-w-[1560px] lg:px-4' : ''}`}>
        <div className="flex items-center gap-6">
          <Brand />
          <nav aria-label="Main" className="hidden items-center gap-1 md:flex">
            {MAIN_NAV.map((item) => (
              <a key={item.href} className="nav-link" href={item.href} aria-current={route.view === item.view ? 'page' : undefined}>{item.label}</a>
            ))}
            <AdvancedMenu route={route} />
          </nav>
        </div>
        <div className="flex items-center gap-1">
          {advanced && <LanguageButton />}
          <ThemeButton />
          {!STAGE_VIEWS.includes(route.view) && (
            <a href="#/demo" className="btn-accent ml-1 hidden sm:inline-flex">
              <PhoneIcon /> Try the demo
            </a>
          )}
          <button type="button" className="btn-ghost w-11 px-0 md:hidden" aria-expanded={menu} aria-controls="mobile-menu" aria-label={menu ? 'Close menu' : 'Open menu'} onClick={() => setMenu((m) => !m)}>
            {menu ? <CrossIcon /> : <MenuIcon />}
          </button>
        </div>
      </div>
      {menu && (
        <nav id="mobile-menu" aria-label="Mobile" className="animate-rise border-t border-line bg-page md:hidden">
          <div className="shell space-y-1 py-3">
            {MAIN_NAV.map((item) => (
              <a key={item.href} onClick={close} className="block rounded-xl px-3 py-2.5 text-lg font-semibold hover:bg-sunk" href={item.href} aria-current={route.view === item.view ? 'page' : undefined}>{item.label}</a>
            ))}
            <p className="px-3 pt-3 text-sm font-bold tracking-wide text-faint">Advanced</p>
            {ADVANCED.map((item) => (
              <a key={item.href} onClick={close} className="block rounded-xl px-3 py-2 font-medium text-muted hover:bg-sunk" href={item.href}>{item.label}</a>
            ))}
            <a onClick={close} href="#/demo" className="btn-accent mt-3 flex w-full"><PhoneIcon /> Try the demo</a>
          </div>
        </nav>
      )}
    </header>
  )
}

function Footer() {
  return (
    <footer className="mt-auto border-t border-line bg-surface">
      <div className="shell grid gap-8 py-10 md:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)_minmax(0,1fr)] md:gap-10 md:py-12">
        <div className="max-w-sm space-y-3">
          <Brand />
          <p className="text-muted">A phone-call voice assistant that stands between a farmer and a buyer, and looks out for the farmer.</p>
        </div>
        <div className="space-y-2 text-sm text-muted">
          <h2 className="text-sm font-bold tracking-wide text-ink">Credits</h2>
          <p>Speech: Gnani Prisma (speech to text) and Gnani Timbre (voice).</p>
          <p>Translation: Sarvam.</p>
        </div>
        <div className="space-y-2 text-sm text-muted">
          <h2 className="text-sm font-bold tracking-wide text-ink">About this demo</h2>
          <p>Synthetic data only. Every name, number and price is made up, and nothing is sent.</p>
          <p>A deal here is a record of what was said on a call, not a contract.</p>
        </div>
      </div>
      <div className="border-t border-line">
        <p className="shell py-4 text-sm text-faint">Built for the Gnani.ai Great Indian AI Internship Challenge.</p>
      </div>
    </footer>
  )
}

/** Sticky nav, the page with a soft entrance on each view change, and the footer. */
export default function Shell({ route, children }: { route: Route; children: ReactNode }) {
  const pageKey = route.view
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'instant' })
  }, [pageKey])
  return (
    <div className="flex min-h-dvh flex-col">
      <a href="#main" onClick={(e) => { e.preventDefault(); document.getElementById('main')?.focus() }} className="sr-only focus:not-sr-only focus:absolute focus:top-3 focus:left-3 focus:z-50 focus:rounded-lg focus:bg-surface focus:px-3 focus:py-2">Skip to content</a>
      <TopNav route={route} />
      <main id="main" tabIndex={-1} key={pageKey} className="animate-page flex-1 outline-none">{children}</main>
      <Footer />
    </div>
  )
}
