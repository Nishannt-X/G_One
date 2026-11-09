import type { CallView } from '../lib/callState'
import { rupees } from '../lib/format'
import { parseReference } from '../lib/pricing'

export function toolNote(tool: CallView['tools'][number]): { text: string; ok: boolean } {
  const name = tool.tool.toLowerCase()
  if (!tool.ok) return { text: `A ${name.replace(/_/g, ' ')} lookup did not work`, ok: false }
  if (name.includes('mandi')) {
    const ref = parseReference(tool.result)
    if (ref) return { text: `Checked mandi reference: ${rupees(ref.perQuintal)} a quintal${ref.market ? ` at ${ref.market}` : ''}${ref.date ? `, dated ${ref.date}` : ''}`, ok: true }
    return { text: 'Checked the mandi reference', ok: true }
  }
  if (name.includes('buyer')) {
    const match = /'([^']+)'/.exec(tool.result)
    return { text: match ? `Matched a buyer: ${match[1]}` : 'Looked for a buyer', ok: true }
  }
  if (name.includes('weather')) return { text: 'Checked the weather', ok: true }
  return { text: `Used ${name.replace(/_/g, ' ')}`, ok: true }
}
