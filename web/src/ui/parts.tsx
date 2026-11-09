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

/** A panel from the right edge on the native dialog element, full width on a phone. */
export function Drawer({ open, onClose, label, children }: { open: boolean; onClose: () => void; label: string; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])
  return (
    <dialog ref={ref} onClose={onClose} aria-label={label} onClick={(e) => { if (e.target === ref.current) onClose() }} className="animate-drawer fixed inset-y-0 right-0 left-auto m-0 h-dvh max-h-dvh w-full max-w-[34rem] overflow-y-auto border-l border-line bg-surface text-ink shadow-lift">
      {open && children}
    </dialog>
  )
}
