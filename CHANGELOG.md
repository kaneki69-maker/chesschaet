# Changelog

## 3.0.0
**Распознавание**
- Новая сверточная сеть для 13 классов (пусто + 12 фигур) и цвета; веса обучены (`ml/`), встроены как `piece_cnn.bin`; чистый Kotlin-инференс (`NativePieceNet`), побитово согласованная с NumPy-эталоном подготовка признаков (`PieceFeatureExtractor`).
- Признаки инвариантны к цвету клетки/теме (вычитание фона по рамке клетки + абсолютная яркость).
- Порог уверенности 0.70: неуверенные клетки не угадываются, а подсвечиваются.
- Удалён TFLite-путь (не использовался без модели; конвертера TF в среде разработки не было). Зависимость `tensorflow-lite` и `kchesslib` убраны.

**Доска и позиция**
- `BoardGeometryDetector` переписан: грубый поиск + точная привязка к 18 линиям сетки (~1 px), доска на всю ширину экрана, исправлено расхождение единиц `size` (доля меньшей стороны) и падавший тест.
- `BoardScanPipeline`/`ScanSession`: снимок → доска → 64 клетки → позиция; результат применяется только по кнопке «Применить» (предпросмотр не меняет состояние).
- `PositionReconstructor`: ориентация доски, допущения (ход, рокировка) явно показываются.

**Партия**
- `ChessRules.kt`: полный генератор легальных ходов, SAN, FEN, рокировка, en-passant, превращения, правила окончания (perft глубины 3–4 совпал с эталоном на 6 стандартных позициях, включая Kiwipete).
- `GameTracker`: определение ходов, взятий, рокировки, en-passant и превращений по последовательности снимков, до нескольких пропущенных полуходов; отмена хода; PGN.

**Stockfish**
- `UciEngine`: рукопожатие, MultiPV, глубина/время, потоковые обновления, отмена с ресинхронизацией, оценка всегда с точки зрения белых, отказ на позициях без ходов.
- `LocalStockfish`: встроенный `libstockfish.so` (jniLibs, arm64) приоритетнее импортированного; безопасная замена бинарника.
- `tools/bundle_stockfish.sh`.

**Интерфейс**
- Экран сканирования открывается сам после снимка; правка клеток, хода, рокировки, en-passant; ручная область доски.
- «Сканировать следующий ход», «Отменить ход», «Копировать PGN», переворот доски, стрелка/подсветка лучшего хода, три линии анализа в SAN, кнопка «Стоп».
- Иконка приложения; `versionName 3.0.0`, `versionCode 30`.

**Тесты**: 105 JVM-проверок, включая сквозной тест на скриншотах и паритет Kotlin↔NumPy.

## 2.5.0 и ранее
Без изменений: см. историю в архиве 2.5.0 (импорт PGN/FEN, редактор, occupancy-сканер, разовый MediaProjection, оверлей).
## 2.2.0
- Added pseudo-legal move generation from a previous FEN and exact occupancy-mask comparison.
- Added occupancy-only move suggestions for unique quiet moves, captures, en-passant and castling patterns, including flipped-screen coordinate mapping.
- Rejected impossible, noisy and ambiguous occupancy transitions instead of guessing.
- Connected the new inference path to the scan dialog and clarified that promotion piece identity and full king-safety legality are not inferred from occupancy alone.
- Added smoke and JUnit regression cases for quiet moves, captures, en-passant, flipped orientation and invalid/noisy masks.

## 2.1.0
- Added full before/after board transition inference from a previous FEN and a manually confirmed 64-square piece map.
- Added recognition of captures, kingside/queenside castling patterns, en-passant and pawn promotion.
- Added explicit validation and ambiguity reasons; inferred moves are displayed for manual confirmation and are never applied automatically.
- Integrated the full transition comparison into the screenshot scan dialog.
- Added regression coverage for captures, castling, en-passant, promotion and flipped-board coordinate mapping.

## 2.0.0
- Added automatic axis-aligned chessboard geometry detection in screenshots, with a confidence score and manual crop fallback.
- Moved the geometry search off the UI thread and added a busy state to prevent repeated scans.
- Added conservative move hinting when comparing a known previous FEN with a newly scanned occupancy map.
- Added synthetic regression coverage for board geometry, uniform-image rejection, quiet moves and black-bottom orientation.

## 1.10.0
- Hardened Android Stockfish binary validation and replacement recovery.
- Improved local UCI process failure reporting and cloud response validation.
- Preserved castling and en-passant metadata in manual position editing.

## 2.4.0
- Occupancy classification now returns a per-square uncertainty mask in addition to occupancy and foreground ratio.
- Cells close to the occupancy decision threshold are explicitly marked uncertain instead of being silently treated as reliable.
- Occupancy-only move inference rejects a scan containing uncertain cells, preventing a low-confidence screenshot from producing a misleading move hint.
- Scan dialog reports the number of uncertain cells and passes the uncertainty mask into move inference.
- Added smoke/unit coverage for the uncertainty mask and fail-closed move inference.

## 2.4.0
- Added `BoardSquareTensorizer`, a deterministic preprocessing stage that extracts all 64 squares in canonical chess order.
- Resizes each square to a configurable tensor size with bilinear sampling, excludes configurable inner borders, and normalizes RGB channels to [-1, 1].
- Supports reversed board orientation and validates image buffers and preprocessing parameters.
- Added unit and JVM smoke coverage for tensor dimensions, normalization, canonical ordering, orientation reversal, and invalid input handling.
- This is ML input preparation only. No trained piece-classification model or piece identity predictions are included.

## 2.5.0
- Integrated TensorFlow Lite inference runtime for per-square chess-piece classification.
- Added import, validation, and safe replacement of compatible `.tflite` models.
- Added 13-class output decoder (empty + 12 piece/color classes), confidence threshold, and conservative handling of uncertain squares.
- Added UI action to install a model and classify all 64 squares.
- Added unit tests for class mapping, empty squares, low confidence, logits, malformed output, and NaN values.
- Model weights are not bundled: a trained compatible model and representative validation dataset are still required.
