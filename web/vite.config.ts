import { existsSync, readFileSync } from 'node:fs'
import { networkInterfaces } from 'node:os'
import { join } from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig, type Plugin } from 'vite'
import { isSameOrigin, lanHosts } from './src/lib/lan.ts'

// The Spring Boot backend runs on :8080 in development; G_ONE_BACKEND points the proxy elsewhere when 8080 is taken.
const backend = process.env.G_ONE_BACKEND ?? 'http://localhost:8080'

/** GET /__g-one/lan: where a phone can reach this dev server. The port is read per request, so it is the real one. */
function lanInfo(https: boolean): Plugin {
  return {
    name: 'g-one-lan-info',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use('/__g-one/lan', (req, res) => {
        res.setHeader('Content-Type', 'application/json')
        res.setHeader('Cache-Control', 'no-store')
        res.end(JSON.stringify({ https, port: req.socket.localPort, hosts: lanHosts(networkInterfaces(), process.env.G_ONE_LAN_HOST) }))
      })
    },
  }
}

function devCertificate() {
  const dir = join(import.meta.dirname, '.dev-certs')
  const key = join(dir, 'key.pem')
  const cert = join(dir, 'cert.pem')
  if (!existsSync(key) || !existsSync(cert)) throw new Error('No dev certificate. Start with: bun run dev:https')
  return { key: readFileSync(key), cert: readFileSync(cert) }
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // `bun run dev:https` is opt-in: only then does the server leave localhost, so plain `bun run dev` stays private.
  const https = mode === 'https'
  return {
    plugins: [react(), tailwindcss(), lanInfo(https)],
    server: {
      host: https ? true : undefined,
      https: https ? devCertificate() : undefined,
      proxy: {
        '/api': { target: backend, changeOrigin: true },
        '/ws': {
          target: backend,
          changeOrigin: true,
          ws: true,
          configure: https
            ? (proxy) => {
                proxy.on('proxyReqWs', (proxyReq, req) => {
                  if (isSameOrigin(req.headers.origin, req.headers.host)) proxyReq.setHeader('Origin', backend)
                })
              }
            : undefined,
        },
      },
    },
  }
})
