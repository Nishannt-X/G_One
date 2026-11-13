import { describe, expect, it } from 'bun:test'
import { MAIN_NAV, parseRoute, redirectFor } from './route'

describe('parseRoute', () => {
  it('keeps the sample demo and the browser call on separate views', () => {
    expect(parseRoute('#/demo').view).toBe('demo')
    expect(parseRoute('#/demo?autoplay=1').query.get('autoplay')).toBe('1')
    expect(parseRoute('#/call').view).toBe('call')
    expect(parseRoute('#/call?lan=192.168.1.5').query.get('lan')).toBe('192.168.1.5')
  })

  it('keeps the other routes working', () => {
    for (const view of ['farmers', 'analytics', 'memory', 'buyers', 'simulator', 'join', 'sample-receipt'] as const) {
      expect(parseRoute(`#/${view}`).view).toBe(view)
    }
    expect(parseRoute('#/join?call=abc').query.get('call')).toBe('abc')
    expect(parseRoute('#/farmers/caller-1')).toMatchObject({ view: 'farmers', param: 'caller-1' })
  })

  it('treats #/how as a section of home, and unknown or empty hashes as home', () => {
    expect(parseRoute('#/how')).toMatchObject({ view: 'home', param: 'how' })
    expect(parseRoute('').view).toBe('home')
    expect(parseRoute('#/nowhere').view).toBe('home')
  })

  it('renders an old #/demo link with live call params as the call page', () => {
    const route = parseRoute('#/demo?lan=10.0.0.4')
    expect(route.view).toBe('call')
    expect(route.query.get('lan')).toBe('10.0.0.4')
  })
})

describe('redirectFor', () => {
  it('moves #/demo with live call params to #/call with the same query', () => {
    expect(redirectFor('#/demo?lan=192.168.1.5')).toBe('#/call?lan=192.168.1.5')
    expect(redirectFor('#/demo?call=c-1&lan=10.0.0.4')).toBe('#/call?call=c-1&lan=10.0.0.4')
  })

  it('leaves the sample demo and every other route alone', () => {
    for (const hash of ['#/demo', '#/demo?autoplay=1', '#/call?lan=10.0.0.4', '#/join?call=c-1', '#/', '']) expect(redirectFor(hash)).toBeNull()
  })
})

describe('the Analytics route', () => {
  it('opens Analytics at #/analytics and keeps old #/live links working', () => {
    expect(parseRoute('#/analytics').view).toBe('analytics')
    expect(parseRoute('#/live').view).toBe('analytics')
    expect(redirectFor('#/live')).toBe('#/analytics')
    expect(redirectFor('#/live/calls?id=c-1')).toBe('#/analytics/calls?id=c-1')
    expect(parseRoute('#/analytics/calls')).toMatchObject({ view: 'analytics', param: 'calls' })
    expect(redirectFor('#/analytics')).toBeNull()
  })
})

describe('MAIN_NAV', () => {
  it('lists Demo, Try a call and Farmers, in that order, with no How it works item', () => {
    expect(MAIN_NAV.map((n) => [n.label, n.href])).toEqual([['Demo', '#/demo'], ['Try a call', '#/call'], ['Farmers', '#/farmers']])
    for (const item of MAIN_NAV) expect(parseRoute(item.href).view).toBe(item.view)
  })
})
