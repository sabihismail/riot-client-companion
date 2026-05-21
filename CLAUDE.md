# RiotClientCompanion

## Running (Dev)

Requires Java 8 (JavaFX is bundled with Java 8; the javafx plugin is intentionally disabled).

```powershell
$env:JAVA_HOME = "<path-to-jdk8>"; .\gradlew.bat run
```

## Building (Fat JAR)

```powershell
$env:JAVA_HOME = "<path-to-jdk8>"; .\gradlew.bat shadowJar
```

Output: `build/libs/League-Completionist-Client.jar` (also copied to `exportDirectory` in `config.json`)

## LCU API

The League client exposes a local HTTPS API. Auth details are in the lockfile while the client is running:

```
<League install dir>/lockfile
```

Format: `process:pid:port:password:protocol`

```bash
# Example request
curl -sk -u "riot:<password>" "https://127.0.0.1:<port>/<endpoint>"
```

Useful endpoints:
- `/lol-patch/v1/game-version` — current game version string
- `/lol-champion-mastery/v1/local-player/champion-mastery` — mastery data

## Notes

- Must use Java 8 — JavaFX is bundled with Java 8 and the plugin is intentionally disabled
- Java 21 will NOT work — JavaFX missing
- LoL client must be running for the app to connect
