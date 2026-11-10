import type { ReactNode } from 'react'
import type { CallView } from '../lib/callState'
import DealPanel from './DealPanel'

interface Props {
  title: string
  /** Says which kind of conversation this page shows: the recorded sample, a browser call or a synthetic call. */
  mode: { text: string; tone: '' | 'badge-ok' | 'badge-warn' }
  /** Beside the mode, such as the call clock. */
  extra?: ReactNode
  actions: ReactNode
  /** The deal panel follows this call. */
  dealCall: CallView | null
  /** The conversation card: call bar, credit line, conversation and controls. */
  children: ReactNode
  /** Under the conversation and deal panel on small screens, beside them on large ones. */
  aside?: ReactNode
  footer?: ReactNode
  after?: ReactNode
}

/** The frame both the sample page and the call page share: a header row, the conversation, and the deal beside it. */
export default function Stage({ title, mode, extra, actions, dealCall, children, aside, footer, after }: Props) {
  return (
    <>
      <div className="mx-auto flex max-w-[1560px] flex-col gap-3 px-3 py-3 sm:px-4 lg:h-[calc(100dvh-4.0625rem)]">
        <div className="flex min-h-11 flex-wrap items-center justify-between gap-x-4 gap-y-2">
          <div className="flex min-w-0 flex-wrap items-center gap-3">
            <h1 className="text-2xl font-semibold">{title}</h1>
            <span data-testid="page-mode" className={`badge ${mode.tone}`}>{mode.text}</span>
            {extra}
          </div>
          <div className="flex flex-wrap items-center gap-3">{actions}</div>
        </div>

        <div className="grid min-h-0 flex-1 gap-3 lg:grid-cols-[minmax(0,1fr)_23.5rem]">
          <section aria-label="Conversation" className="card relative flex h-[calc(100dvh-5rem)] min-h-[34rem] min-w-0 flex-col overflow-hidden lg:h-auto lg:min-h-0">
            {children}
          </section>
          {aside}
          <div className="min-h-0 min-w-0">
            <DealPanel call={dealCall} />
          </div>
        </div>
        {footer}
      </div>
      {after}
    </>
  )
}
