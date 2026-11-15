import { describe, expect, it } from 'bun:test'
import { listProgress } from './listProgress'

// Five points whose bottom edges sit 100 px apart, the first at 500 px in the window.
const edges = [500, 600, 700, 800, 900]

describe('List reading progress', () => {
  it('shows the first point from the start, before its edge reaches the reading line', () => {
    expect(listProgress(edges, 100)).toBe(0)
    expect(listProgress(edges, 499)).toBe(0)
    expect(Math.floor(listProgress(edges, 100))).toBe(0)
  })

  it('reaches a point as its edge rises past the line and runs smoothly between points', () => {
    expect(listProgress(edges, 500)).toBe(0)
    expect(listProgress(edges, 550)).toBeCloseTo(0.5)
    expect(listProgress(edges, 600)).toBe(1)
    expect(listProgress(edges, 750)).toBeCloseTo(2.5)
  })

  it('stops at the last point once every edge has passed the line', () => {
    expect(listProgress(edges, 900)).toBe(4)
    expect(listProgress(edges, 5000)).toBe(4)
  })

  it('never goes below the first point, even with no points', () => {
    expect(listProgress([], 0)).toBe(0)
  })
})
