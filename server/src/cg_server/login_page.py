"""Login page of the OAuth flow: the user pastes the personal CG code (Polish UI, no JS)."""

from html import escape
from typing import Annotated

from fastapi import APIRouter, Form
from fastapi.responses import HTMLResponse, RedirectResponse, Response

from cg_server.oauth import LOGIN_PATH, CgOAuthProvider, LoginRequestInvalid, WrongCode

PAGE = """<!DOCTYPE html>
<html lang="pl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CG-connector: połącz z Claude</title>
<style>
body {{ font-family: system-ui, sans-serif; max-width: 28rem; margin: 3rem auto; padding: 0 1rem; }}
input, button {{ font-size: 1rem; width: 100%; box-sizing: border-box; padding: .6rem; }}
button {{ margin-top: 1rem; }}
.error {{ color: #b00020; }}
</style>
</head>
<body>
<h1>CG-connector</h1>
{body}
</body>
</html>
"""

FORM = """<p>Claude chce połączyć się z Twoimi danymi z Garmin Connect.</p>
{error}
<form method="post" action="{action}">
<input type="hidden" name="request" value="{request}">
<label for="code">Wklej swój kod CG-connector</label>
<input id="code" name="code" type="password" autocomplete="off" required autofocus>
<button type="submit">Połącz z Claude</button>
</form>
<p><small>Kod dostajesz od administratora serwera (polecenie cg-server user-code).</small></p>
"""

EXPIRED = """<p class="error">Link logowania wygasł albo jest nieprawidłowy.</p>
<p>Wróć do Claude i połącz konektor ponownie.</p>
"""


def _html(body: str, status_code: int) -> HTMLResponse:
    return HTMLResponse(PAGE.format(body=body), status_code=status_code)


def _form(request_id: str, error: str = "", status_code: int = 200) -> HTMLResponse:
    error_html = f'<p class="error">{escape(error)}</p>' if error else ""
    body = FORM.format(error=error_html, action=LOGIN_PATH, request=escape(request_id))
    return _html(body, status_code)


def login_router(provider: CgOAuthProvider) -> APIRouter:
    router = APIRouter(include_in_schema=False)

    @router.get(LOGIN_PATH)
    def login_form(request: str = "") -> Response:
        if not provider.login_request_valid(request):
            return _html(EXPIRED, 400)
        return _form(request)

    @router.post(LOGIN_PATH)
    def login_submit(
        request: Annotated[str, Form()] = "", code: Annotated[str, Form()] = ""
    ) -> Response:
        try:
            url = provider.complete_login(request, code)
        except LoginRequestInvalid:
            return _html(EXPIRED, 400)
        except WrongCode:
            if not provider.login_request_valid(request):  # too many attempts
                return _html(EXPIRED, 401)
            return _form(request, "Nieprawidłowy kod. Sprawdź go i spróbuj ponownie.", 401)
        return RedirectResponse(url, status_code=302)

    return router
