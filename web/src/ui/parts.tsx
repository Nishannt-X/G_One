import { useEffect, useRef, type ReactNode } from 'react'
import { useT } from '../lib/i18n'
import { AlertIcon } from './icons'

export function Card({ title, aside, children, className = '', labelId }: { title?: ReactNode; aside?: ReactNode; children: ReactNode; className?: string; labelId?: string }) {
  return (
    <section aria-labelledby={labelId} className={`card p-4 md:p-5 ${className}`}>
      {(title || aside) && (
        <header className="mb-3 flex flex-wrap items-center justify-between gap-2">
          {title && <h2 id={labelId} className="text-lg font-bold">{title}</h2>}
          {aside}
        </header>
      )}
      {children}
    </section>
  )
}

export function Empty({ icon, title, children, action }: { icon?: ReactNode; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 px-4 py-8 text-center">
      {icon && <div className="text-3xl text-faint">{icon}</div>}
      <p className="text-lg font-bold">{title}</p>
      {children && <p className="max-w-prose text-muted">{children}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  )
}

export function Skeleton({ className = 'h-5 w-full' }: { className?: string }) {
  return <div aria-hidden="true" className={`animate-shimmer rounded-lg bg-sunk ${className}`} />
}

/** A failure the viewer can read, with the fix in words and an optional way to retry. */
export function ErrorNote({ title, children, onRetry }: { title: string; children?: ReactNode; onRetry?: () => void }) {
  const t = useT()
  return (
    <div role="alert" className="flex flex-wrap items-start gap-3 rounded-xl border border-bad bg-bad-soft p-4 text-ink">
      <AlertIcon className="mt-0.5 text-bad" />
      <div className="min-w-0 flex-1 basis-56">
        <p className="font-bold">{title}</p>
        {children && <p className="mt-0.5 text-muted">{children}</p>}
      </div>
      {onRetry && <button type="button" className="btn-quiet" onClick={onRetry}>{t('retry')}</button>}
    </div>
  )
}

/** A modal on the native dialog element, which traps focus and closes on Escape for free. */
export function Dialog({ open, onClose, title, children }: { open: boolean; onClose: () => void; title: string; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])
  return (
    <dialog ref={ref} onClose={onClose} aria-labelledby="dialog-title" className="card m-auto w-[min(32rem,calc(100vw-2rem))] p-5 text-ink">
      <h2 id="dialog-title" className="text-xl font-bold">{title}</h2>
      <div className="mt-3">{open && children}</div>
    </dialog>
  )
}

export function Segmented<T extends string>({ label, value, options, onChange, disabled }: { label: string; value: T; options: { value: T; label: ReactNode }[]; onChange: (value: T) => void; disabled?: boolean }) {
  return (
    <div role="radiogroup" aria-label={label} className="grid auto-cols-fr grid-flow-col gap-1 rounded-xl bg-sunk p-1">
      {options.map((option) => (
        <label
          key={option.value}
          className={`flex min-h-11 cursor-pointer items-center justify-center gap-2 rounded-lg px-3 text-center font-semibold transition-colors has-[:focus-visible]:outline-3 has-[:focus-visible]:outline-offset-1 has-[:focus-visible]:outline-brand ${
            value === option.value ? 'bg-surface text-ink shadow-card' : 'text-muted hover:text-ink'
          } ${disabled ? 'cursor-not-allowed opacity-60' : ''}`}
        >
          <input type="radio" className="sr-only" disabled={disabled} checked={value === option.value} onChange={() => onChange(option.value)} />
          {option.label}
        </label>
      ))}
    </div>
  )
}

let scrollLocks = 0

/** Stops the page behind an open overlay from scrolling; counted, so stacked overlays release it once, together. */
function lockPageScroll(lock: boolean) {
  scrollLocks = Math.max(0, scrollLocks + (lock ? 1 : -1))
  document.documentElement.style.overflow = scrollLocks > 0 ? 'hidden' : ''
}

/**
 * A centred modal on the native dialog element, full screen on a phone. The browser keeps focus inside it, makes the
 * page behind inert and sends Escape to the topmost one only, so overlays stack: a second one opened later sits on top
 * and dims the first. Escape, a backdrop click or `onClose` from a button close it; `onClose` should change what
 * `open` says (a route), and the overlay closes when it does. Focus moves to the element marked `data-autofocus`, and
 * back to `returnFocus()` (as it was while open) on close. Children lay out the panel: a header, a body with
 * `min-h-0 flex-1 overflow-y-auto`, and a footer.
 */
export function Overlay({ open, onClose, labelledBy, returnFocus, children }: { open: boolean; onClose: () => void; labelledBy: string; returnFocus?: () => HTMLElement | null; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  const latest = useRef({ open, onClose, returnFocus, closing: false })
  const pressedBackdrop = useRef(false)
  useEffect(() => {
    latest.current.onClose = onClose
    // A close that did not change `open` (nothing to go back to) must not block the next one.
    latest.current.closing = false
    if (open) latest.current.returnFocus = returnFocus
  })
  useEffect(() => {
    const dialog = ref.current
    const state = latest.current
    state.open = open
    state.closing = false
    if (!dialog || !open) return
    if (!dialog.open) dialog.showModal()
    lockPageScroll(true)
    dialog.querySelector<HTMLElement>('[data-autofocus]')?.focus({ preventScroll: true })
    return () => {
      state.open = false
      lockPageScroll(false)
      if (dialog.open) dialog.close()
      state.returnFocus?.()?.focus({ preventScroll: true })
    }
  }, [open])
  const requestClose = () => {
    const state = latest.current
    if (!state.open || state.closing) return
    state.closing = true
    state.onClose()
  }
  return (
    <dialog
      ref={ref}
      aria-modal="true"
      aria-labelledby={labelledBy}
      // Escape is handled here so it closes this overlay only: Chrome groups dialogs opened without a click in
      // between (a deep link opens two at once) and would close them together. Any other native close (a system back
      // gesture) still reaches onClose.
      onKeyDown={(e) => {
        if (e.key !== 'Escape') return
        e.preventDefault()
        e.stopPropagation()
        requestClose()
      }}
      // A close event that arrives after the overlay reopened (another farmer in the same tick) is stale.
      onClose={() => { if (!ref.current?.open) requestClose() }}
      onPointerDown={(e) => { pressedBackdrop.current = e.target === e.currentTarget }}
      onClick={(e) => { if (e.target === e.currentTarget && pressedBackdrop.current) requestClose() }}
      className="fixed inset-0 m-0 h-dvh max-h-none w-full max-w-none overflow-hidden border-0 bg-transparent p-0 text-ink open:flex open:items-center open:justify-center sm:p-6"
    >
      <div className="animate-rise flex h-full w-full flex-col overflow-hidden bg-surface shadow-lift sm:h-auto sm:max-h-[90vh] sm:max-w-[720px] sm:rounded-2xl sm:border sm:border-line">
        {open && children}
      </div>
    </dialog>
  )
}
