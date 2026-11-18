# Architektura

Pełny opis: [MASTER_PROMPT.md, sekcja 3](../MASTER_PROMPT.md#3-architektura). Decyzje: [docs/adr](adr/).

```
Zegarek ──BT──> Garmin Connect (chmura) <──python-garminconnect── cg-server (laptop: Windows → WSL → Docker)
                                                                      │  /api/v1  /mcp  /health
                                                         tunel HTTPS ─┤
                                                 ┌────────────────────┴───────────────┐
                                          Aplikacja Android                      claude.ai (konektor MCP)
```
