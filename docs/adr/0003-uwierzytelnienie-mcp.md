# ADR-0003: Uwierzytelnienie konektora MCP — OAuth 2.1 z kodem CG

- Status: zaakceptowana (do potwierdzenia spike'iem z prawdziwym claude.ai)
- Data: 2026-11-18
- Research: [docs/research/mcp-tunel.md](../research/mcp-tunel.md)

## Kontekst

Konektor musi działać na darmowym planie Claude (konto brata). Stały nagłówek `Authorization` w konektorze jest w becie tylko dla wybranych organizacji, więc na Free nie możemy na nim polegać.

## Decyzja

Minimalny serwer OAuth 2.1 (Dynamic Client Registration + PKCE) w `cg-server`, zbudowany na `OAuthAuthorizationServerProvider` z oficjalnego SDK `mcp` 2.x. Na stronie autoryzacji użytkownik wkleja swój **osobisty kod CG** (generowany poleceniem CLI, w bazie tylko hash). Token dostępu wskazuje użytkownika (`subject`), a każde narzędzie filtruje dane po nim. Refresh tokeny są rotowane.

## Rozważone

- Stały nagłówek (pierwotny plan A): niedostępny na Free.
- Sekret w ścieżce URL + „No sign-in” (plan C): działa, ale Anthropic go odradza; tylko awaryjnie.

## Konsekwencje

- Więcej kodu niż przy nagłówku (ok. 1 dzień), ale jedno rozwiązanie dla obu kont.
- Wymagane: 401 z `resource_metadata`, `/.well-known/oauth-protected-resource`, `/.well-known/oauth-authorization-server`, callback `https://claude.ai/api/mcp/auth_callback`.
