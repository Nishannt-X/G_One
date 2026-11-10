# G-One

A phone-call voice middleman for Indian farmers. A farmer dials a normal number, talks to an AI assistant in Hindi,
English or Hinglish, and the assistant connects the right buyer, translates the conversation, privately advises the
farmer, and remembers past calls. Built on Gnani Prisma (speech to text) and Gnani Timbre (text to speech) for the
Gnani.ai Great Indian AI Internship Challenge.

Built on the base Repository of [G-One](https://github.com/Nishannt-X/G_One-AI) for the same.

Status: in development (deadline 2026-11-10). Start with [ROADMAP.md](ROADMAP.md) and [CLAUDE.md](CLAUDE.md).

## Farmer memory demo

G-One remembers a short gist of each farmer's calls, keyed by a salted hash of the number (never the number itself). A
synthetic farmer, Suresh, is seeded for the "it remembers me" moment.

**Env (synthetic values only).** Spring Boot reads only real environment variables here; it does not read
`.env.local` by itself. Put these in an untracked `.env.local` at the repo root (see `.env.example`):

```sh
G_ONE_MEMORY_HMAC_KEY=<your own base64 key>        # openssl rand -base64 32; never commit it
G_ONE_MEMORY_SEED_FILE=classpath:memory/seed-suresh.json
G_ONE_DEMO_FARMER_PHONE=+919876543210              # simulator Caller A (synthetic)
```

Then load the file into the terminal that starts the backend, from the repo root:

```powershell
# PowerShell (Windows)
Get-Content .env.local | ForEach-Object { if ($_ -match '^\s*([A-Za-z_]\w*)=(.*?)\s*(#.*)?$') { Set-Item "env:$($Matches[1])" $Matches[2] } }
cd backend; .\mvnw.cmd spring-boot:run
```

```sh
# bash / Git Bash / macOS
set -a; . ./.env.local; set +a
cd backend && ./mvnw spring-boot:run
```

The variables last only for that terminal; load them again in a new one.

The browser simulator hashes caller numbers with `G_ONE_MEMORY_HMAC_KEY` too, so **Caller A is Suresh**. Without the
key the seed is skipped (startup logs a warning) and the simulator falls back to its built-in dev salt.

**Start a fresh take.** Stop the backend (Ctrl+C), then select a new empty data directory in the same terminal before
restarting it. The scripts create a unique directory and print its path; they never delete an existing database.

```powershell
# PowerShell (Windows), from the repo root after loading .env.local
$env:G_ONE_DATA_DIR = (& pwsh scripts/reset-memory.ps1)
cd backend; .\mvnw.cmd spring-boot:run
```

```sh
# bash / Git Bash / macOS, from the repo root after loading .env.local
export G_ONE_DATA_DIR="$(sh scripts/reset-memory.sh)"
cd backend && ./mvnw spring-boot:run
```

Check the log for `Farmer memory seeded from classpath:memory/seed-suresh.json`. Each fresh directory keeps its own
take history; stop every backend using an old directory before cleaning old data by hand.

**Take plan.**

1. Caller A: G-One asks "Suresh ji?"; answer yes. The memory panel on `#/demo` shows his crops and open follow-up.
2. Caller B: a first call. Say your name (for example "मेरा नाम रमेश है"). Hang up and wait for the
   "Saved for next time" note before redialing (it appears only once this call's own summary is stored; "Couldn't
   confirm" means check the memory profile before redialing). Call again as Caller B: G-One asks for that name.
3. Use **Caller B, never Caller A,** for "forget me" (मुझे भूल जाओ). A forget blocks the seed for that caller in the
   current data directory. Select a fresh directory for another take.

## Phone as the buyer (local demo)

The laptop is the farmer on `#/call` (Try a call). A phone on the same network scans a QR code and joins as the buyer. This stays on
your network: a self-signed certificate and no public tunnel.

```sh
cd backend && ./mvnw spring-boot:run           # add G_ONE_DEMO_MODE=true to run offline with no keys
cd web && bun run dev:https                    # second terminal; prints https://<laptop-ip>:5173/
```

1. Connect the laptop to the phone's hotspot (or put both on one Wi-Fi network), then start the commands above.
2. Open `https://localhost:5173/#/call` on the laptop and tap **Call as farmer**. The QR code and link appear in the
   buyer panel.
3. Scan the code, or type the link on the phone. Accept the certificate warning once.
4. Tap **Join call** and allow the microphone. G-One asks the buyer to say yes before connecting them. Use headphones on
   one side if the two devices are close, so that they do not hear each other.

`bun run dev` stays HTTP on localhost only. The certificate is written to `web/.dev-certs/` and is gitignored. If the
wrong address is shown, pick another under the QR code, set `G_ONE_LAN_HOST=<ip>` before `bun run dev:https`, or open
`#/call?lan=<ip>`.

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
