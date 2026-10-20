# OpenBarnyard — Android Port / Android-порт

[![Android](https://github.com/Player124413/OpenBarnyard/actions/workflows/Android.yaml/badge.svg)](https://github.com/Player124413/OpenBarnyard/actions/workflows/Android.yaml)

**Русская версия ниже · English version below the Russian one.**

---

## 🇷🇺 По-русски

Android-порт открытой декомпиляции игры **Barnyard** (движок TOSHI 2.0, Blue Tongue Entertainment).
Файлов игры в АПК **нет** — нужна ваша собственная ПК-копия игры (см. «Данные игры» в лаунчере).

### Что уже работает

| Часть | Статус |
|---|---|
| Лаунчер (устройство, статус данных, быстрые действия) | ✅ полностью |
| Настройки графики/производительности + автопресет под ваш телефон | ✅ полностью |
| Редактор сенсорного управления (перетаскивание, размеры, прозрачность, переназначение кнопок на настоящие команды движка, профили, экспорт/импорт, режим для левшей) | ✅ полностью |
| Нативное ядро: EGL/GLES2–3, динамическое разрешение, ограничение FPS, рантайм-детект CPU (NEON и др.) | ✅ полностью |
| Интерактивная техно-демо сцена «Ферма» (коровой можно бегать/прыгать настоящими сенсорными кнопками) | ✅ полностью |
| Игровой рендерер TOSHI (шейдеры DX8 → GLES), звук FMOD, видео Bink | 🚧 в работе — см. [PORTING.md](PORTING.md) |

Честно и прямо: **полный игровой процесс оригинальной игры** появится, когда будет доведён GL-рендерер движка (в upstream-проекте игровые шейдеры сегодня существуют только для DirectX 8). Весь Android-слой вокруг него — лаунчер, ввод, оптимизации, сборка — уже готов и протестирован, а сенсорное управление сразу говорит на родных командах движка (`AInputCommand` из `OpenBarnyard/Source/Input/AInputMap.h`), поэтому подключение геймплея не потребует переделки управления.

### Какой APK качать

| Файл | Кому |
|---|---|
| `OpenBarnyard-vX.Y.Z-universal.apk` | всем (все процессоры в одном файле) |
| `...-arm64-v8a.apk` | современные 64-битные ARM телефоны/планшеты |
| `...-armeabi-v7a.apk` | старые 32-битные ARM телефоны |
| `...-x86_64.apk` | эмуляторы, ChromeOS, Intel-устройства 64 бит |
| `...-x86.apk` | древние 32-битные Intel-планшеты |

### Установка и данные игры

1. Поставьте APK.
2. Откройте лаунчер → «Данные игры». Там показан путь вида
   `Android/data/org.opentoshi.openbarnyard/files/GameData/` — скопируйте туда содержимое вашей ПК-установки игры (папку, где лежат `Data/` и `Barnyard.exe`) через USB или файловый менеджер. Либо кнопкой «Выбрать папку» укажите уже существующую папку.
3. Лаунчер сам проверит установку (количество `.trb/.ttl` библиотек и т.д.) и подскажет, если выбрана не та папка.
4. «Графика и производительность» — оставьте «Авто» или настройте руками: разрешение рендера, динамическое разрешение, лимит FPS (30/60/90/120/∞), MSAA, вибрацию кнопок и т.д.
5. «Редактор сенсорного управления» — расставьте кнопки под себя. Можно сохранять несколько профилей и делиться ими файлами `.json`.
6. «Играть»!

Разрешений приложение не запрашивает вообще, сети в нём нет.

### Оптимизация под процессоры

- Отдельные сборки `.so` под каждый ABI с флагами именно под его базовую линию (arm64: `armv8-a`; arm32: `armv7-a + VFPv3 + Thumb-2` **без требования NEON** — запустится даже на Tegra 2; при этом NEON-путь математики диспетчится в рантайме по `HWCAP`, x86_64: SSE4.2, x86: SSE3).
- Thin LTO, `-O3`, GC-секций, скрытая видимость символов, статическая libc++ (не нужен `libc++_shared.so`), выравнивание под страницы 16 КиБ для Android 15+ (64 бит).
- Динамическое разрешение с гистерезисом: слабый GPU не превращается в слайд-шоу, сильный рендерит в полном качестве; фреймпейсинг под лимит FPS экономит батарею.
- Автопресет по объёму RAM, ядрам, версии GLES, битности и частоте экрана (120 Гц флагманы не упираются в 60 FPS, а 1–2 ГБ бюджетники получают «Картошку»).
- Сцена техно-демо специально «запечена» в один статичный VBO: ~12 draw call на кадр — тянет вообще всё с GLES2.

### Сборка из исходников

Нужны: JDK 17, Android SDK (platform 34, build-tools 34) и NDK 27. Gradle/Maven **не нужны**:

```sh
python3 Android/build.py            # APK появятся в Android/dist/
python3 Android/build.py --test     # JVM юнит-тесты лаунчера
sh Android/tools/run_host_tests.sh  # C++ тесты ядра + проверки контрактов
python3 Android/verify_apk.py Android/dist/*.apk
```

Для Android Studio рядом лежит обычный Gradle-проект (`Android/settings.gradle`) — он собирает те же исходники через CMake. Релизная подпись: переменные `OB_KEYSTORE`, `OB_KEYSTORE_PASS`, `OB_KEY_ALIAS`, `OB_KEY_PASS` (без них — автоматически генерируемый debug-ключ).

CI: `.github/workflows/Android.yaml` — юнит-тесты (gcc и clang), сборка всех ABI, проверка APK (ELF/ABI/содержимое), smoke-тест на эмуляторе со скриншотами, публикация релиза по тегу `v*`.

---

## 🇬🇧 English

Android port of the **OpenBarnyard** decompilation (Barnyard, TOSHI 2.0 engine).
**No game files are included** — bring your own PC copy of Barnyard.

### Status

Working today: the full launcher (device auto-tuning, game-data verification),
graphics/performance settings with an Auto preset, a WYSIWYG touch-controls
editor whose buttons bind to the engine's real `AInputCommand` ids (profiles,
export/import, left-handed mirror), and a native GLES2/3 core (dynamic
resolution, frame pacing, runtime SIMD dispatch) rendering an interactive
farm tech-demo you can actually play with those controls.

Remaining for full gameplay: TOSHI's GL renderer for the game's materials
(upstream shaders are DirectX 8 only), plus FMOD/Bink replacements. The
complete, honest roadmap lives in [PORTING.md](PORTING.md) — the Android
platform layer is designed so the engine slots in without reworking input.

### APKs

`universal` runs everywhere; per-ABI builds are smaller:
`arm64-v8a` (modern phones), `armeabi-v7a` (32-bit ARM, no NEON required),
`x86_64` (emulators/ChromeOS), `x86` (32-bit Intel).

### Setup

Install the APK → **Game data** shows the `.../files/GameData/` folder; copy
your PC installation (the folder containing `Data/` and `Barnyard.exe`) there
or pick an existing folder. Tune **Graphics & performance** (or leave Auto),
arrange controls in the editor, press **Play**. The app requests zero
permissions and contains no networking.

### Building

JDK 17 + Android SDK + NDK 27 only — no Gradle needed:

```sh
python3 Android/build.py            # APKs land in Android/dist/
python3 Android/build.py --test     # launcher JVM unit tests
sh Android/tools/run_host_tests.sh  # native core tests + contract checks
```

A Gradle project for Android Studio lives alongside (`settings.gradle`) and
builds the same sources through CMake. CI (`.github/workflows/Android.yaml`)
runs unit tests with gcc and clang, builds every ABI, verifies the APKs'
ELF/ABI structure, smoke-tests on an emulator with screenshots, and publishes
GitHub releases from `v*` tags.

Per-CPU optimisation summary: per-ABI baseline flags (32-bit ARM deliberately
assumes no NEON; NEON math is HWCAP-dispatched), thin LTO + `-O3`, hidden
visibility, section GC, static libc++, 16 KiB page alignment for Android 15+,
a hysteresis dynamic-resolution controller and an FPS pacer, plus an
Auto-quality heuristic keyed on RAM/cores/GLES level/bitness/display Hz.
