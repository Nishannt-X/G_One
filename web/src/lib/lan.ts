/**
 * Finding the laptop's address on the local network, and the link a phone opens to join a call as the buyer. No DOM and
 * no Node imports: the page, vite.config.ts and scripts/dev-cert.ts all use it, and tests pass the interfaces in.
 */

/** The shape of Node's os.networkInterfaces(). */
export type NetInterfaces = Record<string, { address: string; family: string | number; internal: boolean }[] | undefined>

/** Tunnels, VPNs and containers: a phone on the hotspot usually cannot reach these. Listed last, not removed. */
const VIRTUAL = /^(utun|tun|tap|ppp|ipsec|bridge|vmnet|vboxnet|veth|docker|br-|awdl|llw|vethernet|virbr|wg|tailscale|zt)/i

export function isPrivateIpv4(address: string): boolean {
  const parts = address.split('.').map(Number)
  if (parts.length !== 4 || parts.some((p) => !Number.isInteger(p) || p < 0 || p > 255)) return false
  const [a, b] = parts
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168)
}

/** Lower is better: known phone hotspot subnets, then real adapters by how common the range is on home networks. */
function rank(name: string, address: string): number {
  if (VIRTUAL.test(name)) return 9
  if (address.startsWith('172.20.10.') || address.startsWith('192.168.43.')) return 0
  if (address.startsWith('192.168.')) return 1
  if (address.startsWith('10.')) return 2
  return 3
}

/**
 * The addresses a phone might reach this machine on, best first. An override (a host the owner typed) always leads.
 * Same input, same order.
 */
export function lanHosts(interfaces: NetInterfaces, override?: string | null): string[] {
  const found = Object.entries(interfaces)
    .flatMap(([name, entries]) =>
      (entries ?? [])
        .filter((e) => (e.family === 'IPv4' || e.family === 4) && !e.internal && isPrivateIpv4(e.address))
        .map((e) => ({ name, address: e.address })),
    )
    .sort((x, y) => rank(x.name, x.address) - rank(y.name, y.address) || x.name.localeCompare(y.name) || x.address.localeCompare(y.address))
    .map((e) => e.address)
  const wanted = override && isSafeHost(override.trim()) ? [override.trim()] : []
  return [...new Set([...wanted, ...found])]
}

/** A bare IPv4 address or hostname, so that a value from an env var or the URL cannot change the link's shape. */
export function isSafeHost(host: string): boolean {
  return /^[a-z0-9.-]{1,253}$/i.test(host)
}

export function joinUrl(host: string, port: number, callId: string): string {
  return `https://${host}:${port}/#/join?call=${encodeURIComponent(callId)}`
}

/** The call id in a #/join link, or null when it is missing or malformed. */
export function parseJoinCall(query: URLSearchParams): string | null {
  const call = query.get('call')?.trim() ?? ''
  return /^[A-Za-z0-9._:-]{1,128}$/.test(call) ? call : null
}

/**
 * True for a socket opened by a page this same server delivered. The backend only trusts localhost origins, so the
 * HTTPS dev proxy vouches for these and no others: a page from any other site keeps its own Origin and is refused.
 */
export function isSameOrigin(origin: string | undefined, host: string | undefined): boolean {
  if (!origin || !host) return false
  try {
    return new URL(origin).host === host
  } catch {
    return false
  }
}

/** The openssl config for a self-signed certificate valid for these hosts. */
export function certConfig(hosts: string[]): string {
  const names = hosts.map((h, i) => `${/^[\d.]+$/.test(h) ? 'IP' : 'DNS'}.${i + 1} = ${h}`)
  return [
    '[req]',
    'distinguished_name = dn',
    'x509_extensions = v3',
    'prompt = no',
    '[dn]',
    'CN = G-One local dev',
    '[v3]',
    'basicConstraints = critical, CA:TRUE',
    'keyUsage = critical, digitalSignature, keyEncipherment, keyCertSign',
    'extendedKeyUsage = serverAuth',
    'subjectAltName = @sans',
    '[sans]',
    ...names,
    '',
  ].join('\n')
}
