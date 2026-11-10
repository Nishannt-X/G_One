# G-One

A phone-call voice middleman for Indian farmers. A farmer dials a normal number, talks to an AI assistant in Hindi,
English or Hinglish, and the assistant connects the right buyer, translates the conversation, privately advises the
farmer, and remembers past calls. Built on Gnani Prisma (speech to text) and Gnani Timbre (text to speech) for the
Gnani.ai Great Indian AI Internship Challenge.
Built on the base Repository of [G-One](https://github.com/Nishannt-X/G_One-AI) for the same.

Status: in development (deadline 2026-11-10). Start with [ROADMAP.md](ROADMAP.md) and [CLAUDE.md](CLAUDE.md).

## Phone as the buyer (local demo)

The laptop is the farmer on `#/demo`. A phone on the same network scans a QR code and joins as the buyer. This stays on
your network: a self-signed certificate and no public tunnel.

```sh
cd backend && ./mvnw spring-boot:run           # add G_ONE_DEMO_MODE=true to run offline with no keys
cd web && bun run dev:https                    # second terminal; prints https://<laptop-ip>:5173/
```

1. Connect the laptop to the phone's hotspot (or put both on one Wi-Fi network), then start the commands above.
2. Open `https://localhost:5173/#/demo` on the laptop and tap **Call as farmer**. The QR code and link appear in the
   buyer panel.
3. Scan the code, or type the link on the phone. Accept the certificate warning once.
4. Tap **Join call** and allow the microphone. G-One asks the buyer to say yes before connecting them. Use headphones on
   one side if the two devices are close, so that they do not hear each other.

`bun run dev` stays HTTP on localhost only. The certificate is written to `web/.dev-certs/` and is gitignored. If the
wrong address is shown, pick another under the QR code, set `G_ONE_LAN_HOST=<ip>` before `bun run dev:https`, or open
`#/demo?lan=<ip>`.

**Troubleshooting**

- *The phone cannot open the page.* Some hotspots and Wi-Fi networks isolate clients, so devices on them cannot reach
  each other, and nothing on the laptop can change that. Turn client isolation off if the phone offers it, or use a
  different network. Also check that the macOS firewall allows incoming connections for `bun`/`node`.
- *The microphone is blocked.* The phone needs the `https://` link. Then allow the microphone in the browser's site
  settings.
- *The page opens but says it could not reach the laptop.* The certificate exception may not cover the audio connection,
  which has been a problem on iPhones. Send `web/.dev-certs/cert.pem` to the phone, install it, and turn it on under
  Settings > General > About > Certificate Trust Settings. Remove it afterwards. This route has not been tested on a
  real phone.
