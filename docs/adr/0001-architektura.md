# ADR-0001: Architektura CG-connector

- Status: zaakceptowana
- Data: 2026-11-18

## Kontekst

Chcemy połączyć zegarki Garmin (Enduro 3, Fenix 8 Pro) z Claude (także darmowy plan) i z aplikacją Android, za 0 zł, dla dwóch użytkowników. Jedyną maszyną, która może pełnić rolę serwera, jest laptop z Windows + WSL, włączany ręcznie. Repo jest publiczne.

## Decyzja

- Jeden serwer `cg-server` (Python 3.12, FastAPI, SQLite, APScheduler) w jednym kontenerze Docker w WSL: REST API dla aplikacji, endpoint MCP (Streamable HTTP) dla claude.ai, sync o 14:00 z catch-upem po starcie/wybudzeniu, outbox wysyłek treningów.
- Dostęp do Garmin Connect przez nieoficjalną bibliotekę `python-garminconnect`, schowaną za interfejsem `GarminGateway`.
- Dostęp z internetu przez tunel HTTPS bez otwierania portów (szczegóły: ADR-0002).
- Aplikacja Android offline-first (Room + WorkManager), kolejka wysyłek.
- Wieloużytkownikowość od początku: każdy rekord ma `user_id`, użytkownik wynika wyłącznie z tokenu.

## Rozważone i odrzucone

- **Sync w GitHub Actions** (działa bez laptopa): publiczne repo + dane zdrowotne + tokeny Garmina w chmurze CI to za duże ryzyko.
- **Sync na telefonie (Chaquopy):** możliwy w v1.1, jeśli laptop będzie zbyt często wyłączony. Ocena w `docs/research/`.
- **Płatne pośredniki (np. athletedata):** sprzeczne z budżetem 0 zł.
- **intervals.icu:** stabilne darmowe API; zostaje jako plan B, gdyby nieoficjalna biblioteka przestała działać.

## Konsekwencje

- Claude i wysyłka treningów działają tylko przy włączonym laptopie; aplikacja musi to dobrze komunikować.
- Ryzyko zmian w nieoficjalnym API Garmina; łagodzone przypiętymi wersjami i interfejsem `GarminGateway`.
- Migracja na inny komputer = przeniesienie folderu danych + `.env`.
