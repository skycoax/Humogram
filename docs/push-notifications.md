# Push-уведомления — заметки для владельца

## Что сейчас

Push выключен. Пока Humogram открыт или только что свёрнут, сообщения приходят
по обычному соединению с Telegram. Когда Android усыпляет приложение (через
минуту-другую в фоне, сразу после смахивания из «недавних»), **уведомления о
новых сообщениях не приходят**, пока приложение снова не откроют.

Причина: Telegram будит приложение через Firebase Cloud Messaging (FCM), а у
Humogram своего проекта Firebase нет. Файл `TMessagesProj_App/google-services.json`
в репозитории принадлежит Telegram (проект `tmessages2`, пакеты
`org.telegram.messenger*`). С ним сборка выпускала бы APK под нашим именем с
чужими идентификаторами, а Telegram всё равно не смог бы доставить push
нашему пакету. Поэтому сборка его игнорирует.

`Tools/play_policy_check.py` напоминает об этом при каждой релизной сборке
(предупреждение, не ошибка).

## Как это включить

Включается без правок кода. Сборка сама подключает Firebase, если
`google-services.json` наш, то есть в нём есть пакет `uz.humogram.app`
(см. `TMessagesProj_App/build.gradle`).

### 1. Проверьте платформу api_id

https://my.telegram.org → API development tools. Платформа приложения должна
быть **Android**. Только у Android-приложений есть поле для учётных данных FCM.
Платформу после создания поменять нельзя, а второе приложение на том же
аккаунте не создать. Если стоит не Android, остаётся завести api_id на другом
аккаунте Telegram, с платформой Android. Тогда уже вошедшим пользователям push
заработает только после повторного входа: push привязан к api_id, с которым
пользователь входил.

### 2. Проект Firebase

1. https://console.firebase.google.com → Add project → «Humogram». Google
   Analytics не включайте: он не нужен и добавил бы пункты в Data safety.
2. Add app → Android:
   - `uz.humogram.app` (релиз в Play),
   - `uz.humogram.app.beta` (debug-сборки),
   - `uz.humogram.app.web`, только если собираете standalone.

   SHA-1 для push не нужен. Каждый пакет, который собираете, должен быть в
   файле, иначе Gradle остановится с «No matching client found».
3. Project settings → General → скачайте `google-services.json` **после**
   добавления всех пакетов. Положите его вместо
   `TMessagesProj_App/google-services.json`. Файл не секретный (ключ в нём
   ограничен вашими приложениями), его можно коммитить.

### 3. Ключ для Telegram

Telegram отправляет push через FCM HTTP v1 от имени вашего проекта, поэтому
ему нужен JSON сервисного аккаунта. Telegram принимает его с июня 2024 года
(ответ разработчика TDLib: https://github.com/tdlib/td/issues/549).

1. Google Cloud Console (тот же проект) → IAM & Admin → Service accounts →
   Create: имя `telegram-push`, роль **Firebase Cloud Messaging API Admin**.
   Keys → Add key → JSON.
2. my.telegram.org → API development tools → ваше приложение → загрузите этот
   JSON в поле FCM. Если Telegram его не примет, возьмите ключ Admin SDK:
   Firebase → Project settings → Service accounts → Generate new private key.
   Права у него шире, поэтому сначала пробуйте отдельный аккаунт.
3. **Этот JSON — секрет.** С ним можно слать push всем пользователям Humogram.
   Не кладите его ни в один репозиторий. Храните рядом с ключами подписи вне
   проекта и удалите скачанную копию из «Загрузок».

Telegram не сообщает об ошибке в ключе: push просто не приходит.
Проверяйте на практике (шаг 5).

### 4. До релиза: политика и Play

`play_policy_check.py` не пропустит релиз, пока не сделано всё это:

1. В политику конфиденциальности (`docs/privacy-policy.md` и
   `D:\Web Development\WebHumogramPage\privacy\index.html`, три языка) добавьте
   тексты ниже.
2. Play Console → Data safety: «Device or other IDs». Собирается (collected),
   не передаётся (shared), цель: App functionality. Сверьтесь с
   https://firebase.google.com/docs/android/play-data-disclosure: там Google
   перечисляет, что собирает FCM.
3. В `docs/play-console.md` замените строку на `data.push_fcm = declared`.

**Раздел 2 (кто отвечает за данные)**

- UZ: `- **Bildirishnomalar — Google (Firebase Cloud Messaging).** Yangi xabar
  kelganda ilovani uyg‘otish uchun Telegram serverlari bildirishnomani Google
  push-xizmati orqali yuboradi. Uning mazmuni faqat telefoningiz va Telegram
  biladigan kalit bilan shifrlangan, shuning uchun Google faqat bildirishnoma
  yetkazilganini ko‘radi, matnini emas. Buning uchun Firebase ilovaga
  o‘rnatish identifikatori va push-token beradi; ilova tokenni Telegram’ga
  uzatadi.`
- RU: `- **Уведомления — Google (Firebase Cloud Messaging).** Чтобы разбудить
  приложение при новом сообщении, серверы Telegram отправляют уведомление
  через push-сервис Google. Его содержимое зашифровано ключом, который знают
  только ваш телефон и Telegram, поэтому Google видит лишь факт доставки, но
  не текст. Для этого Firebase выдаёт приложению идентификатор установки и
  push-токен; приложение передаёт токен в Telegram.`
- EN: `- **Notifications — Google (Firebase Cloud Messaging).** To wake the
  app when a message arrives, Telegram's servers send the notification
  through Google's push service. Its content is encrypted with a key known
  only to your phone and Telegram, so Google sees that a notification was
  delivered, not what it says. For this, Firebase gives the app an
  installation ID and a push token, and the app passes the token to Telegram.`

**Раздел 3, строка таблицы**

- UZ: `| Push-token va Firebase o‘rnatish identifikatori | Google (Firebase) → Telegram | Doim, bildirishnomalar uchun |`
- RU: `| Push-токен и идентификатор установки Firebase | Google (Firebase) → Telegram | Всегда, для уведомлений |`
- EN: `| Push token and Firebase installation ID | Google (Firebase) → Telegram | Always, for notifications |`

**Раздел 5 (передача третьим лицам)**: допишите к фразе про Telegram и Google
«…и, для доставки уведомлений, push-токен — **Google Firebase Cloud Messaging**»
(на каждом из трёх языков).

### 5. Проверка

1. Соберите и поставьте приложение, войдите в аккаунт.
2. Смахните Humogram из «недавних».
3. Напишите на этот аккаунт с другого. Уведомление должно прийти за
   секунды.
4. Если не пришло: `adb logcat | grep -i -E "fcm|firebase"`. Сообщение «Default
   FirebaseApp is not initialized» значит, что плагин не подключился (в файле
   нет `uz.humogram.app`). Если токен получен, а push не приходит, проблема
   на стороне Telegram: не тот JSON, не та платформа или пользователь вошёл
   с другим api_id.

Уже установленные копии регистрируют токен сами при первом запуске новой
версии. Повторный вход не нужен, если api_id тот же.

## Что видит Google

То же, что у официального Telegram. Приложение передаёт Telegram случайный
256-байтный ключ (`SharedConfig.pushAuthKey`, в `account.registerDevice`), и
Telegram шифрует им каждое уведомление. Google видит токен устройства, время
и размер, но не текст и не отправителя. Ничего из сканера или защиты ссылок
через push не идёт.

## Почему не без Google

- Постоянное фоновое соединение требует foreground-сервиса с вечным значком в
  шторке. На Android 15 тип `dataSync` ограничен шестью часами в сутки, а Play
  требует обосновать такой сервис. Для мессенджера Google это не одобряет.
- UnifiedPush / Web push (типы 4 и 10 в Telegram API) работают без Firebase,
  но нужен свой шлюз-сервер. Через него шли бы (зашифрованные) уведомления
  всех пользователей, и его надо держать живым 24/7. Обычный пользователь
  ещё и должен поставить отдельное приложение-дистрибьютор. Это вариант для
  второй сборки «без Google», не для Play.
