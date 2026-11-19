# ADR-0002: Tunel (Tailscale Funnel) i Docker w WSL

- Status: zaakceptowana (do potwierdzenia spike'iem na laptopie)
- Data: 2026-11-18
- Research: [docs/research/mcp-tunel.md](../research/mcp-tunel.md)

## Kontekst

Serwer działa na laptopie (Windows + WSL), który zmienia sieci i bywa uśpiony. claude.ai łączy się z chmury Anthropic, więc potrzebny jest stały, publiczny adres HTTPS na porcie 443, bez otwierania portów i za 0 zł.

## Decyzja

- **Tailscale Funnel** (plan Personal, darmowy) jako kontener sidecar `tailscale/tailscale` w `compose.yaml`, z `TS_SERVE_CONFIG` i włączonym Funnel. Maszyna nazywa się neutralnie `cg-server` (nazwy trafiają do publicznego rejestru certyfikatów). Wygasanie klucza węzła wyłączone. Adres: `https://cg-server.<tailnet>.ts.net`.
- **Docker Engine w WSL Ubuntu z systemd**, `instanceIdleTimeout=-1` w `.wslconfig`, start przy logowaniu Windows z Harmonogramu zadań.

## Plany zapasowe

- Tunel: aplikacja Tailscale na Windows + `tailscale funnel` do portu wystawionego z WSL (oficjalnie zalecane przy WSL2).
- Docker: Docker Desktop (darmowy do użytku osobistego, ma autostart).
- Cloudflare Tunnel odrzucony: stały adres wymaga płatnej domeny, a quick tunnel zmienia adres po restarcie.

## Konsekwencje

- Adres się nie zmienia przy zmianie sieci; po wybudzeniu tunel odnawia się sam.
- Serwer MCP musi mieć `allowed_hosts` z adresem `*.ts.net`.
- Migracja na PC w Łomży = przeniesienie folderu z danymi, `.env` i stanu Tailscale.

## Aktualizacja 2026-11-19: ngrok jako plan B

Na laptopie Tomka Funnel działał lokalnie, ale Tailscale nie opublikował publicznego wpisu DNS (`…ts.net` → NXDOMAIN po kilku godzinach). To znany, nienaprawiony błąd Tailscale (tailscale/tailscale#21698, #21847, #21502), bez obejścia po naszej stronie. Dodaliśmy opcjonalny kontener `ngrok` (profil Compose `ngrok`, domyślnie wyłączony): darmowa, stała domena `*.ngrok-free.app`, te same ścieżki co Funnel (`ops/ngrok/policy.yml`), token tylko w `.env`. Tailscale Funnel zostaje domyślnym tunelem. Koszty ngrok free: limit 20 000 żądań/mies. i strona ostrzeżenia ngrok przy pierwszym wejściu z przeglądarki.

