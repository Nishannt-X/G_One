/**
 * Makes the self-signed certificate for `bun run dev:https`, so a phone on the same network gets a secure page and may
 * use its microphone. Writes web/.dev-certs/ (gitignored). Reuses the certificate unless this machine's addresses
 * changed or it is close to expiry. It stays on this machine; nothing is uploaded.
 */
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { networkInterfaces } from 'node:os'
import { join } from 'node:path'
import { certConfig, lanHosts } from '../src/lib/lan.ts'

const VALID_DAYS = 30
const RENEW_AFTER_MS = (VALID_DAYS - 2) * 24 * 60 * 60 * 1000

const dir = join(import.meta.dirname, '..', '.dev-certs')
const key = join(dir, 'key.pem')
const cert = join(dir, 'cert.pem')
const hostsFile = join(dir, 'hosts.txt')
const configFile = join(dir, 'openssl.cnf')

const hosts = ['localhost', '127.0.0.1', ...lanHosts(networkInterfaces(), process.env.G_ONE_LAN_HOST)]

function isCurrent(): boolean {
  if (!existsSync(key) || !existsSync(cert) || !existsSync(hostsFile)) return false
  if (Date.now() - statSync(cert).mtimeMs > RENEW_AFTER_MS) return false
  const covered = new Set(readFileSync(hostsFile, 'utf8').split('\n'))
  return hosts.every((h) => covered.has(h))
}

if (isCurrent()) {
  console.log(`Dev certificate is current for: ${hosts.join(', ')}`)
} else {
  mkdirSync(dir, { recursive: true })
  writeFileSync(configFile, certConfig(hosts))
  const result = spawnSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', String(VALID_DAYS), '-keyout', key, '-out', cert, '-config', configFile], { encoding: 'utf8' })
  if (result.error || result.status !== 0) {
    console.error(`Could not create the dev certificate with openssl: ${result.error?.message ?? result.stderr}`)
    console.error('openssl ships with macOS. On Windows, run this from Git Bash, or install openssl and add it to PATH.')
    process.exit(1)
  }
  writeFileSync(hostsFile, hosts.join('\n'))
  console.log(`Created a dev certificate for: ${hosts.join(', ')}`)
}
