# AcousticLogger

Mobilna aplikacja Android (Proof of Concept) do **skanowania pomieszczenia** i **analizy akustycznej**:
- rejestracja surowego audio (PCM → WAV),
- zbieranie klatek z kamery + orientacji IMU,
- budowa przybliżonej chmury punktów 3D (`room_model.ply`),
- szacunek materiałów i współczynników pochłaniania,
- pomiar czasu pogłosu **RT60** z impulsu akustycznego (np. klask).

> **Uwaga:** To projekt badawczy / PoC — model 3D i klasyfikacja materiałów są przybliżone, nie zastępują profesjonalnych pomiarów akustycznych.

## Zrzut ekranu

Dodaj zrzut ekranu aplikacji jako `docs/screenshot.png`, np. po wykonaniu skanu:

![AcousticLogger screenshot](docs/screenshot.png)

## Wymagania

| Wymaganie | Wartość |
|-----------|---------|
| Android | API 28+ (Android 9+) |
| Sprzęt | Kamera, mikrofon, czujnik orientacji |
| Narzędzia (build) | Android Studio, JDK 17 |

## Instalacja na telefonie

### Opcja A — Android Studio (zalecane)

1. Sklonuj repozytorium:
   ```bash
   git clone https://github.com/Permurez/AcousticLogger.git
   cd AcousticLogger
   ```
2. Otwórz folder w **Android Studio** → poczekaj na Gradle Sync.
3. Włącz **Opcje deweloperskie** i **Debugowanie USB** na telefonie.
4. Podłącz telefon, wybierz urządzenie i kliknij **Run ▶**.

### Opcja B — APK z GitHub Actions

1. Wejdź w zakładkę **Actions** → ostatni zielony build → pobierz artefakt `app-debug-apk`.
2. Zainstaluj `app-debug.apk` na telefonie (zezwalając na instalację z nieznanego źródła).

### Opcja C — lokalny build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Uprawnienia przy pierwszym uruchomieniu

- **Mikrofon** — nagrywanie audio
- **Kamera** — skan wizualny pomieszczenia
- **Dostęp do plików** (Android 11+) — zapis wyników w folderze Pobrane

## Jak używać

1. Naciśnij **START**.
2. Po ~1,5 s aplikacja ** sama odtworzy sygnał testowy** z głośnika (nie musisz klaskać).
3. Przez 15–30 s powoli obracaj telefon — skieruj kamerę na ściany, podłogę i sufit.
4. Naciśnij **STOP** i poczekaj 5–20 s (nie zamykaj aplikacji).
5. Przeczytaj **ekran wyników**; pliki sesji trafią do:

```
/storage/emulated/0/Download/AcousticLogger_YYYYMMDD_HHMMSS/
├── recording.wav          # audio PCM 48 kHz mono 16-bit
├── audio_chunks.csv       # znaczniki czasu chunków audio
├── raw_telemetry.csv      # IMU + metadane klatek kamery
├── room_model.ply         # chmura punktów 3D
└── acoustic_report.json   # RT60, materiały, pochłanianie
```

## Architektura

```
MainActivity / ResultsActivity
        │
   CoreController
   ├── AudioTelemetry   (AudioRecord, PCM)
   ├── CameraTelemetry  (CameraX)
   ├── ImuTelemetry     (ROTATION_VECTOR)
   ├── RoomModelBuilder (chmura punktów → PLY)
   ├── MaterialAbsorptionEstimator
   └── AcousticAnalyzer (RT60, Sabine)
```

## Licencja

[MIT](LICENSE) — Copyright (c) 2026 Permurez

## Autor

[Permurez](https://github.com/Permurez)
