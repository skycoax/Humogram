# Защита ссылок (link guard) — заметки для владельца

## Что делает

- Сайт не из доверенного списка → ссылка в чате красная, открытие через два
  подтверждения. Сайт из чёрного списка → красная, предупреждение жёстче и
  трёхсекундная пауза на втором шаге. Доверенный сайт — как в обычном Telegram.
- `t.me`, `tg://`, упоминания, хэштеги, команды ботов, телефоны и почта не
  затрагиваются.
- Переключатель: Настройки → Humogram → «Защита ссылок» (по умолчанию включено).
  Это просто вкл/выкл; отдельной страницы с версией списка и кнопкой
  «Обновить сейчас» больше нет — список обновляется сам (см. ниже).

## Откуда берётся список

1. Встроенный в приложение: `assets/link-default-allowlist.json` (источник —
   `TelegramAPI/shared/data/`, рядом `…NOTES.md` с обоснованием каждой записи)
   плюс домены банков и госсервисов из `uz-brand-allowlist.json`.
2. Ваш список с сервера `https://lists.skycoax.uz/v1/lists.json`. Приложение
   скачивает его при запуске, при возврате в приложение и раз в ~5 минут, пока
   открыто. Проверка ссылки идёт на телефоне; адреса ссылок никуда не уходят.
3. Если сервер недоступен, действует последний скачанный список, а без него —
   встроенный. Неизвестное остаётся подозрительным.

Правила: чёрный список сильнее всего; «исключения» сильнее белого; ваша точная
запись в белом списке снимает встроенное исключение (например, `docs.google.com`),
а запись родителя (`google.com`) — нет. Подделку под бренд (буквы-двойники)
белый список не отменяет.

## Сервер

Пакет: `TelegramAPI/deploy/linkguard-lists/` (инструкция — `README.md` там же).
Установка в два шага, как у tg-relay:

    # Git Bash на этом ПК
    cd /c/Users/user/Desktop/TelegramAPI/deploy/linkguard-lists
    SHIP_KEY=1 bash deploy.sh
    ssh -t skycoax-vps 'sudo bash /tmp/linkguard-bundle/install.sh'

Потом задать пароль командой, которую напечатает `install.sh`. Сертификат
выпускается через webroot и чужие файлы nginx не трогает.

Локально посмотреть страницу: `bash run-local.sh` (временный ключ и пароль).

## Ключи подписи

Приложение принимает только список, подписанный одним из двух ключей
(`jac_linkguard_pubkeys`). Приватные ключи лежат ВНЕ репозиториев:
папка `.humogram` в профиле пользователя Windows: `linkguard-signing-key.pem` (рабочий, `lg1`) и
`linkguard-backup-key.pem` (запасной, `lg2`, на сервер не отправляется).
Сделайте их резервную копию. Потеря обоих = списки нельзя обновить без нового
релиза приложения.

## Что не закрыто

- Сервер ещё не развёрнут; `install.sh` ни разу не запускался на настоящей машине.
- На телефоне под вошедшим аккаунтом не проверено (красный цвет, диалоги).
- Не перехватываются: переходы внутри встроенного браузера, встраиваемые
  плееры, мини-приложения ботов.
- `Tools/play_policy_check.py` остановит сборку release, если политика
  конфиденциальности, Data safety (`docs/play-console.md`) или текст «О
  приложении» перестанут упоминать скачивание списка с lists.skycoax.uz.
  Это единственное, что приложение получает с нашего сервера: у сканера
  вирусов «проверки в облаке» больше нет, он ничего не отправляет.

## Текст для политики конфиденциальности (en / uz / ru)

Text for the owner (do not edit files in this pass). Replace the last bullet of section 2 and add one paragraph to section 3 after the scanner paragraph, in all three languages; move 'Last updated'; mirror on hg.skycoax.uz/privacy in the same sitting; re-check Play Data safety and add a recorded line to docs/play-console.md (see AND-9).

EN section 2: "- **The app and scanner - the developer (Kamolov Muxammad).** The published build contacts one server run by the developer, lists.skycoax.uz, which serves the site list described in section 3. Apart from what any web request carries (your IP address and the app's build number), it receives nothing from you."
EN section 3: "**Link protection** downloads a list of trusted and blocked sites from the developer's server (lists.skycoax.uz) when the app starts, when you return to it, and about every five minutes while it is open. It is one public file, the same for everyone, and links are compared with it on your phone: no link, site address, message, account or device identifier is sent. As with any web request, the server sees your IP address and the app's build number. You can switch this off in Humogram settings -> Link protection; the app then stops requesting the list."

UZ section 2: "- **Ilova va skaner - dasturchi (Kamolov Muxammad).** Chiqarilgan versiya dasturchining bitta serveriga - saytlar ro'yxatini beradigan lists.skycoax.uz ga murojaat qiladi (3-bo'limga qarang). Bu server sizdan so'rovning texnik ma'lumotlaridan (IP-manzil, ilova versiyasi raqami) boshqa hech narsa olmaydi."
UZ section 3: "**Havolalar himoyasi** ishonchli va qora ro'yxatdagi saytlar ro'yxatini dasturchi serveridan (lists.skycoax.uz) yuklab oladi: ilova ishga tushganda, unga qaytganingizda va u ochiq turganida taxminan har besh daqiqada. Bu hamma uchun bir xil ochiq fayl. Havolalar shu ro'yxat bilan telefoningizning o'zida solishtiriladi - havolalar, sayt manzillari, xabarlar, hisob yoki qurilma identifikatorlari yuborilmaydi. Har qanday veb-so'rovdagi kabi, server IP-manzilingizni va ilova versiyasi raqamini ko'radi. Bu funksiyani Humogram sozlamalari -> Havolalar himoyasi bo'limida o'chirish mumkin; shunda ilova ro'yxatni so'ramaydi."

RU section 2: "- **Приложение и сканер - разработчик (Kamolov Muxammad).** Опубликованная версия обращается к одному серверу разработчика - lists.skycoax.uz, который отдаёт список сайтов (см. раздел 3). Кроме технических данных запроса (IP-адрес, номер сборки приложения), этот сервер ничего от вас не получает."
RU section 3: "**Защита ссылок** загружает список доверенных и заблокированных сайтов с сервера разработчика (lists.skycoax.uz) при запуске приложения, при возврате в него и примерно раз в пять минут, пока оно открыто. Это один общедоступный файл, одинаковый для всех; ссылки сверяются с ним на вашем телефоне: ни ссылки, ни адреса сайтов, ни сообщения, ни идентификаторы аккаунта или устройства не отправляются. Как при любом веб-запросе, сервер видит ваш IP-адрес и номер сборки приложения. Функцию можно отключить в настройках Humogram -> «Защита ссылок»; тогда приложение перестаёт запрашивать список."

Optional sentence, only after confirming on the deployed box that nginx's error log does not retain addresses for this vhost: EN "The server's request log for this file keeps the time and the build number, not the IP address." (uz: "So'rovlar jurnalida vaqt va versiya raqami yoziladi, IP-manzil yozilmaydi."; ru: "В журнале запросов сохраняются время и номер сборки, IP-адрес не сохраняется.").
