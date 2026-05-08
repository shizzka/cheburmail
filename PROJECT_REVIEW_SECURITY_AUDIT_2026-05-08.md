# Аудит v2 после фиксов CheburMail

Дата: 2026-05-08  
Проект: `/home/q/cheburmail`  
HEAD: `b916cba` (`fix(ui): полировка onboarding и UpdateBanner (v0.4.3)`)  
Предыдущий отчет: `PROJECT_REVIEW_SECURITY_AUDIT_2026-05-07.md`

## Объем

Проверено локально:

- security-sensitive код: прием/отправка сообщений, group control messages, app lock, SQLCipher migration, Tink/DataStore, FileSaver, update/release flow;
- Android manifest, backup policy, network security config;
- status remediation по findings из отчета 2026-05-07;
- Gradle-проверки `assembleDebug`, `testDebugUnitTest`, `lintDebug`.

Ограничения: не запускались instrumentation tests на устройстве/эмуляторе, не выполнялся динамический pentest, не выполнялся отдельный CVE/SCA-скан внешней базой уязвимостей. Lint dependency warnings использовались как локальный сигнал, но не заменяют SCA.

## Краткий вывод

Большая часть критичных и средних findings из первого аудита исправлена. В коде закрыты основные integrity-дыры: `DELETE:` теперь проверяет авторство/чат, `MEMBER_REMOVED` проверяет admin для групп с `createdBy`, FileSaver санитизирует имена, PIN переведен на PBKDF2+salt+throttling, screenshots default включен, OutboxDrain и SendWorker приведены к более безопасной модели.

Релизный baseline сейчас не зеленый: `assembleDebug` и unit-тесты проходят, но `lintDebug` падает с Compose error в новом onboarding reset. Это release blocker.

Открытых критических runtime-уязвимостей уровня первого отчета не найдено. Остались residual risks: legacy self-remove для старых групп без `createdBy`, subject-only ACK handler/tests как dead code, отсутствие regression-тестов на security hardening, app-lock без relock-on-background, и release script все еще может удалить stable tag при `FORCE=1`.

## Результаты проверок

### Сборка и unit-тесты

Команда:

```bash
env ANDROID_HOME=/home/q/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug testDebugUnitTest --no-daemon
```

Результат: `BUILD SUCCESSFUL in 31s`.

### Полный локальный Gradle run

Команда:

```bash
env ANDROID_HOME=/home/q/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug testDebugUnitTest lintDebug --no-daemon
```

Результат: failed из-за `lintDebug`.

Lint summary: `1 error, 84 warnings, 2 hints`.  
HTML report: `app/build/reports/lint-results-debug.html`  
Text report: `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`

Главная ошибка:

- `app/src/main/java/ru/cheburmail/app/ui/onboarding/OnboardingScreen.kt:22`
- `remember { viewModel.reset(); Unit }`
- Issue: `RememberReturnType`

## Статус findings из аудита 2026-05-07

| ID | Статус | Комментарий |
| --- | --- | --- |
| H1 DELETE control без авторизации | Fixed in code | `ReceiveWorker.kt:222-255` проверяет target exists, same chat, not outgoing, senderContactId совпадает с отправителем. Regression-тестов нет. |
| H2 MEMBER_REMOVED self-removal до admin check | Partially fixed | Для групп с `createdBy` admin check есть. Для legacy-групп без admin metadata self-remove все еще разрешен без admin. |
| H3 unit-тесты красные из-за Java/lazysodium | Fixed | `testImplementation("com.goterl:lazysodium-java:5.1.0")`; `testDebugUnitTest` проходит. |
| M1 Delivery ACK subject-only | Partially fixed | `ReceiveWorker` игнорирует ACK subject. Но `DeliveryReceiptHandler` и тесты subject-only ACK остались. |
| M2 App lock SHA-256 PIN | Mostly fixed | PBKDF2 + salt + throttling есть. Остались UX/lifecycle issues: нет relock-on-background и UI не показывает lockout countdown. |
| M3 screenshots default off | Fixed | `AppSettings.screenshotsBlocked` default `true`. |
| M4 FileSaver path traversal/API 29 lint | Fixed | `sanitizeFileName`, canonical path check, `@RequiresApi(Q)`. Старый lint blocker ушел. |
| M5 OutboxDrain bypass SyncFactory / SendWorker fallback account | Fixed in code | `OutboxDrainWorker` использует `SyncFactory`; `SendWorker` передает фактический failed config. |
| M6 OutboxDrain silent delete oversized BLOB | Fixed in current worker | Теперь queue entry/message помечаются `FAILED`. Историческая `MIGRATION_2_3` все еще удаляет oversized queue rows при upgrade с very old DB. |
| M7 Room schemas ignored | Fixed | `git ls-files app/schemas` показывает 9 schema-файлов. |
| M8 release tags delete/recreate | Partially fixed | По умолчанию tags immutable. Но `FORCE=1` может удалить stable release тоже. |
| M9 SQLCipher plaintext backup cleanup | Fixed | `DbCipherMigrator.cleanupPlaintextBackup()` вызывается до migrated-check. |
| L1 bot PII | Operational risk remains | `bot/` ignored, но `bot/subscribers.json` и `bot/inbox.jsonl` остаются локальными PII файлами. |
| L2 EncryptedEnvelope empty plaintext | Fixed | `fromBytes` теперь требует `>= NONCE + MAC`. |
| L3 backup policy pre-Android 12 | Fixed | Добавлен `full_backup_content.xml`; `allowBackup=false` сохранен. |

## Findings v2

### B1. Release blocker: lint падает на `remember { viewModel.reset(); Unit }`

Severity: High / release blocker  
Файл: `app/src/main/java/ru/cheburmail/app/ui/onboarding/OnboardingScreen.kt:22`

Новый fix для повторного добавления аккаунта сбрасывает `OnboardingViewModel` прямо во время composition:

```kotlin
remember { viewModel.reset(); Unit }
```

Compose lint правильно блокирует это: mutation во время composition может не соответствовать реально принятой composition и ломает модель side effects. Сейчас из-за этого `lintDebug` падает, значит release pipeline нельзя считать зеленым.

Важно: простая замена на `LaunchedEffect(Unit) { viewModel.reset() }` может вернуть исходный баг. Если старая VM уже имеет `isComplete=true`, первый composition успеет увидеть `isComplete` и вызвать `onComplete()` до запуска effect.

Рекомендация:

- сбрасывать VM перед навигацией в onboarding из Settings: `onAddAccount = { onboardingViewModel.reset(); navController.navigate(Routes.ONBOARDING) }`;
- или создавать onboarding VM с route/session key;
- или добавить явное состояние `resetDone`, чтобы экран не проверял `isComplete`, пока reset не выполнен.

### S1. Legacy-группы без `createdBy` все еще принимают self-remove без admin

Severity: Medium  
Файл: `app/src/main/java/ru/cheburmail/app/group/ControlMessageHandler.kt:187`

Для групп с `chat.createdBy` новый код корректно проверяет admin перед `MEMBER_REMOVED`. Но для legacy-групп без admin metadata остается fallback:

- если `targetEmail == selfEmail`, чат удаляется локально;
- admin sender не проверяется.

Это лучше, чем прежняя уязвимость для всех групп, но для старых групп риск сохраняется: известный контакт, знающий `chatId`, может попытаться удалить локальный legacy-чат у жертвы.

Рекомендация: мигрировать старые группы к `createdBy` при первом валидном `GROUP_INVITE`/admin event, либо перед legacy self-remove требовать локальное подтверждение пользователя, либо ограничить fallback только явным user-initiated leave flow.

### S2. Subject-only ACK handler и тесты остались как опасный dead code

Severity: Medium  
Файлы:

- `app/src/main/java/ru/cheburmail/app/transport/ReceiveWorker.kt:147`
- `app/src/main/java/ru/cheburmail/app/messaging/DeliveryReceiptHandler.kt:23`
- `app/src/test/java/ru/cheburmail/app/messaging/DeliveryReceiptTest.kt:81`

Production receive path теперь игнорирует ACK subject, что закрывает прямой spoofing path. Но в коде остался `DeliveryReceiptHandler.handleAck(subject)`, который по одному subject может обновить `SENT -> DELIVERED`, и unit-тесты все еще закрепляют это поведение.

Риск: будущий refactor может заново подключить handler и вернуть forgeable ACK.

Рекомендация: удалить `DeliveryReceiptHandler`/subject-only tests или переписать их под будущий encrypted/authenticated ACK формат. Если feature отключена по README, dead code лучше убрать.

### S3. Security hardening почти не покрыт regression-тестами

Severity: Medium  
Проверка: `rg -n "DELETE:|MEMBER_REMOVED|sanitizeFileName|PBKDF2|lockout|getLockout|oversized|WhatsNew|lastSeen" app/src/test app/src/androidTest`

Найдены только старые тесты сериализации/генерации `MEMBER_REMOVED` и ACK tests. Не найдено targeted-тестов на:

- unauthorized `DELETE:` из чужого контакта/чата;
- `DELETE:` для outgoing message;
- unauthorized `MEMBER_REMOVED` в группе с `createdBy`;
- legacy `MEMBER_REMOVED` behavior;
- `FileSaver.sanitizeFileName` path traversal cases;
- AppLock PBKDF2 migration, failed attempt counter, lockout;
- OutboxDrain oversized BLOB repair;
- `WhatsNew` first rollout behavior for existing account vs fresh install.

Риск: исправления выглядят разумно, но легко сломать их повторно без красного теста.

Рекомендация: добавить узкие unit-тесты на каждый security fix. В первую очередь `DELETE`, `MEMBER_REMOVED`, ACK ignore, FileSaver sanitize, AppLock lockout.

### S4. App lock не relock-ает приложение после background/resume

Severity: Medium / privacy  
Файлы:

- `app/src/main/java/ru/cheburmail/app/MainActivity.kt:72`
- `app/src/main/java/ru/cheburmail/app/ui/lock/LockScreen.kt:170`
- `app/src/main/java/ru/cheburmail/app/security/AppLockManager.kt:60`

`isLocked` выставляется только при создании `MainActivity`:

```kotlin
var isLocked by remember { mutableStateOf(appLockManager.isLockEnabled) }
```

После успешного unlock `isLocked=false`. При уходе приложения в background и возврате в тот же process повторной блокировки не видно. Для приватного мессенджера пользователь обычно ожидает relock after background/timeout.

Также `AppLockManager.getLockoutRemainingMs()` есть, но UI показывает только `Неверный PIN`; пользователь не видит, что наступил lockout и сколько ждать.

Рекомендация:

- добавить lifecycle observer: на `ON_STOP` запоминать время, на `ON_START` relock если PIN включен и timeout истек;
- показывать lockout countdown в `LockScreen`;
- добавить тесты на lockout state machine в `AppLockManager`.

### S5. `release.sh FORCE=1` может удалить stable tag

Severity: Medium / release integrity  
Файл: `release.sh:43`

Скрипт теперь по умолчанию считает tags immutable, это правильное изменение. Но `FORCE=1` не ограничен debug-режимом:

```bash
if [[ "${FORCE:-0}" == "1" ]]; then
    gh release delete "$TAG" --yes --cleanup-tag
fi
```

Комментарий говорит "только для debug", но код позволяет `FORCE=1 ./release.sh release` удалить stable release/tag.

Рекомендация: разрешить force-delete только для `MODE=debug`, а для stable требовать новый `versionCode`. Если нужен аварийный override для stable, сделать отдельный флаг с явным confirm, например `FORCE_STABLE_RETAG=I_UNDERSTAND`.

### S6. Native dependency не готова к 16 KB page alignment

Severity: Low/Medium / compatibility  
Источник: `lintDebug`

Lint предупреждает:

- `lazysodium-android:5.1.0`
- `arm64-v8a/libsodium.so is not 16 KB aligned`

Это не текущая runtime-уязвимость, но риск совместимости с устройствами, требующими 16 KB page size.

Рекомендация: проверить, есть ли Java-17-compatible lazysodium/sqlcipher build с 16 KB alignment, либо планировать отдельную проверку на Android 15+/16 KB page devices. Обновление lazysodium до 5.2.0 ранее конфликтовало с Java 17 unit tests, значит нужен аккуратный dependency strategy.

### L1. `GlobalScope.launch` остался в `WhatsNew` dismiss

Severity: Low  
Файл: `app/src/main/java/ru/cheburmail/app/ui/chat/ChatListScreen.kt:181`

Сохранение `lastSeenWhatsNewVersion` запускается через `GlobalScope.launch`. Компилятор предупреждает о delicate API. Для короткой DataStore-записи риск невысокий, но это не lifecycle-aware.

Рекомендация: использовать `rememberCoroutineScope()` для dismiss handler или вынести write в ViewModel.

### L2. Lint warning `StaticFieldLeak` по `AppSettings`

Severity: Low  
Файл: `app/src/main/java/ru/cheburmail/app/storage/AppSettings.kt:47`

Lint видит singleton с `Context`, но `getInstance` сохраняет `context.applicationContext`, поэтому это похоже на false positive. Можно оставить как есть или переписать поле на `applicationContext` явно в конструкторе, чтобы warning ушел.

## Статус исправлений после round-2 фикса (2026-05-08, Claude)

215/215 unit-тестов проходят, lint clean, blocker'ов нет.

| ID | Источник | Статус | Где исправлено |
| --- | --- | --- | --- |
| B1 | v2 audit | ✅ Fixed | `OnboardingScreen.kt` — убран `remember{ ... }`, reset вызывается в `AppNavigation.onAddAccount` ДО navigate |
| S2 | v2 audit | ✅ Removed | `DeliveryReceiptHandler.kt` + `DeliveryReceiptTest.kt` удалены; `ReceiveWorker` теперь не импортирует |
| S3 | v2 audit | ✅ Tests added | +35 регрессионных тестов: FileSaverSanitizeTest (10), AppLockManagerTest (16), ControlMessageHandlerTest (+5 для H2), ReceiveWorkerTest (+4 для H1) |
| S5 | v2 audit | ✅ Fixed | `release.sh` — `FORCE=1` только для `MODE=debug`; для stable требуется `FORCE_STABLE_RETAG=I_UNDERSTAND` |
| S1 | v2 audit | ⚠️ Documented compromise | Legacy `MEMBER_REMOVED` self-remove оставлен с тестом подтверждающим поведение; non-self-target в legacy теперь явно отвергается |
| S4 | v2 audit | ⏳ Tier 3 | App-lock relock-on-background — отложено |
| S6 | v2 audit | ⏳ Tier 4 | 16KB page alignment lazysodium — defer до Java 21 toolchain |
| L1 | v2 audit | ✅ Fixed | `GlobalScope.launch` → `rememberCoroutineScope()` в `ChatListScreen` |
| L2 | v2 audit | ⏭️ Suppress | StaticFieldLeak в AppSettings — false positive (uses applicationContext) |
| A1 | self-review | ✅ Fixed | `ReceiveWorker` принимает `primaryEmail`, использует `selfChatEmail()` для `directChatId` — chatId стабилен независимо от alias-аккаунта |
| A2 | self-review | ✅ Fixed | 12 точек `getByEmail` → `getByEmailOrAlias` в lookup-путях; collision-check'и оставлены на `getByEmail` намеренно |
| A3 | self-review | ✅ Fixed | `ImapClient` — stuck-fetch detection 90s + `Thread.interrupt()` зависшего holder |
| A4 | self-review | ⏳ Tier 3 | SyncFactory blocking DataStore — оставлено |
| A5 | self-review | ⏳ Tier 3 | Index на `contacts.public_key` — низкий приоритет на малом N |
| A6 | self-review | ⏳ Tier 3 | Snackbar в `ChatScreen` — low UX-разрыв |
| A7 | self-review | ⏳ Tier 3 | SmtpHealthTracker persist — намеренно сбрасывается, low |
| A8 | self-review | ✅ Fixed | `MultiAccountManager.events` replay=0 → replay=1 — событие не теряется если подписчик подключился позже |

**Регрессионные тесты по security hardening (S3):**

```
ru.cheburmail.app.media.FileSaverSanitizeTest          — 10 tests, path injection / control bytes / length cap
ru.cheburmail.app.security.AppLockManagerTest          — 16 tests, PBKDF2+salt / throttling / legacy migration
ru.cheburmail.app.group.ControlMessageHandlerTest      — 18 tests (был 13, +5 для H2 MEMBER_REMOVED auth)
ru.cheburmail.app.transport.ReceiveWorkerTest          — 9 tests (был 5, +4 для H1 DELETE auth)
```

**Релизная готовность 0.4.4:** lintDebug clean, testDebugUnitTest 215/215. Tier 1 + Tier 2 закрыты, можно бампать версию и публиковать debug-канал.

## Дополнения от self-review (Claude, 2026-05-08)

В дополнение к находкам v2-аудита — список собственных observations из
ревью моего же diff после фиксов и из событий 2026-05-08 (включая
runtime-проблемы на основном телефоне Eugene).

### A1. ChatId не использует canonical primary email

Severity: High  
Файлы:

- `app/src/main/java/ru/cheburmail/app/messaging/ChatIdGenerator.kt:23`
- `app/src/main/java/ru/cheburmail/app/ui/navigation/AppNavigation.kt:396`
- `app/src/main/java/ru/cheburmail/app/ui/chat/ChatViewModel.kt:175`
- `app/src/main/java/ru/cheburmail/app/transport/ReceiveWorker.kt:200, 332`

`ChatIdGenerator.directChatId(myEmail, contact.email)` принимает текущий
активный email юзера. Если у юзера несколько аккаунтов и он переключает
активный (Yandex → Mail.ru), `directChatId` вычислит **другой** идентификатор
и старая переписка станет «потерянной» (появится дубликат-чат с тем же
контактом). Также при отправке через fallback-аккаунт chatId-сторона
получателя совпадёт, а отправителя нет — может разойтись.

Рекомендация: ChatIdGenerator должен принимать **canonical primary email**
(первый сохранённый аккаунт) для обеих сторон. Контакт уже хранит
`contact.email` (primary) — правильно. Для self-стороны нужно резолвить
primary через `AccountRepository.getAll().first().firstOrNull()?.email` и
кешировать в `ChatViewModel`.

### A2. 14 мест `contactDao.getByEmail` НЕ заменены на `getByEmailOrAlias`

Severity: Medium → High при реальном использовании алиасов  
Файлы (по результатам `grep -n "contactDao\.getByEmail\b"`):

- `app/src/main/java/ru/cheburmail/app/ui/contacts/ContactsViewModel.kt:93,238`
- `app/src/main/java/ru/cheburmail/app/ui/chat/ChatViewModel.kt:471,602,971`
- `app/src/main/java/ru/cheburmail/app/transport/MessageRepository.kt:75`
- `app/src/main/java/ru/cheburmail/app/group/GroupMessageSender.kt:141`
- `app/src/main/java/ru/cheburmail/app/group/GroupManager.kt:345`
- `app/src/main/java/ru/cheburmail/app/group/ControlMessageHandler.kt:191,242,258,353`
- `app/src/main/java/ru/cheburmail/app/messaging/DeliveryReceiptSender.kt:48`
- `app/src/main/java/ru/cheburmail/app/transport/SendWorker.kt:80`
- `app/src/main/java/ru/cheburmail/app/messaging/KeyExchangeManager.kt:220` (intentional collision check)

Эти места ищут контакт по email из From/recipient. Когда юзер отправляет
с alias-аккаунта, на стороне получателя `getByEmail(senderAlias)` вернёт
null, и сообщение будет отброшено как «unknown sender».

Рекомендация: pass через все 14 точек, заменить на `getByEmailOrAlias`
там, где ищется **входящий контакт** (recipient/sender lookup). Где
проверяется уникальность email при создании контакта (как
`KeyExchangeManager:220` collision check, `ContactsViewModel:93`
duplicate-check) — оставить `getByEmail`.

### A3. ImapClient deadlock на висящем fetch (runtime)

Severity: Medium  
Воспроизведено: 2026-05-08 на основном телефоне Eugene (0.4.3 debug,
mr_respect@bk.ru).

Один `fetchMessages` зависает на IO-операции (без жёсткого timeout на
самом сокете), внутренний mutex остаётся захваченным, все последующие
вызовы `fetchMessages` логируют `another fetch is in progress (30s
timeout)` и отбрасываются. Юзер видит «приложение не реагирует на новые
письма», единственный workaround — force-stop.

Рекомендация: hard-timeout на socket-операции (Read/Connect) +
interrupt висящего fetch при превышении wall-clock budget (например
60 сек). Текущий "30s timeout" комментарий вводит в заблуждение —
реально mutex не отпускается совсем.

### A4. Blocking DataStore-read в WorkManager-runtime

Severity: Medium  
Файл: `app/src/main/java/ru/cheburmail/app/sync/SyncFactory.kt:131`

```kotlin
val autoFallback = runCatching {
    kotlinx.coroutines.runBlocking {
        AppSettings.getInstance(context).autoFallbackEnabled.first()
    }
}.getOrDefault(true)
```

`runBlocking` на DataStore из worker thread — антипаттерн. На медленном
устройстве может тормозить worker. Допустимо для одного boolean, но
лучше прокинуть `Flow<Boolean>` в `SendWorker` и читать из coroutine
scope.

### A5. Нет индекса на `contacts.public_key`

Severity: Low  
Файл: `app/src/main/java/ru/cheburmail/app/db/dao/ContactDao.kt`

`getByPublicKey` (новый запрос для multi-email matching) делает
full-scan `contacts WHERE public_key = ?`. На N<100 контактов
приемлемо, на больших деградирует O(N).

Рекомендация: добавить `@Index(value = ["public_key"])` в
`ContactEntity`, миграция v9→v10.

### A6. Snackbar `FallbackUsed` показывается только в `ChatListScreen`

Severity: Low  
Файл: `app/src/main/java/ru/cheburmail/app/ui/chat/ChatListScreen.kt:103`

Если юзер сидит в открытом чате и отправил сообщение через fallback, он
не увидит snackbar (он живёт в ChatList). Минорный UX-разрыв.

Рекомендация: продублировать `sendEvents`-collector в `ChatScreen` или
поднять SnackbarHost на root-уровень NavHost.

### A7. `SmtpHealthTracker` теряется при рестарте процесса

Severity: Low (намеренно)  
Файл: `app/src/main/java/ru/cheburmail/app/account/SmtpHealthTracker.kt`

In-memory tracker сбрасывается при OOM-kill (агрессивный MIUI). Юзер
снова получит retry на упавший SMTP перед карантином. Намеренно
(TSPU мог переключиться), но на устройствах с агрессивным kill —
постоянное «мигание». Stretch goal: persist последние fail в
SharedPreferences.

### A8. `MultiAccountManager.events` replay=0

Severity: Low  
Файл: `app/src/main/java/ru/cheburmail/app/account/MultiAccountManager.kt:38`

`MutableSharedFlow(replay=0, extraBufferCapacity=8, DROP_OLDEST)` —
если в момент эмита нет подписчика (юзер на каком-то экране без
ChatListScreen), событие теряется. Snackbar для FallbackUsed не
покажется потом.

Рекомендация: replay=1 + сохранить «последний показанный» в
collector'е, чтобы не дублировать на recomposition.

## План исправлений

По приоритету:

**Tier 1 — release blockers (must-fix перед stable promote):**

1. **B1** OnboardingScreen lint — 5 минут. `viewModel.reset()` вынести
   из `remember{}` в `onAddAccount` callback в `AppNavigation`.
2. **A1** ChatId canonical — 30-60 минут. Принимать primary email через
   `AccountRepository.getAll().first().first().email`, фоллбэк на
   текущий activeEmail если accounts пуст.
3. **A2** 14× `getByEmail` → `getByEmailOrAlias`. ~30 минут на
   mechanical replacement, с разбором collision-chek-точек где замена
   не нужна.

**Tier 2 — quality-of-release:**

4. **A3** ImapClient hard-timeout + interrupt висящего fetch.
5. **S2** Удалить subject-only ACK handler + тесты.
6. **S3** Regression-тесты: DELETE-auth, MEMBER_REMOVED admin/legacy,
   FileSaver sanitize, AppLock lockout.
7. **S5** `release.sh FORCE=1` — разрешить только при `MODE=debug`.
8. **A8** MultiAccountManager.events → replay=1.

**Tier 3 — польза но не блокер:**

9. **S4** AppLock relock-on-background + lockout countdown в UI.
10. **A4** SyncFactory blocking DataStore → Flow прокинуть.
11. **A5** Index на `contacts.public_key`, миграция v9→v10.
12. **A6** Snackbar в ChatScreen.
13. **L1** `GlobalScope.launch` → `rememberCoroutineScope()`.
14. **A7** SmtpHealthTracker персистенс.

**Tier 4 — паркуем (либо не наш код, либо long-term):**

15. **S1** Legacy MEMBER_REMOVED без admin — оставляем как
    «documented compromise», ждём миграции старых групп.
16. **S6** 16KB page alignment lazysodium — требует Java 21
    toolchain или выпуска lazysodium-android 5.1.x с alignment.
    Defer до общего toolchain-апгрейда.
17. **L2** StaticFieldLeak в AppSettings — false positive, оставляем.

## Что сделано хорошо после фиксов

- `ReceiveWorker.kt:222-255`: remote delete теперь авторизован по чату, направлению и sender contact id.
- `ControlMessageHandler.kt:199-218`: group member removal для групп с admin metadata требует admin.
- `FileSaver.kt`: filename sanitization, canonical path guard, `@RequiresApi(Q)` для MediaStore ветки.
- `AppLockManager.kt`: PBKDF2WithHmacSHA256, salt, legacy migration, failed attempt counter, lockout.
- `AppSettings.kt:111-116`: screenshot protection default `true`.
- `OutboxDrainWorker.kt`: oversized queue entries больше не удаляются молча, message status ставится `FAILED`.
- `SendWorker.kt`: fallback health tracking использует фактический failed account.
- `DbCipherMigrator.kt:54-67`: plaintext backup cleanup выполняется до migrated-check.
- `AndroidManifest.xml`: `allowBackup=false`, `fullBackupContent`, `dataExtractionRules`, `networkSecurityConfig`, internal services/receivers не экспортированы.
- `network_security_config.xml`: cleartext traffic запрещен.
- Room schemas теперь отслеживаются: 9 файлов в `app/schemas`.
- `git ls-files cheburmail-release.jks local.properties bot/subscribers.json` пустой: keystore/local properties/bot subscribers не попали в git.

## Релизная готовность

На текущем HEAD релизить рано из-за `lintDebug` blocker.

Минимальный pre-release checklist:

1. Исправить `OnboardingScreen.kt:22` без возврата старого `isComplete=true` бага.
2. Прогнать:
   ```bash
   env ANDROID_HOME=/home/q/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug testDebugUnitTest lintDebug --no-daemon
   ```
3. Добавить хотя бы smoke regression tests на `DELETE` и `MEMBER_REMOVED`.
4. Ограничить `FORCE=1` в `release.sh` только debug-релизами.

## Рекомендуемый порядок исправлений

1. Починить `OnboardingScreen` lint blocker.
2. Добавить regression-тесты на H1/H2 fixes.
3. Удалить или переписать subject-only ACK handler/tests.
4. Ограничить legacy `MEMBER_REMOVED` fallback или добавить user confirmation.
5. Добавить app-lock relock-on-background и lockout countdown.
6. Защитить stable release tags от `FORCE=1`.
7. Запланировать dependency/16 KB alignment работу отдельно от Java 17 test compatibility.

## Примечания по рабочему дереву

Перед созданием этого отчета рабочее дерево было чистым, кроме уже существующего untracked отчета `PROJECT_REVIEW_SECURITY_AUDIT_2026-05-07.md`.

