# RiotClientCompanion — Dependency Upgrade Guide

## Project Type
**Kotlin/Gradle JVM Application**

## Check for Updates
```bash
cd D:/Coding\ -\ Unsynced/RiotClientCompanion
gradle dependencyUpdates
```

## Current Dependencies
- Kotlin: 1.9.21
- TornadoFX: 1.7.20 (UNMAINTAINED — use caution)
- OkHttp: 4.11.0
- Exposed: 0.52.0
- SQLite JDBC: 3.46.0.0

## Upgrade Procedure (One at a Time)

### 1. Check Changelog
```bash
# Visit:
# https://github.com/JetBrains/kotlin/releases
# https://square.github.io/okhttp/changelog/
# Search for: "Breaking Changes"
```

### 2. Update build.gradle.kts (One Line)
```kotlin
// Edit dependencies block, change ONE implementation:
implementation("org.jetbrains.kotlin", "kotlin-jvm", "1.10.0")
// or
implementation("com.squareup.okhttp3", "okhttp", "4.12.0")
```

### 3. Build
```bash
gradle clean build
# Watch for deprecation warnings
```

### 4. Test
```bash
gradle run
# OR: java -jar build/libs/League-Completionist-Client.jar

# Verify:
# - UI renders (windows, buttons clickable)
# - LoL client API calls work
# - Database reads/writes work
# - JAR exports to config.json exportDirectory
```

### 5. Commit (If Successful)
```bash
git add build.gradle.kts
git commit -m "Upgrade Kotlin 1.9.21 → 1.10.0"
```

## Breaking Changes to Watch
- **Kotlin 1.9 → 1.10**: Minimal breaking changes (mostly safe)
- **OkHttp 4.11 → 4.12**: 
  - Certificate pinning syntax may change
  - Custom interceptors need to handle new IOException types
- **TornadoFX 1.7**: UNMAINTAINED (frozen since 2020) — do NOT upgrade
- **Exposed 0.52**: Database ORM stable

## Future Migration (Not Priority)
```
Current: TornadoFX 1.7 (unmaintained, 2020 era)
Target:  JavaFX 21 + Kotlin 1.10+ (modern, maintained)

Timeline: Plan for future refactor (6+ months)
Benefits: Official support, new UI controls, better performance
```

## Rollback
```bash
git checkout build.gradle.kts
gradle clean build
```

## Known Issues / Hard Failures

**TornadoFX 1.7 cannot be upgraded** — Project is UNMAINTAINED (last update 2020).
- DO NOT attempt upgrade to 1.8+ (does not exist, project abandoned)
- Current version 1.7.20 is stable but frozen
- Plan for JavaFX 21 migration in future refactor (6+ months)
- Workaround for now: Keep TornadoFX 1.7.20 as-is, upgrade everything else

**OkHttp + Custom Interceptors:** If code uses custom interceptors, check release notes.
- Exception handling changed in 4.12+
- If upgrade fails: revert OkHttp, try intermediate version

## Notes
- Gradle DSL is in Kotlin (build.gradle.kts) — type-safe
- TornadoFX is EOL and CANNOT be upgraded (skip it)
- OkHttp is actively maintained (safe to upgrade)
- shadowJar creates fat JAR (check it works after upgrade)
- Local LoL client API is brittle (test thoroughly)
