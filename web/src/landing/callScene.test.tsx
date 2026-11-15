import { describe, expect, it } from 'bun:test'
import { renderToStaticMarkup } from 'react-dom/server'
import CallScene, { type SceneSize } from './CallScene'

const render = (step: number, size: SceneSize = 'roomy') => renderToStaticMarkup(<CallScene step={step} size={size} />)

/** Each pane's opening tag, in order. */
const panes = (html: string) => [...html.matchAll(/<div[^>]*data-pane="(\d+)"[^>]*>/g)].map((m) => ({ index: Number(m[1]), tag: m[0] }))
const visible = (html: string) => panes(html).filter((p) => !p.tag.includes('aria-hidden'))

/** The text of pane `i`, up to the next pane or the end of the scene. */
const paneText = (html: string, i: number) => {
  const start = html.indexOf(`data-pane="${i}"`)
  const end = i < 4 ? html.indexOf(`data-pane="${i + 1}"`, start) : html.length
  return html.slice(start, end).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ')
}

describe('Call scene', () => {
  it('has five panes and shows exactly the one for the current step, hiding the rest from assistive tech', () => {
    for (let step = 0; step < 5; step++) {
      const html = render(step)
      expect(panes(html).map((p) => p.index)).toEqual([0, 1, 2, 3, 4])
      const shown = visible(html)
      expect(shown.map((p) => p.index)).toEqual([step])
      expect(shown[0].tag).toContain('is-current')
      for (const p of panes(html).filter((p) => p.index !== step)) expect(p.tag).toContain('aria-hidden="true"')
    }
  })

  it('marks panes before and after the current one, so a change animates the way the visitor scrolled', () => {
    for (let step = 0; step < 5; step++) {
      for (const p of panes(render(step))) {
        const expected = p.index < step ? 'is-before' : p.index > step ? 'is-after' : 'is-current'
        expect(p.tag).toContain(expected)
        for (const other of ['is-before', 'is-after', 'is-current'].filter((c) => c !== expected)) expect(p.tag).not.toContain(other)
      }
    }
  })

  it('keeps an out-of-range step inside the scene', () => {
    expect(visible(render(-3)).map((p) => p.index)).toEqual([0])
    expect(visible(render(99)).map((p) => p.index)).toEqual([4])
  })

  it('shows no step counter, only the illustrative label', () => {
    for (let step = 0; step < 5; step++) expect(render(step)).not.toMatch(/\d of 5/)
    expect(render(0)).toContain('Illustrative call')
  })

  it('keeps every pane and its words when compact, at phone sizes on every width', () => {
    for (let step = 0; step < 5; step++) {
      const full = render(step)
      const compact = render(step, 'compact')
      expect(visible(compact).map((p) => p.index)).toEqual([step])
      for (let i = 0; i < 5; i++) expect(paneText(compact, i)).toBe(paneText(full, i))
      expect(compact).toContain('Illustrative call')
      expect(compact).not.toMatch(/sm:/)
    }
  })

  it("keeps a mini scene to each pane's label and main line (plus any English line), word for word, one pane at a time", () => {
    /** Only the words of pane `i`, without the rest of its opening tag. */
    const words = (html: string, i: number) => {
      const text = paneText(html, i)
      return text.slice(text.indexOf('>') + 1).replace(/<.*$/, '').trim()
    }
    for (let step = 0; step < 5; step++) {
      const full = render(step)
      const mini = render(step, 'mini')
      expect(panes(mini).map((p) => p.index)).toEqual([0, 1, 2, 3, 4])
      expect(visible(mini).map((p) => p.index)).toEqual([step])
      expect(mini).toContain('Illustrative call')
      expect(mini).not.toMatch(/ sm:/)
      for (let i = 0; i < 5; i++) {
        // A label, a main line and any English line, all of them in the full pane too, in the same order.
        expect(words(mini, i).length).toBeGreaterThan(0)
        expect(words(full, i)).toContain(words(mini, i))
      }
    }
  })

  it("keeps the main line's language on a mini pane", () => {
    const mini = render(0, 'mini')
    expect(mini).toMatch(/<p lang="kn"[^>]*>ನಮಸ್ಕಾರ ಬಸವರಾಜ್/)
    expect(mini).toMatch(/<p lang="bn"[^>]*>কুইন্টাল প্রতি/)
  })

  it('glosses the Kannada and Bengali lines in English on a mini pane, as the full pane does', () => {
    const mini = render(0, 'mini')
    const full = render(0)
    const kn = 'Namaskara, Basavaraj! Last time you asked about the onion rate, I remember.'
    const bn = 'I will give ₹1,900 a quintal.'
    for (const [i, gloss] of [[1, kn], [3, bn]] as const) {
      expect(paneText(mini, i)).toContain(gloss)
      expect(paneText(full, i)).toContain(gloss)
    }
    expect(mini).toMatch(/ನೆನಪಿದೆ.<\/p><p lang="en"[^>]*>Namaskara, Basavaraj!/)
    expect(mini).toMatch(/টাকা দেব।<\/p><p lang="en"[^>]*>I will give ₹1,900 a quintal.<\/p>/)
    // Panes already in English get no second line.
    const paneMarkup = (i: number) => mini.slice(mini.indexOf(`data-pane="${i}"`), i < 4 ? mini.indexOf(`data-pane="${i + 1}"`) : mini.length)
    for (const i of [0, 2, 4]) expect(paneMarkup(i).match(/<p /g)).toHaveLength(2)
    for (const i of [1, 3]) expect(paneMarkup(i).match(/<p /g)).toHaveLength(3)
  })

  it('never plays by itself and has no controls of its own: the page scroll drives it', () => {
    const html = render(2)
    expect(html).not.toMatch(/Pause the animation|Play the animation/)
    expect(html).not.toContain('<button')
  })

  it('keeps the yes to joining the call apart from the yes to the terms', () => {
    const html = render(0)
    const join = paneText(html, 2)
    expect(join).toContain('asks the buyer first')
    expect(join).toContain('A yes to joining the call, not to any price.')
    expect(join).not.toMatch(/₹/)
    const deal = paneText(html, 4)
    expect(deal).toContain('Read back to both, then a yes from each')
    expect(deal).toContain('Farmer said yes')
    expect(deal).toContain('Buyer said yes')
    expect(deal).toContain('A record of the call, not a contract.')
  })

  it('keeps the buyer off the line until they are asked', () => {
    expect(paneText(render(0), 0)).toContain('The buyer is not on the line yet.')
  })
})
