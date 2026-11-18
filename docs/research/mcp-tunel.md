# Research M0: konektor MCP w claude.ai, Python MCP SDK, Tailscale Funnel, Docker w WSL

Autor: Researcher · Data sprawdzenia źródeł: **2026-11-18** · Dotyczy: MASTER_PROMPT sekcje 3.2, 3.3, 3.5, 9 (ADR-0002)

Oznaczenia: **[F]** fakt sprawdzony w źródle (link w sekcji Źródła), **[T]** zweryfikowane testem/spike'iem lokalnie 2026-11-18, **[P]** przypuszczenie / do potwierdzenia w spike'u M0 na laptopie Tomka.

---

## TL;DR — rekomendacje

| Obszar | Rekomendacja | Pewność |
|---|---|---|
| **Auth MCP** | **Plan B staje się planem A: minimalny serwer OAuth 2.1 w FastAPI z DCR + PKCE, zbudowany na klasach z oficjalnego SDK** (`OAuthAuthorizationServerProvider`). Użytkownik przy dodawaniu konektora loguje się swoim osobistym „kodem CG” na stronie naszego serwera. Stały nagłówek `Authorization` jest w becie tylko dla wybranych organizacji, więc na Free najpewniej niedostępny. Plan awaryjny: sekret w ścieżce URL + „No sign-in”. | wysoka (docs) + spike działa lokalnie |
| **MCP SDK** | Pakiet `mcp` **2.3.0** (2026-10-02). **Uwaga: w 2.x `FastMCP` zmienił nazwę na `MCPServer`** (`from mcp.server.mcpserver import MCPServer`). Montaż: `app.mount("/", mcp.streamable_http_app(stateless_http=True, json_response=True, transport_security=...))` + `mcp.session_manager.run()` w lifespan FastAPI. Bez `transport_security` z publicznym hostem serwer zwraca **421** za Funnelem. | wysoka [T] |
| **Tunel** | **Tailscale Funnel** (darmowy, „available for all plans”), port **443** (Claude nie łączy się na inny port). Wariant domyślny: **sidecar `tailscale/tailscale` w docker compose z `TS_SERVE_CONFIG` (AllowFunnel)** — wszystko w jednym folderze, łatwa migracja na PC w Łomży, brak zależności od przekierowania localhost Windows↔WSL. Wariant zapasowy: aplikacja Tailscale na Windows + `tailscale funnel --bg 8000` (oficjalnie zalecane miejsce dla Tailscale przy WSL2). | średnio-wysoka, sidecar do potwierdzenia w spike'u |
| **Docker** | **Docker Engine bezpośrednio w WSL Ubuntu z systemd** (0 zł, lekki, bez licencji) + `instanceIdleTimeout=-1` w `.wslconfig` + zadanie Harmonogramu przy logowaniu, które „budzi” WSL. Docker Desktop jest darmowy do użytku osobistego i ma gotowy autostart, ale jest ciężki i dokłada warstwę GUI/aktualizacji. | średnia [P] — autostart do sprawdzenia na laptopie |

Najważniejsze korekty względem MASTER_PROMPT:
1. Sekcja 3.2 zakłada nagłówek `Authorization: Bearer` konfigurowany w konektorze. **Wg docs Anthropic „Request headers” to beta „available to a limited set of organizations”** → na Free nie ma na to gwarancji. Zalecam od razu OAuth (plan B), który i tak lepiej pasuje do „token per użytkownik”.
2. Kod musi używać API **MCP SDK 2.x** (`MCPServer`, nie `FastMCP`) albo przypiąć `mcp<2`. Rekomendacja: 2.x, przypięte `mcp==2.3.*`.

---

## 1. Własne zdalne konektory MCP w claude.ai na planie Free

### 1.1 Fakty

- **[F]** Własne konektory (remote MCP) działają na planach Free, Pro, Max, Ultra, Team, Enterprise. **„Free users are limited to one custom connector.”** (support 11175166, add-unlisted)
- **[F]** Dodawanie (Free/Pro/Max): **Customize → Connectors → Add custom connector** → URL serwera → opcjonalnie dane OAuth → Add. Wygląd okna różni się między organizacjami: albo jeden ekran z **Advanced settings** (OAuth Client ID / Client Secret), albo dwa kroki z wyborami poniżej. (add-unlisted)
- **[F]** Opcje w oknie dodawania:
  - **Authentication:** „Sign in now” / „Sign in when needed” / „No sign-in”.
  - **OAuth client:** „Use Claude's published identity” (CIMD, zalecane), „Register automatically” (**DCR**, RFC 7591), „Use your own OAuth client” (Client ID, sekret opcjonalny).
  - **Request headers:** stałe nagłówki (`authorization`, `x-api-key`, `x-auth-token`, do 4 nagłówków), wartość wysyłana dosłownie (trzeba wpisać `Bearer <token>`). **„Request header authentication is in beta and available to a limited set of organizations. If you don't see the Request headers section… your organization doesn't have access yet.”** Tabela typów auth: `static_headers` — „Beta, for a limited set of organizations”; opis mówi o kluczu wpisywanym przez „organization Owner”.
  - Ustawień auth nie da się zmienić po dodaniu — trzeba usunąć i dodać konektor ponownie.
- **[F]** Typy auth wspierane domyślnie: `oauth_dcr`, `oauth_cimd`, `none` (authless). (claude.com/docs/connectors/building/authentication)
- **[F]** Wymagania Claude wobec serwera OAuth:
  - Nieuwierzytelnione żądanie do MCP → **HTTP 401** z `WWW-Authenticate: Bearer resource_metadata="https://…/.well-known/oauth-protected-resource…"`. Na 200 nagłówek jest ignorowany.
  - **Protected Resource Metadata (RFC 9728):** pole `resource` musi być **dokładnie** równe URL-owi wpisanemu w Claude (z `/mcp`); `authorization_servers` — Claude bierze tylko pierwszy wpis. Fallback bez wskaźnika: `/.well-known/oauth-protected-resource/<ścieżka-mcp>`, potem `/.well-known/oauth-protected-resource`.
  - **Authorization Server Metadata:** RFC 8414 (`/.well-known/oauth-authorization-server`) lub OIDC Discovery.
  - **PKCE S256** zawsze; serwer musi ogłaszać `code_challenge_methods_supported: ["S256"]`.
  - CIMD używany tylko, gdy metadata ma `client_id_metadata_document_supported: true` **i** `none` w `token_endpoint_auth_methods_supported`; inaczej **fallback do DCR** (`registration_endpoint`).
  - `/token` musi przyjmować `application/x-www-form-urlencoded`; `/register` — JSON.
  - Refresh: Claude odświeża przy 401 i do 5 min przed wygaśnięciem; przy nieważnym refresh tokenie zwracać `invalid_grant`; dla public client (DCR/CIMD) **rotować refresh tokeny**.
  - Limity czasu: discovery/registration/token **10 s**, refresh **30 s**.
  - **Callback (claude.ai web, Desktop, mobile, Cowork): `https://claude.ai/api/mcp/auth_callback`.** Claude Code: loopback `http://localhost:<port>/callback` / `127.0.0.1`.
  - `client_credentials` (M2M) nie jest wspierany.
- **[F]** Połączenia idą **z chmury Anthropic**, nie z urządzenia; ruch wychodzący z **`160.79.104.0/21`**. Serwer musi mieć publiczny HTTPS na **standardowym porcie 443** („Claude can't connect to a URL that specifies another port”).
- **[F]** Docs dla serwerów z kluczem stałym: „Never accept it in the URL… the MCP authorization specification prohibits access tokens in the query string”.
- **[F] Mobile:** „Installing connectors on mobile is currently in beta — Claude Desktop and web remain the primary path”. Konektor dodany na claude.ai/Desktop „will be available… the next time you log in… on Claude for iOS or Android”. Ten sam callback i infrastruktura auth obsługuje mobile. → **Dodawać konektor na claude.ai w przeglądarce, używać też w aplikacji na telefonie.**
- **[F]** Konektor włącza się per rozmowa: „+” → Connectors.

### 1.2 Wnioski

- **[P, wysokie prawdopodobieństwo]** Na koncie Free brata/Tomka sekcji „Request headers” **nie będzie** (beta dla ograniczonej grupy organizacji, opis mówi o „organization Owner”). Sprawdzić w M0: Customize → Connectors → Add custom connector i zrobić zrzut ekranu (bez wpisywania czegokolwiek).
- Nawet gdyby nagłówek był dostępny, docs zaznaczają, że to „credential organizacji, nie osoby”. U nas każdy użytkownik ma osobne konto Free i osobny konektor, więc technicznie by działało, ale **OAuth jest ścieżką oficjalnie wspieraną na wszystkich planach**.
- **Rekomendacja auth (A): OAuth 2.1 + DCR + PKCE na bazie SDK** (szczegóły w sekcji 2.4, kod przetestowany lokalnie [T]). Przepływ dla użytkownika: dodaje konektor z URL `https://<maszyna>.<tailnet>.ts.net/mcp`, w polu OAuth nic nie wpisuje (albo „Register automatically”) → Claude otwiera stronę logowania CG-connector → użytkownik wkleja swój osobisty **kod CG** (długi losowy, wygenerowany CLI `cg-server user create`, w bazie tylko hash; ten sam co dla aplikacji Android albo osobny) → wraca do Claude, połączone. Serwer zna `user_id` z tokenu.
- **Plan awaryjny (C): sekret w ścieżce** — „No sign-in”, URL `https://…ts.net/mcp/<sekret-256-bit>`. Najprostsze, działa dziś na każdym planie (authless jest wspierany). Minusy: sprzeczne z zaleceniem Anthropic, sekret w historii/logach (TLS kończy się na laptopie, więc ścieżkę widzą tylko Anthropic i nasz serwer; nie logować ścieżek), rotacja = usunięcie i ponowne dodanie konektora. Użyć tylko, jeśli OAuth nie zadziała w M0.
- **Lista dozwolonych IP** nie jest potrzebna (Funnel jest publiczny). Opcjonalnie [P]: logować/odrzucać ruch na `/mcp` spoza `160.79.104.0/21` na podstawie `X-Forwarded-For` ustawianego przez Tailscale — nie traktować jako zabezpieczenia głównego.
- **Limity Free [F]:** 1 własny konektor. Limity wiadomości/kontekstu Free — Anthropic nie podaje stałych liczb (zależne od obciążenia) [P]; dlatego odpowiedzi narzędzi muszą być zwięzłe (już w MASTER_PROMPT).

---

## 2. Oficjalne Python MCP SDK (`mcp`)

### 2.1 Fakty

- **[F]** PyPI `mcp`: **2.3.0** (wydana 2026-10-02). Linia 1.x nadal łatana (1.30.0, 2026-09-07). (pypi.org/pypi/mcp/json, sprawdzone 2026-11-18)
- **[F]** W 2.x **`FastMCP` → `MCPServer`** (`from mcp.server.mcpserver import MCPServer, Context`); import `mcp.server.fastmcp` rzuca `ModuleNotFoundError`. `json_response`, `stateless_http`, `streamable_http_path`, `host` przeniesione z konstruktora do `run()` / `streamable_http_app()`. `mount_path` usunięte. `get_context()` usunięte — parametr `ctx: Context` w narzędziu. (migration guide v2)
- **[F]** Aplikacja hosta musi w lifespan uruchomić `async with mcp.session_manager.run():` — inaczej „Task group is not initialized”. `mcp.session_manager` istnieje dopiero po wywołaniu `streamable_http_app()`.
- **[F/T]** Auth w SDK (kod `mcp/server/auth/*`, wersja 2.3.0):
  - `TokenVerifier` (Protocol: `async verify_token(token) -> AccessToken | None`) — tryb „resource server”.
  - `OAuthAuthorizationServerProvider` — pełny serwer autoryzacji: SDK samo wystawia `/.well-known/oauth-authorization-server`, `/authorize`, `/token`, `/register` (DCR), `/revoke`, sprawdza PKCE S256 i `redirect_uri`.
  - `AuthSettings(issuer_url, resource_server_url, required_scopes, client_registration_options, revocation_options, validate_token_resource)` — z `resource_server_url` SDK wystawia `/.well-known/oauth-protected-resource/mcp` i dokłada `WWW-Authenticate: Bearer … resource_metadata=…` do 401.
  - `AccessToken` ma pole **`subject`** (właściciel zasobu) i `claims`. W narzędziu: `from mcp.server.auth.middleware.auth_context import get_access_token` → `get_access_token().subject` = nasz `user_id`.
  - `validate_token_resource` niezaustawione → ostrzeżenie; w 3.0 domyślnie `True`. Ustawiać jawnie.
  - SDK **nie ogłasza CIMD**, więc Claude użyje DCR — to OK.
- **[T]** Domyślnie (`host="127.0.0.1"`) SDK włącza ochronę DNS rebinding z `allowed_hosts` tylko dla localhost → żądanie z `Host: <maszyna>.ts.net` dostaje **421 Misdirected Request**. Trzeba przekazać `TransportSecuritySettings(allowed_hosts=["<maszyna>.<tailnet>.ts.net", ...])`.
- **[F, kod źródłowy Tailscale]** Tailscale Serve/Funnel **zachowuje oryginalny nagłówek Host** (`r.Out.Host = r.In.Host`) i dodaje `X-Forwarded-Host`, `X-Forwarded-Proto: https`, `X-Forwarded-For`, a dla Funnela `Tailscale-Funnel-Request: ?1` (nagłówki tożsamości Tailscale-User-* są czyszczone). (`ipn/ipnlocal/serve.go`)

### 2.2 Decyzje dla backendu

- `stateless_http=True, json_response=True` — brak sesji w pamięci, więc restart/uśpienie laptopa nie psuje stanu sesji MCP; nie potrzebujemy sampling/elicitation (w trybie stateless rzucają `NoBackChannelError`).
- Kolejność w FastAPI: najpierw własne trasy (`/api/v1`, `/health`, `/oauth/login`), na końcu `app.mount("/", mcp_app)` (mount pod `/`, bo SDK wystawia też `/.well-known/*` i `/authorize`, `/token`, `/register`, `/revoke` w korzeniu).
- Stan OAuth (klienci DCR, kody, hashe access/refresh tokenów) **w SQLite**, nie w pamięci — inaczej po restarcie kontenera Claude dostanie `invalid_client` i trzeba łączyć konektor od nowa.

### 2.3 Minimalny przykład: FastAPI + MCP pod `/mcp` + TokenVerifier (przetestowany [T])

Wariant „stały token” (gdyby nagłówek był dostępny albo dla testów przez curl / Claude Code):

```python
import contextlib, hashlib, hmac
from fastapi import FastAPI
from mcp.server.auth.provider import AccessToken, TokenVerifier
from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.settings import AuthSettings
from mcp.server.mcpserver import MCPServer
from mcp.server.transport_security import TransportSecuritySettings

PUBLIC_URL = "https://laptop.tail1234.ts.net"          # z .env
USERS_BY_HASH = {hashlib.sha256(b"...").hexdigest(): "tomek"}  # w realu: tabela users

class HashTokenVerifier(TokenVerifier):
    async def verify_token(self, token: str) -> AccessToken | None:
        h = hashlib.sha256(token.encode()).hexdigest()
        for stored, user in USERS_BY_HASH.items():
            if hmac.compare_digest(stored, h):
                return AccessToken(token=token, client_id="claude", scopes=["cg"], subject=user)
        return None

mcp = MCPServer("CG-connector", token_verifier=HashTokenVerifier(),
    auth=AuthSettings(issuer_url=PUBLIC_URL, resource_server_url=f"{PUBLIC_URL}/mcp",
                      required_scopes=["cg"], validate_token_resource=False))

@mcp.tool()
async def get_profile() -> dict:
    """Return the current user's profile and last sync time."""
    tok = get_access_token()            # user z tokenu, NIGDY z parametru
    return {"user": tok.subject if tok else None}

mcp_app = mcp.streamable_http_app(
    streamable_http_path="/mcp", stateless_http=True, json_response=True,
    transport_security=TransportSecuritySettings(
        enable_dns_rebinding_protection=True,
        allowed_hosts=["laptop.tail1234.ts.net", "127.0.0.1:*", "localhost:*"],
        allowed_origins=["https://claude.ai"]))

@contextlib.asynccontextmanager
async def lifespan(app: FastAPI):
    async with mcp.session_manager.run():
        yield

app = FastAPI(lifespan=lifespan)

@app.get("/health")
def health(): return {"ok": True}

app.mount("/", mcp_app)   # na końcu
```

Wynik testu lokalnego: bez tokenu `401` + `www-authenticate: Bearer … resource_metadata="https://…/.well-known/oauth-protected-resource/mcp"`; z tokenem `initialize` i `tools/call` → `{"user": "tomek"}`; obcy `Host` → `421`.

### 2.4 Rekomendowany wariant: minimalny serwer OAuth (DCR + PKCE) na klasach SDK (przetestowany [T])

Przetestowany lokalnie cały przepływ, który wykonuje Claude: AS metadata → PRM → `POST /register` (DCR, `token_endpoint_auth_method: none`) → `/authorize` (PKCE S256) → nasza strona logowania → redirect na `https://claude.ai/api/mcp/auth_callback?code=…&state=…` → `POST /token` (form-urlencoded, `code_verifier`) → `tools/call` zwraca `{"user":"tomek"}` → refresh z rotacją (ponowne użycie starego refresh tokenu → `invalid_grant`), ponowne użycie kodu → `invalid_grant`.

```python
"""Spike: MCP (SDK 2.3) + minimalny OAuth 2.1 (DCR + PKCE). Stan w pamięci - w produkcji SQLite."""
import contextlib, hashlib, hmac, os, secrets, time
from fastapi import FastAPI, Form
from fastapi.responses import HTMLResponse, RedirectResponse
from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.provider import (AccessToken, AuthorizationCode, AuthorizationParams,
    RefreshToken, construct_redirect_uri)
from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions, RevocationOptions
from mcp.server.mcpserver import MCPServer
from mcp.server.transport_security import TransportSecuritySettings
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken

PUBLIC_URL = os.environ["CG_PUBLIC_URL"]            # https://<maszyna>.<tailnet>.ts.net
CLAUDE_CALLBACK = "https://claude.ai/api/mcp/auth_callback"
def h(s: str) -> str: return hashlib.sha256(s.encode()).hexdigest()
USERS = {h("<kod-CG-usera>"): "tomek"}             # w realu: users.code_hash

class CgOAuthProvider:
    def __init__(self):
        self.clients, self.pending, self.codes, self.access, self.refresh = {}, {}, {}, {}, {}
    async def get_client(self, client_id): return self.clients.get(client_id)
    async def register_client(self, info: OAuthClientInformationFull):
        for uri in info.redirect_uris or []:        # tylko callback claude.ai
            if str(uri) != CLAUDE_CALLBACK:
                raise ValueError("redirect_uri not allowed")
        self.clients[info.client_id] = info
    async def authorize(self, client, params: AuthorizationParams) -> str:
        login_id = secrets.token_urlsafe(24)
        self.pending[login_id] = (client.client_id, params)
        return f"{PUBLIC_URL}/oauth/login?login_id={login_id}"
    def finish_login(self, login_id: str, user_code: str) -> str | None:
        item = self.pending.pop(login_id, None)
        user = next((u for k, u in USERS.items() if hmac.compare_digest(k, h(user_code))), None)
        if not item or not user: return None
        client_id, p = item
        code = secrets.token_urlsafe(32)
        self.codes[code] = AuthorizationCode(code=code, scopes=p.scopes or ["cg"],
            expires_at=time.time() + 300, client_id=client_id, code_challenge=p.code_challenge,
            redirect_uri=p.redirect_uri, redirect_uri_provided_explicitly=p.redirect_uri_provided_explicitly,
            resource=p.resource, subject=user)
        return construct_redirect_uri(str(p.redirect_uri), code=code, state=p.state)
    async def load_authorization_code(self, client, code): return self.codes.get(code)
    def _issue(self, client_id, scopes, subject, resource) -> OAuthToken:
        at, rt = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        self.access[h(at)] = AccessToken(token=at, client_id=client_id, scopes=scopes,
            expires_at=int(time.time()) + 3600, subject=subject, resource=resource)
        self.refresh[h(rt)] = RefreshToken(token=rt, client_id=client_id, scopes=scopes,
            subject=subject, resource=resource)
        return OAuthToken(access_token=at, expires_in=3600, scope=" ".join(scopes), refresh_token=rt)
    async def exchange_authorization_code(self, client, ac: AuthorizationCode) -> OAuthToken:
        self.codes.pop(ac.code, None)               # kod jednorazowy; PKCE weryfikuje SDK
        return self._issue(client.client_id, ac.scopes, ac.subject, ac.resource)
    async def load_refresh_token(self, client, rt): return self.refresh.get(h(rt))
    async def exchange_refresh_token(self, client, rt: RefreshToken, scopes) -> OAuthToken:
        self.refresh.pop(h(rt.token), None)         # rotacja (wymóg dla public client)
        return self._issue(client.client_id, scopes or rt.scopes, rt.subject, rt.resource)
    async def load_access_token(self, token):
        t = self.access.get(h(token))
        return t if t and (t.expires_at or 0) > time.time() else None
    async def revoke_token(self, token):
        self.access.pop(h(token.token), None); self.refresh.pop(h(token.token), None)

provider = CgOAuthProvider()
mcp = MCPServer("CG-connector", auth_server_provider=provider, auth=AuthSettings(
    issuer_url=PUBLIC_URL, resource_server_url=f"{PUBLIC_URL}/mcp", required_scopes=["cg"],
    validate_token_resource=False,
    client_registration_options=ClientRegistrationOptions(enabled=True, valid_scopes=["cg"], default_scopes=["cg"]),
    revocation_options=RevocationOptions(enabled=True)))

@mcp.tool()
async def get_profile() -> dict:
    """Return who is logged in."""
    tok = get_access_token()
    return {"user": tok.subject if tok else None}

mcp_app = mcp.streamable_http_app(streamable_http_path="/mcp", stateless_http=True, json_response=True,
    transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=True,
        allowed_hosts=[PUBLIC_URL.removeprefix("https://"), "127.0.0.1:*", "localhost:*"],
        allowed_origins=["https://claude.ai"]))

@contextlib.asynccontextmanager
async def lifespan(app):
    async with mcp.session_manager.run():
        yield

app = FastAPI(lifespan=lifespan)

@app.get("/oauth/login", response_class=HTMLResponse)
def login_form(login_id: str):      # w produkcji: szablon z escapowaniem, CSP, rate limit
    return f"""<form method=post><input type=hidden name=login_id value="{login_id}">
    Kod CG-connector: <input name=code type=password><button>Połącz z Claude</button></form>"""

@app.post("/oauth/login")
def login_submit(login_id: str = Form(...), code: str = Form(...)):
    url = provider.finish_login(login_id, code)
    return RedirectResponse(url, status_code=302) if url else HTMLResponse("Zły kod", status_code=401)

app.mount("/", mcp_app)
```

Uwagi do produkcji: stan w SQLite (tabele `oauth_clients`, `oauth_codes`, `oauth_tokens` z hashami); TTL `pending` 10 min; rate limit na `/oauth/login`; escapowanie `login_id`; wymaga `python-multipart`. Metadane SDK ogłaszają `token_endpoint_auth_methods_supported: ["client_secret_post","client_secret_basic"]` (bez `none`), a mimo to DCR z `none` zadziałał lokalnie [T]. Czy Claude to akceptuje — **potwierdzić w spike'u z prawdziwym claude.ai** [P].

---

## 3. Tailscale Funnel

### 3.1 Fakty

- **[F]** „Tailscale Funnel is available for all plans.” Plan **Personal: $0, „Free forever”, do 6 użytkowników, nielimitowane urządzenia.** Funnel ma status **beta**.
- **[F]** Wymagania: Tailscale ≥ 1.38.3, **MagicDNS**, **HTTPS certificates** włączone, atrybut **`funnel`** w polityce tailnetu (nodeAttrs).
- **[F]** Porty Funnela: **443, 8443, 10000**. Claude akceptuje tylko 443 → **używamy 443**.
- **[F]** Przepustowość: „non-configurable bandwidth limits” (liczb nie podano). Dla JSON-owych odpowiedzi MCP i API bez znaczenia [P].
- **[F]** TLS kończy się na lokalnym węźle Tailscale (certyfikat Let's Encrypt dla `<maszyna>.<tailnet>.ts.net`); proxy tylko do `http://127.0.0.1:<port>`.
- **[F]** Adres: `https://<nazwa-maszyny>.<tailnet>.ts.net`, gdzie tailnet to `tailNNNN.ts.net` albo losowa „fun name” (np. `yak-bebop.ts.net`). **Stały**, dopóki nie zmieni się nazwy maszyny/tailnetu. Nazwy maszyn trafiają do **publicznego rejestru Certificate Transparency** → nazwa maszyny bez danych osobowych (np. `cg-server`, nie `laptop-tomka`).
- **[F]** `tailscale funnel --bg <cel>` — konfiguracja trwała, wraca po restarcie i po `tailscale down/up`. `tailscale funnel status`, `tailscale funnel reset`.
- **[F]** Klucze węzła wygasają domyślnie po **180 dniach** (trzeba się ponownie zalogować) — dla serwera wyłączyć „key expiry” w konsoli (Machines → … → Disable key expiry). Urządzenia z tagiem mają expiry wyłączone domyślnie. Auth key (do kontenera) max 90 dni, ale po rejestracji węzeł zostaje autoryzowany.
- **[F]** WSL2: „it is recommended that users run Tailscale on the Windows host only, and not inside WSL 2”; Tailscale jednocześnie na Windows i w WSL — ruch WSL przez Tailscale Windows „will not work”. Problem MTU 1280 w WSL2 (obejście w Tailscale).
- **[F]** Windows: tryb **Run unattended** (ikonka w zasobniku → Preferences → Run unattended) — Tailscale działa także bez zalogowanego użytkownika i po restarcie.
- **[F]** Docker: `tailscale/tailscale`, zmienne `TS_AUTHKEY`, `TS_STATE_DIR` (musi być trwały, inaczej nowy węzeł przy każdym starcie), `TS_HOSTNAME`, `TS_SERVE_CONFIG` (JSON z konfiguracją Serve/Funnel; montować **katalog**, nie plik), `TS_USERSPACE` (domyślnie włączony — nie potrzeba `/dev/net/tun`). W `serve.json` można użyć `${TS_CERT_DOMAIN}`; `"AllowFunnel": {"${TS_CERT_DOMAIN}:443": true}`.
- **[F]** Microsoft: w domyślnym trybie NAT aplikacje Windows sięgają do usług w WSL przez `localhost`.

### 3.2 Uśpienie, zmiana sieci, ruch z Anthropic

- **[F/P]** Funnel działa przez serwery przekaźnikowe Tailscale, więc nie wymaga otwierania portów ani publicznego IP; działa z domu, hotspotu, pracy (o ile sieć nie blokuje Tailscale — sieci firmowe czasem blokują) [P].
- **[P]** Po uśpieniu: Tailscale sam wznawia połączenie po wybudzeniu (zwykle kilka–kilkanaście sekund); w tym czasie Claude dostaje błąd połączenia. Adres się nie zmienia. Przy uśpionym laptopie konektor jest niedostępny — zgodne z MASTER_PROMPT.
- **[F/P]** Funnel jest publiczny w internecie, więc chmura Anthropic dociera bez allowlisty. Do sprawdzenia w M0: dodanie konektora z prawdziwego claude.ai.

### 3.3 Rekomendacja: gdzie uruchomić Tailscale

| | **A. Sidecar w docker compose (rekomendowany)** | **B. Aplikacja Tailscale na Windows (zapasowy)** |
|---|---|---|
| Instalacja | nic na Windows; kontener `tailscale/tailscale` w tym samym compose | instalator .exe, logowanie w GUI, komenda w konsoli admina |
| Ścieżka ruchu | Funnel → kontener TS → `127.0.0.1:8000` (wspólny namespace sieci z `cg-server`) | Funnel → Windows → `127.0.0.1:8000` → przekierowanie localhost WSL → Docker w WSL |
| Ryzyko | ostrzeżenie Tailscale o WSL dotyczy instalacji w dystrybucji z Tailscale także na Windows; tutaj Windows nie ma Tailscale [P]; tryb userspace | znane przypadki, że przekierowanie localhost Windows→WSL przestaje działać po hibernacji/zmianie sieci [P] |
| Migracja na PC w Łomży | kopiujesz folder (z `ts-state/`) i `docker compose up` → ten sam węzeł i adres [P] | trzeba zainstalować i skonfigurować Tailscale na nowym PC; nowy adres, chyba że przemianujesz maszynę |
| Start po włączeniu | razem z Dockerem (`restart: unless-stopped`) | usługa Windows, startuje sama (Run unattended) |

Szkic (do spike'u DevOps):

```yaml
# compose.yaml (fragment)
services:
  tailscale:
    image: tailscale/tailscale:stable        # przypiąć wersję
    hostname: cg-server                       # nazwa maszyny => adres cg-server.<tailnet>.ts.net
    environment:
      - TS_STATE_DIR=/var/lib/tailscale
      - TS_SERVE_CONFIG=/config/serve.json
      - TS_AUTHKEY=${TS_AUTHKEY:-}            # z .env; można pominąć i zalogować się linkiem z `docker compose logs tailscale`
    volumes:
      - ${CG_DATA_DIR}/ts-state:/var/lib/tailscale
      - ./tailscale-config:/config            # katalog, nie plik
    restart: unless-stopped
  cg-server:
    build: .
    network_mode: service:tailscale           # cg-server słucha na 127.0.0.1:8000 w namespace TS
    depends_on: [tailscale]
    restart: unless-stopped
```

```json
// tailscale-config/serve.json
{
  "TCP": { "443": { "HTTPS": true } },
  "Web": { "${TS_CERT_DOMAIN}:443": { "Handlers": { "/": { "Proxy": "http://127.0.0.1:8000" } } } },
  "AllowFunnel": { "${TS_CERT_DOMAIN}:443": true }
}
```

Uwaga [P]: Funnel proxuje wszystkie ścieżki; ograniczenie do `/api/v1/*`, `/mcp`, `/health`, `/.well-known/*`, `/authorize`, `/token`, `/register`, `/revoke`, `/oauth/login` robimy w FastAPI (brak innych tras, `docs_url=None`) — alternatywnie osobne handlery w `serve.json` per ścieżka.

Wariant B (Windows): PowerShell jako administrator → `tailscale funnel --bg 8000` (443 → `http://127.0.0.1:8000`), `cg-server` publikuje port `127.0.0.1:8000:8000` w compose.

---

## 4. Docker na Windows + WSL za 0 zł

### 4.1 Fakty

- **[F]** Docker Desktop jest darmowy dla „Personal use” (oraz małych firm < 250 pracowników i < 10 mln USD przychodu, edukacji, niekomercyjnego open source). Ma ustawienie „Automatically start Docker Desktop when you sign in to your machine”.
- **[F]** Docker Engine (Apache 2.0) instaluje się w Ubuntu z oficjalnego repozytorium apt; WSL obsługuje systemd: `/etc/wsl.conf` → `[boot] systemd=true`, potem `wsl --shutdown`. `sudo systemctl enable docker containerd`.
- **[F]** `.wslconfig`: `[general] instanceIdleTimeout` (domyślnie 15000 ms, **`-1` wyłącza automatyczne zamykanie dystrybucji**), `[wsl2] vmIdleTimeout` (domyślnie 60000 ms). WSL nie startuje sam przy logowaniu — trzeba go wywołać.
- **[F]** `/etc/wsl.conf` `[boot] command` — komenda uruchamiana jako root przy starcie instancji.

### 4.2 Rekomendacja: Docker Engine w WSL + systemd

Dlaczego: 0 zł bez warunków licencyjnych, mniej RAM-u (laptop), brak GUI i auto-aktualizacji, które coś psują, te same komendy na PC w Łomży. Docker Desktop zostaje jako plan B, jeśli autostart WSL okaże się kapryśny (Desktop sam pilnuje swojej maszyny WSL i ma checkbox autostartu).

Autostart bez klikania [P — do potwierdzenia na laptopie]:
1. `C:\Users\<user>\.wslconfig`:
   ```ini
   [general]
   instanceIdleTimeout=-1
   [wsl2]
   vmIdleTimeout=-1
   ```
   (wartość `-1` dla `vmIdleTimeout` nie jest opisana w docs Microsoftu — jeśli nie zadziała, użyć `2147483647`).
2. Harmonogram zadań: wyzwalacze „Przy logowaniu” i „Przy odblokowaniu stacji roboczej”, akcja `wsl.exe -d Ubuntu --exec /bin/true` (lub skrypt PowerShell, który dodatkowo sprawdza `curl http://127.0.0.1:8000/health`), „Uruchom niezależnie od tego, czy użytkownik jest zalogowany” niepotrzebne. Systemd w WSL startuje `docker.service`, a compose z `restart: unless-stopped` podnosi kontenery.
3. Jeśli `instanceIdleTimeout=-1` mimo to pozwoli zamknąć dystrybucję — zadanie uruchamia ukryty proces podtrzymujący (`wsl.exe -d Ubuntu --exec sleep infinity`) przez `conhost --headless` / VBS bez okna.

---

## 5. Kroki dla Tomka (laik, klik po kliku) — wariant rekomendowany

Konto i ustawienia Tailscale (jednorazowo, ok. 10 min):
1. Wejdź na https://login.tailscale.com/start → zaloguj się kontem Google/Microsoft/GitHub (plan **Personal**, darmowy, bez karty).
2. Admin console → **DNS** → upewnij się, że **MagicDNS** jest włączone → w sekcji **HTTPS Certificates** kliknij **Enable HTTPS** (zaakceptuj informację, że nazwy maszyn trafią do publicznego rejestru — dlatego maszyna będzie się nazywać `cg-server`). Opcjonalnie wybierz losową nazwę tailnetu („fun name”) zanim cokolwiek podłączysz — potem adres ma się nie zmieniać.
3. Admin console → **Access controls** → sekcja **Funnel** → **Add Funnel to policy** → zapisz.
4. Admin console → **Settings → Keys → Generate auth key**: zaznacz *Pre-approved*, ewentualnie tag `tag:cg-server`; skopiuj klucz i wklej do pliku `.env` na laptopie (`TS_AUTHKEY=...`). **Nigdzie indziej** (nie do repo, nie do czatu).
5. Po pierwszym uruchomieniu (`docker compose up -d`) w **Machines** przy `cg-server` → menu „…” → **Disable key expiry**. Adres serwera to `https://cg-server.<twój-tailnet>.ts.net` (widoczny w Machines).

Konektor w Claude (każdy użytkownik na swoim koncie, w przeglądarce na komputerze):
6. claude.ai → **Customize → Connectors → Add custom connector**. Nazwa: `CG-connector`. URL: `https://cg-server.<tailnet>.ts.net/mcp`. Jeśli są wybory: Authentication „Sign in now”, OAuth client „Register automatically”. Client ID/Secret — puste. **Add**.
7. Otworzy się strona „Kod CG-connector” → wklej swój osobisty kod (Tomek generuje go poleceniem `cg-server user create <imię>` i przekazuje bratu prywatnie) → **Połącz z Claude**.
8. W nowej rozmowie: „+” → Connectors → włącz CG-connector. Na telefonie (aplikacja Claude) konektor pojawi się sam po zalogowaniu.

---

## 6. Ryzyka

| Ryzyko | Ocena | Mitygacja |
|---|---|---|
| Free nie ma „Request headers” | wysoce prawdopodobne | OAuth (rekomendacja A) |
| Claude odrzuci nasz AS (np. brak `none` w `token_endpoint_auth_methods_supported`, czas > 10 s, CIMD/DCR) | niskie–średnie | spike z prawdziwym claude.ai w M0; ewentualnie nadpisać route metadanych; plan C (sekret w ścieżce) |
| Utrata stanu OAuth po restarcie | pewne, jeśli w pamięci | SQLite + wolumen |
| Funnel w becie, limity przepustowości, zmiany w planie Personal | niskie | plan B: Cloudflare quick tunnel (zmienny adres — gorzej), named tunnel wymaga domeny |
| Wygaśnięcie klucza węzła Tailscale (180 dni) | pewne bez działania | Disable key expiry / tag |
| Nazwa maszyny w Certificate Transparency | pewne | neutralna nazwa `cg-server`, losowa nazwa tailnetu |
| 421 przez ochronę DNS rebinding SDK | pewne bez konfiguracji [T] | `TransportSecuritySettings(allowed_hosts=[…ts.net])` |
| Zmiana API SDK (2.x → 3.0: `validate_token_resource` domyślnie True) | średnie | przypięcie `mcp==2.3.*`, ustawiać parametry jawnie, testy kontraktowe |
| Przekierowanie localhost Windows→WSL po uśpieniu (tylko wariant B) | średnie [P] | wariant A (sidecar) lub `networkingMode=mirrored` |
| WSL zamyka się sam mimo systemd | średnie [P] | `instanceIdleTimeout=-1` + zadanie podtrzymujące; plan B Docker Desktop |
| Sieć firmowa/hotelowa blokuje Tailscale | niskie–średnie [P] | Tailscale używa DERP po HTTPS 443; w razie problemu — znany ograniczony scenariusz w docs |
| Prompt injection przez dane (np. nazwy aktywności) | niskie | narzędzia zapisu (`schedule_workout`, `delete_…`) z walidacją; Claude pyta o zgodę przy narzędziach |

---

## 7. Do zrobienia w spike'u M0 (na laptopie Tomka)

1. Zrzut okna „Add custom connector” na koncie Free (czy jest Request headers / Advanced settings).
2. Uruchomić spike z sekcji 2.4 za Funnelem (sidecar) i dodać konektor z claude.ai — przejść pełne logowanie, wywołać `get_profile` w web i w aplikacji mobilnej.
3. Uśpić laptopa na 10 min, wybudzić, zmienić sieć na hotspot — zmierzyć czas powrotu konektora.
4. Restart Windows → bez klikania: czy po zalogowaniu `/health` odpowiada przez Funnel.

---

## Źródła (wszystkie sprawdzone 2026-11-18)

- Anthropic Help Center — Get started with custom connectors using remote MCP: https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp
- Anthropic Help Center — Use connectors to extend Claude's capabilities (mobile): https://support.claude.com/en/articles/11176164-use-connectors-to-extend-claude-s-capabilities
- Claude docs — Add a connector that isn't in the directory (opcje okna, Request headers beta): https://claude.com/docs/connectors/custom/add-unlisted
- Claude docs — Authentication for connectors (DCR/CIMD, PRM, PKCE, callback, IP, timeouty): https://www.claude.com/docs/connectors/building/authentication
- Anthropic IP addresses: https://platform.claude.com/docs/en/api/ip-addresses (cytowane przez powyższą stronę: `160.79.104.0/21`)
- MCP specyfikacja 2025-11-25, Authorization: https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization
- PyPI `mcp` (wersje): https://pypi.org/project/mcp/ (JSON: https://pypi.org/pypi/mcp/json)
- MCP Python SDK v2 — migration guide: https://py.sdk.modelcontextprotocol.io/v2/migration/
- Kod SDK `mcp==2.3.0` (`mcp/server/mcpserver/server.py`, `mcp/server/lowlevel/server.py`, `mcp/server/auth/*`) — przejrzany i przetestowany lokalnie
- Tailscale Funnel: https://tailscale.com/kb/1223/funnel
- Tailscale CLI `funnel`: https://tailscale.com/kb/1311/tailscale-funnel
- Tailscale Serve: https://tailscale.com/kb/1242/tailscale-serve
- Tailscale pricing (Personal $0): https://tailscale.com/pricing
- Tailscale HTTPS / Certificate Transparency: https://tailscale.com/kb/1153/enabling-https
- Tailscale na Windows z WSL 2: https://tailscale.com/kb/1295/install-windows-wsl2
- Tailscale unattended mode / key expiry: https://tailscale.com/kb/1088/run-unattended
- Tailscale auth keys: https://tailscale.com/kb/1085/auth-keys/
- Tailscale Docker parametry: https://tailscale.com/docs/features/containers/docker/docker-params
- Tailscale blog — Docker sidecar, serve.json z AllowFunnel: https://tailscale.com/blog/docker-tailscale-guide
- Kod Tailscale (zachowanie nagłówków Host / X-Forwarded / Tailscale-Funnel-Request): https://github.com/tailscale/tailscale/blob/main/ipn/ipnlocal/serve.go
- Microsoft — WSL networking: https://learn.microsoft.com/en-us/windows/wsl/networking
- Microsoft — WSL config (`instanceIdleTimeout`, `vmIdleTimeout`, systemd): https://learn.microsoft.com/en-us/windows/wsl/wsl-config
- Docker Desktop license: https://docs.docker.com/subscription/desktop-license/
- Docker Desktop settings (autostart): https://docs.docker.com/desktop/settings-and-maintenance/settings/
- Docker Engine w WSL bez Desktop (blog, 2026-03): https://www.chriswarrick.com/blog/2026/03/04/docker-in-wsl-a-no-frills-guide/
