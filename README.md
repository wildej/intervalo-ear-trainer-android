# Intervalo

`Intervalo` — Android-приложение для тренировки распознавания музыкальных интервалов на слух. Проект написан на Kotlin с UI на Jetpack Compose и ориентирован на офлайн-использование без аккаунтов и сетевых зависимостей.

## Что есть в приложении

### 1. Обычная тренировка

- выбор набора интервалов перед началом сессии;
- опция фиксированной базовой ноты `C4`;
- генерация случайного вопроса из выбранных интервалов;
- повторное воспроизведение текущего интервала;
- мгновенная проверка ответа;
- экран результатов с числом верных ответов и точностью.

### 2. Режим показа

- список всех интервалов;
- прослушивание каждого интервала по нажатию без проверки ответа.

### 3. Игровой режим

- матчинг «звук ↔ название интервала»;
- 5 жизней на игру;
- раунды по 6 интервалов;
- сохранение локального рекорда по числу пройденных раундов.

### 4. Служебные экраны

- экран с правилами игрового режима;
- встроенная политика конфиденциальности;
- отправка последнего crash-отчета со стартового экрана, если отчет существует.

## Стек

- Kotlin
- Jetpack Compose
- Material 3
- Navigation Compose
- Android ViewModel + `StateFlow`
- Coroutines
- Timber

## Требования к окружению

- JDK `17`
- Android SDK с `compileSdk 34` и `targetSdk 34`
- `minSdk 26`
- Android Studio с установленными SDK Platform / Build Tools

Проверка окружения:

```bash
java -version
echo $ANDROID_HOME
```

Если `ANDROID_HOME` не задан, достаточно корректного `local.properties` с `sdk.dir=...`.

## Быстрый запуск

### Через Android Studio

1. Откройте проект.
2. Дождитесь Gradle Sync.
3. Запустите приложение на устройстве или эмуляторе.

### Через терминал

Собрать debug APK:

```bash
./gradlew :app:assembleDebug
```

APK будет лежать по пути:

`/work/intervalo-ear-trainer-android/app/build/outputs/apk/debug/app-debug.apk`

Установить на устройство:

```bash
adb devices
adb install -r /work/intervalo-ear-trainer-android/app/build/outputs/apk/debug/app-debug.apk
```

## Release-сборка

В проекте поддерживается подпись релизной сборки через файл `keystore.properties` в корне репозитория.

Ожидаемые ключи:

- `storeFile`
- `storePassword`
- `keyAlias`
- `keyPassword`

Если `keystore.properties` отсутствует, проект все равно собирается, но релизная подпись не будет настроена автоматически.

Собрать release APK:

```bash
./gradlew :app:assembleRelease
```

## Как устроено аудио

Приложение выбирает первый доступный способ воспроизведения:

1. `SoundFontIntervalAudioPlayer` с `app/src/main/assets/soundfonts/Chorium.SF2`
2. `MultiSampleIntervalAudioPlayer` с WAV-сэмплами из `app/src/main/assets/audio/piano_teddy/`
3. `AssetSampleIntervalAudioPlayer` с `app/src/main/assets/audio/piano_ref.wav`
4. запасной синтезатор на синусоиде

Тайминги воспроизведения задаются в `IntervalPlaybackTiming`.

## Тесты

Unit-тесты:

```bash
./gradlew :app:testDebugUnitTest
```

UI / instrumentation тесты:

```bash
./gradlew :app:connectedDebugAndroidTest
```

## Crash-отчеты

Приложение сохраняет crash-отчеты во внутреннее хранилище:

`/data/data/com.muxaeji.intervalo/files/crash-reports/`

Для debug-сборки отчет можно выгрузить через ADB:

```bash
adb shell run-as com.muxaeji.intervalo ls files/crash-reports
adb exec-out run-as com.muxaeji.intervalo cat files/crash-reports/<имя_файла> > crash-report.txt
```

Если приложение запускается, последний отчет можно отправить прямо из стартового меню кнопкой `Отправить crash-отчет`.

## Полезные документы

- [`QA_STABILIZATION.md`](QA_STABILIZATION.md) — чеклист для проверки стабильности аудио
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) — лицензии сторонних библиотек и ассетов
- [`LICENSE`](LICENSE) — лицензия исходного кода проекта
