# mujrozhlas-simple-downloader

Jednoduchý stahovač audií z [mujrozhlas.cz](https://www.mujrozhlas.cz). Vlož URL,
stáhne se to jako **M4B** (s obálkou a kapitolami) a objeví se v přehledu se stavem.

- **Jednotlivé audio** → jeden M4B s obálkou.
- **Seriál** → stáhnou se všechny vydané díly; pokud ještě nevyšly všechny,
  položka čeká a jednou denně se zkusí zbylé díly dotáhnout (nebo ručně tlačítkem
  „Zkusit dotáhnout teď"). Až jsou všechny díly, složí se výsledný M4B.
- **Smazání** z přehledu odebere položku z DB a její složku přesune do `deleted/`
  (na disku ji pak smažeš ručně).

URL se na konkrétní obsah překládá deterministicky — z HTML stránky se přečte
embedded `data-entry` atribut s `uuid`+`type`, žádné hádání podle názvu.

## Požadavky

- Java 25
- `ffmpeg` v PATH

## Build & běh

```bash
./gradlew shadowJar
java -jar build/libs/mrsd-all.jar
```

Otevři http://localhost:8080.

## Konfigurace (proměnné prostředí)

| Proměnná | Výchozí | Význam |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `DOWNLOAD_DIR` | `downloads` | kam se ukládají M4B |
| `DELETED_DIR` | `deleted` | kam se přesouvají smazané položky |
| `DB_PATH` | `data/mrsd.db` | SQLite databáze |
| `AUTH_USER`, `AUTH_PASS` | – | volitelná basic auth na celou appku |

## Docker

```bash
docker compose up -d --build
```

Data, stažené soubory a smazané položky jsou v `./data`, `./downloads` a `./deleted`.
