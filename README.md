# ChessCheat Pro Android 3.0.0

Android-приложение для **разбора и тренировки**: импорт PGN/FEN, пошаговый просмотр партии, распознавание шахматной позиции по снимку экрана (ML на устройстве), автоматическое отслеживание ходов между снимками, анализ Stockfish (до 3 линий), редактор позиции и плавающее окно с последним результатом.

> Приложение не предназначено для подсказок в партиях против людей — это нарушает правила платформ. Захват экрана — по одному снимку и только после системного разрешения Android; непрерывного наблюдения за экраном нет (так было и в 2.5.0).

## Что нового в 3.0.0 (кратко)
| Область | Состояние |
|---|---|
| Распознавание 12 фигур + пустая клетка | Своя CNN (4×32×32 → 13 классов), веса обучены в `ml/`, лежат в `app/src/main/assets/piece_cnn.bin` (175 КБ), инференс на чистом Kotlin |
| Поиск доски на скриншоте | Грубый поиск + привязка к линиям сетки, точность ~1 px; доска на всю ширину; ручная область как запасной вариант |
| Восстановление позиции | Ориентация (белые/чёрные снизу) и расстановка из одного снимка; неуверенные клетки помечаются, а не угадываются |
| Отслеживание партии | `GameTracker`: ходы, взятия (в т.ч. на проходе), рокировки, превращения; пропущенные полуходы; неоднозначность → отказ, а не догадка |
| Правила | Собственный полный генератор легальных ходов `ChessRules.kt` (проверен perft, см. AUDIT_REPORT) |
| Stockfish | UCI-клиент `UciEngine.kt` (MultiPV, отмена, оценка с точки зрения белых), встроенный `libstockfish.so` или импорт ELF |
| UI | Автооткрытие экрана сканирования после снимка, правка клеток/хода/рокировки/en-passant, ход «Сканировать следующий ход», отмена хода, копирование PGN, стрелка лучшего хода, линии анализа в SAN |

Подробности — `CHANGELOG.md`, проверки и **ограничения** — `AUDIT_REPORT.md`.

## Важно: что не проверено
* **APK в этой среде не собирался** (нет Android SDK и доступа к Maven). Gradle-сборка, Compose-экраны, MediaProjection, оверлей и запуск Stockfish на устройстве **не проверялись**. Первая же сборка на вашей машине/в CI (`.github/workflows/android.yml`) может выявить ошибки компиляции Compose-кода.
* ML-модель обучена на **синтетических** скриншотах (шрифтовые наборы фигур). Точность на реальных клиентах (chess.com, lichess и т. д.) **не измерена** и может быть заметно ниже. См. `ML_MODEL_SPEC.md`.

## Сборка
Требования: Android Studio / AGP 8.10.1, JDK 17, Android SDK 35.

1. Положите Stockfish (arm64) в проект: скачайте `stockfish-android-armv8` из официальных релизов Stockfish и выполните  
   `tools/bundle_stockfish.sh /путь/к/stockfish-android-armv8`  
   (файл ~80 МБ, поэтому в архив исходников он не входит; лицензия GPL-3.0 — см. `THIRD_PARTY.md`). Без него приложение собирается, а движок можно импортировать из интерфейса или будет доступен лишь облачный анализ.
2. `gradle :app:testDebugUnitTest :app:assembleDebug` (или CI-workflow).

## Тесты без Android SDK
`bash tools/run_jvm_tests.sh [фильтр]` — компилирует чистые Kotlin-модули и модульные тесты (`app/src/test`) через kotlinc из дистрибутива Gradle и запускает 105 проверок (правила/perft, трекер, детектор, паритет CNN с NumPy, сквозной тест на скриншотах, правки движка и т. д.).  
Живая проверка UCI-клиента на настоящем движке: `verification/v3/live/UciEngineLive.kt` (запускалась на Stockfish 11, собранном под x86; ARM-сборка 17.1 здесь запуститься не могла).

## Переобучение модели
```
python3 ml/gen_dataset.py ...        # синтетический датасет (воспроизводим, seed)
python3 ml/train.py --train data/train.npz --val data/val.npz --out models/full.npz --epochs 6
python3 ml/eval_model.py models/full.npz
python3 ml/export_weights.py models/full.npz app/src/main/assets   # + parity.bin для теста
```
Нужны только `numpy`, `Pillow`, `opencv` (cv2); для шрифтов используются системные шрифты со шахматными глифами. Результаты оценки: `ml/results/`.

## Структура
- `ChessRules.kt` — позиция, легальные ходы, SAN, FEN; `ChessSession.kt`, `ChessImport.kt` — PGN/FEN
- `BoardGeometryDetector.kt` → `PieceFeatureExtractor.kt` → `NativePieceNet.kt` → `BoardRecognizer.kt` → `BoardScanPipeline.kt` (`ScanSession`) → `GameTracker.kt` (`PositionReconstructor`)
- `UciEngine.kt`, `LocalStockfish.kt`, `ElfBinaryValidator.kt`, `CloudAnalysis.kt`, `EngineFormat.kt`
- `MainActivity.kt`, `BoardScanDialog.kt`, `BoardEditor.kt`, `ScreenCaptureService.kt`, `AnalysisOverlayService.kt`
- Устаревший код 2.x (`BoardImageAnalyzer`, `BoardOccupancyClassifier`, `BoardMoveTracker`, `BoardSquareTensorizer`) оставлен для совместимости тестов; новый конвейер его не использует.
- `ml/` — генерация данных, обучение, экспорт; `verification/` — JVM-проверки; `legacy-browser-extension/` — без изменений.
