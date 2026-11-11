import type { ReactNode } from 'react'
import type { CallView } from '../lib/callState'
import { useI18n } from '../lib/i18n'
import CallHeader from './CallHeader'
import DealCard from './DealCard'
import EndReasonBanner from './EndReasonBanner'
import LatencyHud from './LatencyHud'
import MemoryGlance from './MemoryGlance'
import PriceMeter from './PriceMeter'
import Stage from './Stage'
import ToolsCard from './ToolsCard'
import WhisperPanel from './WhisperPanel'

/**
 * One call on the cards: the live call and a stored call share this layout. `main` fills the wide column: the live
 * subtitles for a live call, the key points for a stored one (a stored call never has a conversation to show).
 */
export default function CallBoard({ call, calls, onPick, main }: { call: CallView; calls?: CallView[]; onPick?: (callId: string) => void; main: ReactNode }) {
  const { t } = useI18n()
  return (
    <>
      <CallHeader call={call} calls={calls ?? [call]} onPick={onPick ?? (() => {})} />
      <EndReasonBanner call={call} />
      {(call.origin === 'mock' || call.origin === 'sample') && (
        <p role="note" className="rounded-xl border border-dashed border-whisper-edge bg-whisper-soft px-4 py-2 font-semibold text-whisper">
          {t(call.origin === 'mock' ? 'banner_mock' : 'banner_sample')}
        </p>
      )}
      <Stage call={call} />
      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_23rem]">
        {main}
        <div className="space-y-4">
          <WhisperPanel call={call} />
          <DealCard call={call} />
        </div>
      </div>
      <div className="grid items-start gap-4 md:grid-cols-2 xl:grid-cols-4">
        <PriceMeter call={call} />
        <LatencyHud call={call} />
        <MemoryGlance call={call} />
        <ToolsCard call={call} />
      </div>
    </>
  )
}
