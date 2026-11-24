"""Invite landing page (`/join?code=cgi_…`): download the app, then open it with the server
address and invite code already filled in (deep link `cgconnector://join`). No JS.

The page does not check the code (no oracle for guessing); the app redeems it. The code is
in the query string, so the access log redacts `code=` (logging_filter) like other secrets.
"""

import re
from html import escape
from typing import Annotated
from urllib.parse import urlencode

from fastapi import APIRouter, Query
from fastapi.responses import HTMLResponse

from cg_server.config import Settings

JOIN_PATH = "/join"
RELEASES_URL = "https://github.com/Tomek17/cg-connector/releases/latest"
# Every release also carries the APK under this fixed name (release-android.yml).
APK_URL = f"{RELEASES_URL}/download/cg-connector.apk"
DEEP_LINK = "cgconnector://join"
CODE_RE = re.compile(r"cgi_[A-Za-z0-9_-]{20,80}")

PAGE = """<!DOCTYPE html>
<html lang="pl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CG-connector: zaproszenie</title>
<style>
body {{ font-family: system-ui, sans-serif; max-width: 30rem; margin: 2rem auto; padding: 0 1rem;
  color: #1b2433; line-height: 1.45; }}
h1 {{ color: #14284b; }}
a.button {{ display: block; text-align: center; padding: .9rem; margin: .6rem 0 1.4rem;
  border-radius: .6rem; background: #f07a1a; color: #fff; text-decoration: none;
  font-weight: 600; }}
a.secondary {{ background: #14284b; }}
code {{ background: #eef1f6; padding: .1rem .3rem; border-radius: .2rem; word-break: break-all; }}
small {{ color: #5a6577; }}
</style>
</head>
<body>
<h1>CG-connector</h1>
{body}
</body>
</html>
"""

INVITE = """<p>Tomek zaprasza Cię do CG-connector: Twoje treningi z Garmina w aplikacji, z Claude
i z wysyłaniem treningów na zegarek.</p>
<h2>1. Zainstaluj aplikację</h2>
<p>Stuknij przycisk, a gdy plik się pobierze, otwórz go i stuknij <strong>Zainstaluj</strong>.
Gdy telefon zapyta, pozwól przeglądarce instalować aplikacje (ostrzeżenie Play Protect:
<em>Zainstaluj mimo to</em>). Masz już aplikację? Przejdź do kroku 2.</p>
<a class="button" href="{apk}">Pobierz aplikację</a>
<p><small>Nie pobiera się? <a href="{releases}">Strona wydania</a>: plik <code>.apk</code>
w sekcji Assets.</small></p>
<h2>2. Otwórz zaproszenie w aplikacji</h2>
<p>Wróć na tę stronę i stuknij przycisk. Aplikacja otworzy się z wpisanym adresem i kodem
i dalej poprowadzi Cię krok po kroku (Garmin, potem Claude).</p>
<a class="button secondary" href="{deep_link}">Otwórz w aplikacji</a>
<p><small>Przycisk nie działa? Otwórz aplikację i przepisz w niej adres serwera:
<code>{server}</code>, kod: <code>{code}</code>. Kod jest jednorazowy i ważny 7 dni; nie
przesyłaj go dalej.</small></p>
"""

INVALID = """<p>Ten link zaproszenia jest niepełny. Poproś Tomka o nowy.</p>"""


def join_router(settings: Settings) -> APIRouter:
    router = APIRouter()

    @router.get(JOIN_PATH, include_in_schema=False)
    def join(code: Annotated[str, Query(max_length=200)] = "") -> HTMLResponse:
        if not CODE_RE.fullmatch(code):
            return HTMLResponse(PAGE.format(body=INVALID), status_code=400)
        server = settings.public_url
        deep_link = f"{DEEP_LINK}?{urlencode({'server': server, 'code': code})}"
        body = INVITE.format(
            apk=escape(APK_URL),
            releases=escape(RELEASES_URL),
            deep_link=escape(deep_link),
            server=escape(server),
            code=escape(code),
        )
        return HTMLResponse(PAGE.format(body=body))

    return router


def invite_link(settings: Settings, code: str) -> str:
    return f"{settings.public_url}{JOIN_PATH}?{urlencode({'code': code})}"
