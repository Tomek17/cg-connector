# CG-connector

Most między zegarkami **Garmin** a **Claude**. Pozwala:

1. pobierać dane treningowe z Garmin Connect (aktywności + VO2max) raz dziennie i trzymać je w telefonie (offline),
2. rozmawiać o nich z Claude w zwykłym oknie czatu claude.ai (także na darmowym planie) przez własny konektor MCP,
3. wysyłać treningi (biegowe, siłowe, bouldering i „inne” z samym opisem) na zegarek przez kalendarz Garmin Connect.

> ⚠️ Projekt korzysta z **nieoficjalnego** API Garmin Connect (`python-garminconnect`). Może przestać działać bez ostrzeżenia. Używasz na własne ryzyko.

## Jak to działa

- Serwer (`server/`, Python + FastAPI + SQLite) działa w Dockerze na laptopie z Windows + WSL i jest wystawiony przez tunel HTTPS.
- Aplikacja Android (`android/`, Kotlin + Compose) trzyma dane lokalnie i działa offline.
- Claude łączy się z serwerem jako własny konektor MCP (`/mcp`).

### Ograniczenia (ważne)

- **Claude i wysyłanie treningów działają tylko, gdy laptop-serwer jest włączony** i ma internet. Gdy jest wyłączony, aplikacja kolejkuje treningi i wysyła je przy następnym kontakcie.
- Synchronizacja odbywa się o 14:00 albo zaraz po włączeniu laptopa, jeśli 14:00 została pominięta.
- Dane w aplikacji są dostępne cały czas, także offline.

## Dokumentacja

Dla użytkowników:

- [Instalacja serwera (laptop z Windows + WSL)](docs/instalacja-serwera.md)
- [Konfiguracja Claude (konektor MCP, także darmowy plan)](docs/konfiguracja-claude.md)
- [Instrukcja dla brata (drugi użytkownik, bez wiedzy technicznej)](docs/dla-brata.md)
- [Wydania i instalacja APK](docs/release.md)
- [Test serwera na VPS (czy Garmin działa z chmury)](docs/test-vps.md)
- [Aplikacja Android: budowanie i stan](android/README.md)

Dla programistów:

- [Architektura](docs/architektura.md)
- [Bezpieczeństwo (model zagrożeń, zgłaszanie problemów)](docs/bezpieczenstwo.md)
- [Decyzje architektoniczne (ADR)](docs/adr/)
- [Kontrakt REST API (OpenAPI)](docs/api/)
- [Research](docs/research/)
- [Master Prompt (zasady pracy zespołu)](MASTER_PROMPT.md)

## Status

Wersja 1.0 planowana na 2026-12-02. Plan i zasady pracy: [MASTER_PROMPT.md](MASTER_PROMPT.md).

## Licencja

MIT
