import { describe, expect, it } from 'bun:test'
import { certConfig, isPrivateIpv4, isSameOrigin, isSafeHost, joinUrl, lanHosts, parseJoinCall, type NetInterfaces } from './lan'

const v4 = (address: string, internal = false) => ({ address, family: 'IPv4', internal })

describe('isPrivateIpv4', () => {
  it('accepts the three private ranges and their edges', () => {
    for (const a of ['10.0.0.1', '172.16.0.1', '172.31.255.1', '192.168.1.5', '172.20.10.4']) expect(isPrivateIpv4(a)).toBe(true)
  })
  it('rejects public, link-local, loopback and malformed addresses', () => {
    for (const a of ['8.8.8.8', '172.15.0.1', '172.32.0.1', '169.254.1.1', '127.0.0.1', '10.0.0', '10.0.0.999', 'abc']) expect(isPrivateIpv4(a)).toBe(false)
  })
})

describe('lanHosts', () => {
  it('skips loopback, IPv6, public and link-local entries', () => {
    const interfaces: NetInterfaces = {
      lo0: [v4('127.0.0.1', true)],
      en0: [{ address: 'fe80::1', family: 'IPv6', internal: false }, v4('169.254.3.2'), v4('8.8.8.8')],
    }
    expect(lanHosts(interfaces)).toEqual([])
  })

  it('prefers an iPhone hotspot address over other private ranges', () => {
    expect(lanHosts({ en5: [v4('192.168.1.9')], en0: [v4('172.20.10.4')], en7: [v4('10.1.2.3')] })).toEqual(['172.20.10.4', '192.168.1.9', '10.1.2.3'])
  })

  it('prefers an Android hotspot address', () => {
    expect(lanHosts({ en5: [v4('192.168.1.9')], en0: [v4('192.168.43.7')] })[0]).toBe('192.168.43.7')
  })

  it('lists VPN and container adapters last, even on a preferred range', () => {
    expect(lanHosts({ utun3: [v4('192.168.43.2')], docker0: [v4('172.17.0.1')], en0: [v4('10.0.0.8')] })).toEqual(['10.0.0.8', '172.17.0.1', '192.168.43.2'])
  })

  it('accepts the numeric family of older Node versions', () => {
    expect(lanHosts({ en0: [{ address: '192.168.1.2', family: 4, internal: false }] })).toEqual(['192.168.1.2'])
  })

  it('puts a valid override first without repeating it', () => {
    const interfaces = { en0: [v4('172.20.10.4')] }
    expect(lanHosts(interfaces, ' 192.168.0.50 ')).toEqual(['192.168.0.50', '172.20.10.4'])
    expect(lanHosts(interfaces, '172.20.10.4')).toEqual(['172.20.10.4'])
  })

  it('ignores an override that is not a bare host', () => {
    expect(lanHosts({ en0: [v4('172.20.10.4')] }, 'evil.test/#/x')).toEqual(['172.20.10.4'])
  })

  it('gives the same order whatever order the interfaces come in', () => {
    const a = lanHosts({ en1: [v4('192.168.1.2')], en0: [v4('192.168.2.2')] })
    const b = lanHosts({ en0: [v4('192.168.2.2')], en1: [v4('192.168.1.2')] })
    expect(a).toEqual(b)
  })
})

describe('joinUrl and parseJoinCall', () => {
  it('builds an https link that a join route reads back', () => {
    const url = joinUrl('172.20.10.4', 5173, 'call-123e4567-e89b')
    expect(url).toBe('https://172.20.10.4:5173/#/join?call=call-123e4567-e89b')
    expect(parseJoinCall(new URLSearchParams(url.split('?')[1]))).toBe('call-123e4567-e89b')
  })

  it('encodes characters that would break the link', () => {
    expect(joinUrl('a.local', 1, 'a&b c')).toBe('https://a.local:1/#/join?call=a%26b%20c')
  })

  it('rejects a missing, blank or malformed call id', () => {
    for (const q of ['', 'call=', 'call=%20', 'call=a%20b', 'call=%3Cscript%3E', `call=${'x'.repeat(129)}`]) {
      expect(parseJoinCall(new URLSearchParams(q))).toBeNull()
    }
  })
})

describe('isSafeHost', () => {
  it('allows addresses and hostnames only', () => {
    expect(isSafeHost('172.20.10.4')).toBe(true)
    expect(isSafeHost('my-laptop.local')).toBe(true)
    for (const h of ['', 'a b', 'a/b', 'a:1', 'a#b', 'a?b', 'user@a']) expect(isSafeHost(h)).toBe(false)
  })
})

describe('isSameOrigin', () => {
  it('matches a page served by this host', () => {
    expect(isSameOrigin('https://172.20.10.4:5173', '172.20.10.4:5173')).toBe(true)
  })
  it('refuses other sites, a missing origin and junk', () => {
    expect(isSameOrigin('https://evil.test', '172.20.10.4:5173')).toBe(false)
    expect(isSameOrigin('https://172.20.10.4:9999', '172.20.10.4:5173')).toBe(false)
    expect(isSameOrigin(undefined, '172.20.10.4:5173')).toBe(false)
    expect(isSameOrigin('null', '172.20.10.4:5173')).toBe(false)
  })
})

describe('certConfig', () => {
  it('lists hostnames as DNS names and addresses as IPs', () => {
    const config = certConfig(['localhost', '127.0.0.1', '172.20.10.4'])
    expect(config).toContain('DNS.1 = localhost')
    expect(config).toContain('IP.2 = 127.0.0.1')
    expect(config).toContain('IP.3 = 172.20.10.4')
  })
})
