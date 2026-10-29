# Humogram — Maxfiylik siyosati / Политика конфиденциальности / Privacy Policy

**Oxirgi yangilanish / Последнее обновление / Last updated: 2026-10-29**

<!--
  This is the USER-FACING policy and the reviewed text behind the published
  page at https://hg.skycoax.uz/privacy (the address in jac_privacy_url and in
  the Play listing, built from privacy/index.html in the landing site source).
  This file and that page must say the same thing: change both in the same
  sitting and move "last updated" in both.

  What it describes, and where that lives in the code:

  - The scanner (files and links in chats, and the virus scanner for installed
    apps: DeviceAppCollector / DeviceScanner) runs ENTIRELY ON THE DEVICE and
    transmits nothing. Installed apps are seen through the manifest's targeted
    <queries>, not QUERY_ALL_PACKAGES. Results live under getNoBackupFilesDir()
    (app-private, excluded from backups).
  - Link protection (LinkGuard) downloads one public, signed file,
    jac_linkguard_lists_url = https://lists.skycoax.uz/v1/lists.json: a few
    seconds after start-up, on return to the foreground, about every five
    minutes in the foreground, and on opening a warned link (throttled). The
    request carries User-Agent "Humogram/<versionCode>" and, when a list is
    held, its ETag -- no link, host, message, account or device identifier.
    Switched off, it fetches nothing at all. The server keeps no IP address
    for list requests: the access log for the file is lg_anon and its error
    log is /dev/null (linkguard nginx vhost); the same line minus time and
    size (lg_stat) goes over loopback UDP to lists.py, which keeps only
    per-hour counts by result and build number (Collector, since 2026-10-29).
    The server is a VPS in Tashkent,
    Uzbekistan (AIRNET LLC). Since 2026-10-27 the same file carries the
    device scanner's app lists (AppLists), so it is fetched while link
    protection OR the launch scan is on (LinkGuard.fetchWanted).
  - "Send for review" (ReviewRequest) POSTs, only on a tap and a confirm, a
    site's host or an app's package + signing-cert SHA-256 + label + version
    to jac_review_requests_url = https://lists.skycoax.uz/v1/requests. nginx
    blanks X-Real-IP / X-Forwarded-For and logs lg_anon; lists.py stores the
    item, a count per day and times in its review queue, nothing about the
    sender; once decided the row stays as a record with the decision and is
    deleted a year after it was last sent (REQUEST_HISTORY_DAYS). Data
    safety: docs/play-console.md,
    data.review_requests.

  If anything else ever leaves the phone for a server of ours, rewrite the
  "who is responsible / what is processed / sharing / retention" sections here
  and on the site, the Play Data safety form and docs/play-console.md together.
  Tools/play_policy_check.py holds the release build to docs/play-console.md.

  tools/preflight.mjs fails the build if the <CONTACT-EMAIL>, <OPERATOR-NAME>
  or <SOURCE-URL> placeholders ever come back.
-->

---

<a name="uz"></a>

## O‘zbekcha

### 1. Kirish

Humogram — Telegram platformasi asosida ishlaydigan messenjer. Unga
**qurilmangizda** ishlaydigan himoya qo‘shilgan: telefoningizdagi ilovalarni
tekshiradigan virus skaneri, chatlardagi fayl va havolalarni tekshirish hamda
havolalar himoyasi.

- **Skanerimiz o‘zicha hech narsa yig‘maydi va yubormaydi.** Ilovalar,
  fayllar, ularning raqamli izlari (SHA-256) va havolalar faqat telefoningizda
  tekshiriladi.
- **Havolalar himoyasi** dasturchi serveridan (lists.skycoax.uz) ishonchli va
  bloklangan saytlar hamda ilovalarning bitta ochiq ro‘yxatini yuklab oladi;
  havolalaringiz va ilovalaringiz haqida hech narsa yuborilmaydi.
- **«Tekshiruvga yuborish»** — yagona istisno, va faqat undan foydalansangiz:
  havola ogohlantirishi ostida yoki skaner belgilagan ilovada uni bosib
  tasdiqlasangiz, dasturchiga tekshirish uchun shu saytning nomi — yoki
  ilova paketining nomi, imzosining barmoq izi, nomi va versiyasi — yuboriladi.
- **Messenjer sifatida** ilova Telegram’dan foydalanadi, shuning uchun aloqa
  ma’lumotlaringiz Telegram serverlarida ishlanadi.

### 2. Ma’lumotlaringiz uchun kim javobgar

- **Xabar almashish — Telegram Messenger (Telegram LLC / FZ-LLC).** Hisob va
  suhbat ma’lumotlari Telegram tizimi orqali o‘tadi va o‘sha yerda saqlanadi:
  <https://telegram.org/privacy>.
- **Xaridlar — Google (Google Play).** Obuna va xaridlar Google Play orqali.
- **Ilova, skaner va saytlar ro‘yxati — dasturchi (Kamolov Muxammad).**
  Dasturchi bitta server yuritadi (lists.skycoax.uz va hg.skycoax.uz). U
  havolalar himoyasi va virus skaneri uchun saytlar va ilovalarning ochiq
  ro‘yxatini (3-bo‘limga qarang) va — bulut paroli eslatmasida «Batafsil»ni
  bossangiz — tushuntiruvchi videoni beradi. U sizdan har qanday veb-so‘rov
  bilan keladigan texnik ma’lumotlardan (IP-manzil, ilova versiyasi raqami,
  so‘rov vaqti) va «Tekshiruvga yuborish» orqali o‘zingiz yuborgan narsadan
  (3-bo‘limga qarang) boshqa hech narsa olmaydi.
  Server Toshkentdagi (O‘zbekiston) VPS’da joylashgan, hosting provayderi —
  AIRNET LLC. Shuning uchun ilovadan boshqa davlatda foydalansangiz ham, bu
  so‘rov ma’lumotlari O‘zbekistonda ishlanadi.

### 3. Qanday ma’lumotlar ishlanadi

Humogram Telegram mijozi bo‘lgani uchun quyidagilar **Telegram serverlariga**
yuboriladi (rasmiy Telegram ilovasidagidek):

| Ma’lumot | Kim ishlaydi | Qachon |
|---|---|---|
| Telefon raqami | Telegram | Hisobga kirish (majburiy) |
| Ism, foydalanuvchi nomi, bio, profil rasmi | Telegram | Profil |
| Ikki bosqichli himoya uchun e-pochta | Telegram | Agar o‘rnatsangiz |
| Kontaktlar | Telegram | Kontakt sinxronizatsiyasi yoqilsa |
| Oddiy chatlardagi xabar, media, hujjat, ovozli xabar, qo‘ng‘iroq | Telegram | Xabar almashganda |
| Joylashuv | Telegram | Agar ulashsangiz |
| Xaridlar (Premium, Stars, sovg‘alar) | Google Play → Telegram | Xarid qilganda |

**Maxfiy chatlar** uchdan-uchgacha shifrlangan va faqat qurilmalaringizda qoladi.

Quyida tavsiflangan tekshiruvlar telefoningizning o‘zida bajariladi. Tashqariga
faqat ikki narsa chiqadi: «Havolalar himoyasi» bandida tavsiflangan ro‘yxatni
yuklab olish so‘rovi va — faqat undan foydalansangiz — shu bo‘lim oxirida
tavsiflangan «Tekshiruvga yuborish».

#### Chatlardagi fayllar va havolalar

Sizga kelgan fayllar va xabarlardagi havolalar ilova ichidagi qoidalar va
ma’lumotlar bazasi asosida telefoningizda tekshiriladi. Buning uchun fayllar,
ularning raqamli izi (SHA-256), fayl nomlari, havolalar va xabar matni hech
qayerga yuborilmaydi.

#### Virus skaneri (telefoningizdagi ilovalar)

Virus skaneri telefoningizga o‘rnatilgan ilovalarni ma’lum zararli dasturlarga
hamda bank troyanlari va SMS o‘g‘irlovchi dasturlarga xos belgilarga tekshiradi.
U butunlay telefoningizda ishlaydi: tekshiruvni o‘zingiz boshlaysiz
(Sozlamalar → Qurilma xavfsizligi) yoki, ruxsat bersangiz, u Humogram har safar
ishga tushganda bajariladi.

Skaner faqat telefonning o‘zida quyidagilarni o‘qiydi:

- Android Humogram’ga ko‘rishga ruxsat beradigan ilovalar: ilovalar menyusida
  belgisi bor ilovalar; maxsus imkoniyatlar xizmati, bildirishnomalarga kirish
  yoki SMS funksiyalarini taklif qiladigan ilovalar; shuningdek telefondagi
  ilova do‘konlari va o‘rnatuvchilar. Humogram ularni Android’ning paketlar
  ko‘rinuvchanligi so‘rovlari (package visibility) orqali topadi va barcha
  ilovalarni ko‘rish ruxsatidan (QUERY_ALL_PACKAGES) foydalanmaydi;
- har bir shunday ilovaning paket nomi, nomi va versiyasi; uni qaysi ilova yoki
  do‘kon o‘rnatgani; unga SMS yoki qo‘ng‘iroq ruxsatlari berilganmi; u maxsus
  imkoniyatlar xizmati, bildirishnomalarga kirish huquqiga ega ilova, qurilma
  administratori yoki standart SMS ilovasi sifatida faolmi va boshqa ilovalar
  ustidan ko‘rsatila oladimi; belgisi menyudan yashirilganmi; imzo
  sertifikatining raqamli izi;
- ilova do‘konidan tashqarida o‘rnatilgan ilovalar uchun — o‘rnatish faylining
  SHA-256, SHA-1 va MD5 raqamli izlari;
- Humogram’ning o‘z yuklanmalar va kesh papkalaridagi APK fayllar (ilova
  o‘rnatish fayllari).

Bularning barchasi ilova ichida keladigan qoidalar va ma’lum zararli dasturlar
raqamli izlari ro‘yxati bilan, shuningdek dasturchining ilovalar ro‘yxati —
tasdiqlangan ilovalar (paket nomi va nashriyotchining imzo sertifikati) va
ma’lum zararli dasturlar — bilan solishtiriladi; bu ro‘yxat saytlar ro‘yxati
bilan bir xil ochiq faylda keladi («Havolalar himoyasi»ga qarang). **Skaner
o‘qigan yoki topgan hech narsa hech qayerga yuborilmaydi** — na dasturchiga,
na Telegram’ga, na boshqa birovga: skaner tekshiruv uchun internetdan
foydalanmaydi. Yagona istisno — o‘zingiz tekshiruvga yuborgan ilova (quyida
«Tekshiruvga yuborish»ga qarang). Natijalar faqat telefoningizda,
Humogram’ning boshqa ilovalar ko‘ra olmaydigan ichki xotirasida saqlanadi. Bu
xotira zaxira nusxalarga kirmaydi va ilovani o‘chirsangiz yoki uning
ma’lumotlarini tozalasangiz, o‘chib ketadi.

#### Qurilma tekshiruvi va himoya darajasi

Sozlamalar → Humogram sahifasida ilova telefonning o‘zida quyidagilarni
tekshiradi: qurilmada root belgilari bormi, USB
orqali nosozliklarni tuzatish yoqilganmi va so‘nggi xavfsizlik yangilanishi
qachon bo‘lgan. Buning uchun bir nechta tizim sozlamasi va fayllar mavjudligi
o‘qiladi. Akkauntingizda bulut paroli (2FA) borligi Telegram’dan so‘raladi —
rasmiy ilovaning Maxfiylik sozlamalari ham xuddi shunday so‘raydi. Natijalar va
ulardan hisoblangan himoya darajasi (foiz) faqat telefonda ko‘rsatiladi va hech
qayerga yuborilmaydi.

#### Havolalar himoyasi

Havolalar himoyasi yoqilgan bo‘lsa (standart holatda yoqilgan), chatlardagi
ishonchli saytlar ro‘yxatida yo‘q saytlarga olib boradigan havolalar qizil
rangda ko‘rsatiladi va faqat ikki marta tasdiqlaganingizdan keyin ochiladi;
qora ro‘yxatdagi saytlar uchun kuchliroq ogohlantirish chiqadi. Havolalar
telefoningizda tekshiriladi.

Ro‘yxatlar eskirib qolmasligi uchun ilova dasturchi serveridan bitta ochiq
faylni — <https://lists.skycoax.uz/v1/lists.json> — yuklab oladi: ilova ishga
tushganda, unga qaytganingizda, ilova ochiq turganida taxminan har besh
daqiqada, shuningdek ogohlantirish chiqadigan havolani ochganingizda (agar
ro‘yxat so‘nggi bir necha daqiqada tekshirilmagan bo‘lsa). Bu fayl hamma uchun
bir xil. So‘rovda havola, sayt manzili, xabar, hisob yoki qurilma
identifikatori bo‘lmaydi — faqat har qanday veb-so‘rovda bo‘ladigan narsalar:
IP-manzilingiz va ilova nomi hamda versiya raqamini ko‘rsatadigan User-Agent
sarlavhasi (`Humogram/<versiya raqami>`). Telefonda ro‘yxat allaqachon bo‘lsa,
server «o‘zgarmagan» deb javob bera olishi uchun uning versiya belgisi ham
yuboriladi. Ro‘yxat dasturchi tomonidan imzolangan; havolalar u bilan
telefoningizning o‘zida solishtiriladi. Xuddi shu faylda virus skaneri uchun
dasturchining ilovalar ro‘yxati ham keladi.

Server ro‘yxat so‘rovlari uchun IP-manzillarni saqlamaydi: bu fayl jurnaliga
faqat vaqt, natija va versiya raqami yoziladi. Shulardan server ro‘yxat kuniga
necha marta va ilovaning qaysi versiyalaridan so‘ralganini ham hisoblaydi.

Havolalar himoyasini Sozlamalar → Humogram → Havolalar himoyasi orqali
o‘chirish mumkin. Virus skaneri ham shu fayldan foydalanadi, shuning uchun
«Ishga tushganda viruslarni tekshirish» yoqilgan ekan, ilova uni yuklab olishda
davom etadi; ikkalasi ham o‘chirilsa, ro‘yxat umuman yuklab olinmaydi.

#### Tekshiruvga yuborish

Agar havola ogohlantirishi siz haqiqiy deb biladigan saytni ko‘rsatsa
(masalan, yangi davlat xizmati yoki bank), yoki virus skaneri siz ishonadigan
ilovani belgilasa, ogohlantirish ostidagi **«Tekshiruvga yuborish»**ni bosishingiz
mumkin. Shunda nima yuborilishini aniq aytadigan oyna chiqadi va faqat siz
tasdiqlasangiz, ilova <https://lists.skycoax.uz/v1/requests> manziliga bitta
so‘rov yuboradi:

- sayt uchun — uning nomi (masalan, `new-service.example.uz`); havolaning
  o‘zi, uning yo‘li yoki u bo‘lgan chat emas;
- ilova uchun — paket nomi, imzo sertifikatining SHA-256 barmoq izi, nomi va
  versiyasi; boshqa ilovalaringiz haqida hech narsa.

Har qanday veb-so‘rov kabi, unda IP-manzilingiz va `Humogram/<versiya raqami>`
User-Agent sarlavhasi bo‘ladi. Unda hisob, telefon raqami, qurilma
identifikatori yoki xabarlar yo‘q. Server so‘rov bilan birga IP-manzilni va
User-Agent’ni saqlamaydi: u faqat nima yuborilgani, necha marta va qachon
yuborilganini dasturchi ko‘rib chiqadigan navbatda saqlaydi. Dasturchi qaror
qilgach, so‘rov navbatdan chiqadi va tarixda — nima yuborilgani, necha marta
va qanday qaror qilingani — oxirgi yuborilganidan keyin bir yilgacha
saqlanadi, so‘ng o‘chiriladi; sayt yoki ilova haqiqiy bo‘lsa, u ochiq ro‘yxatga
qo‘shiladi va unga havolalar hamma uchun qizil bo‘lmay qoladi — yoki ilova
belgilanmay qoladi. Sizning bosishingiz va tasdig‘ingizsiz tekshiruvga hech
narsa yuborilmaydi.

### 4. Ma’lumotlar nima uchun ishlatiladi

Xabar almashish, qo‘ng‘iroq va fayl uzatish (Telegram orqali); kontaktlardan
tanishlarni topish; obuna va xaridlar (Google Play); zararli ilovalar, fayllar
va havolalardan himoya (tekshiruv faqat qurilmada); saytlar va ilovalar
ro‘yxatini yangilab turish; siz tekshiruvga yuborgan sayt va ilovalarni
ko‘rib chiqish; xizmat xavfsizligi.

### 5. Uchinchi tomonlarga uzatish

Biz ma’lumotlaringizni sotmaymiz. Ilovadan foydalanish aloqa ma’lumotlarini
**Telegram LLC**’ga, xarid ma’lumotlarini **Google**’ga yuboradi. Qurilmadagi
skaner hech kimga hech narsa uzatmaydi. Ro‘yxat serveriga kelgan so‘rovlar va
siz tekshiruvga yuborgan narsalar haqidagi ma’lumotlarni dasturchi hech kimga
bermaydi.

### 6. Ma’lumotlar qancha saqlanadi

Dasturchida hisobingiz, xabarlaringiz yoki tekshiruv natijalaringiz
saqlanmaydi. Ro‘yxat serveri ro‘yxat so‘rovlari uchun IP-manzillarni
saqlamaydi. Tekshiruvga yuborilgan so‘rov yuboruvchi haqida hech qanday
ma’lumotsiz saqlanadi: dasturchi qaror qilguncha navbatda, so‘ng tarixda (nima
yuborilgani, necha marta, qanday qaror) oxirgi yuborilganidan keyin bir
yilgacha, keyin o‘chiriladi. Aloqa
ma’lumotlari Telegram’da (ularni Telegram’dan o‘chirishingiz mumkin),
xaridlar Google va Telegram’da saqlanadi. Skaner natijalari va ishchi
ma’lumotlari faqat qurilmada turadi va ilova o‘chirilganda yoki uning
ma’lumotlari tozalanganda o‘chadi.

### 7. Sizning huquqlaringiz

Telegram hisobingizni o‘chirishingiz mumkin (<https://my.telegram.org/auth?to=delete>);
xaridlarni Google Play orqali boshqarasiz; savollar bo‘yicha biz bilan bog‘laning.

### 8. Buni qanday tekshirish mumkin

Ilova ochiq kodli (GPLv3): <https://github.com/skycoax/Humogram>. Bu yerda
yozilganlarni kodda tekshirib ko‘rishingiz mumkin.

### 9. Bolalar va yosh cheklovlari

Humogram 13 yosh va undan katta foydalanuvchilar uchun mo‘ljallangan, 13 yoshga
to‘lmagan bolalarga mo‘ljallanmagan. Agar mamlakatingiz qonunida onlayn
xizmatlardan foydalanish yoki ma’lumotlaringizni ishlashga rozilik berish uchun
yuqoriroq yosh belgilangan bo‘lsa (masalan, Yevropa Ittifoqining ayrim
mamlakatlarida 16 yosh), shu yoshgacha ilovadan faqat ota-ona yoki vasiyning
ruxsati bilan foydalanish mumkin.

Odamlar Telegram’da ommaviy joylaydigan ayrim kontent faqat kattalar uchun
mo‘ljallangan. Ilova 18+ deb belgilangan mediani standart holatda yashiradi va
uni faqat chat sozlamalarida 18+ kontentni yoqib, 18 yoshga to‘lganingizni
tasdiqlaganingizdan keyin ko‘rsatadi; qonun talab qiladigan joylarda (masalan,
Buyuk Britaniyada) qo‘shimcha yosh tekshiruvi o‘tkaziladi.

Agar ilovadan 13 yoshga to‘lmagan bola foydalanayotgan yoki bola haqidagi
ma’lumot bexosdan ishlangan deb hisoblasangiz, biz bilan bog‘laning.

### 10. Aloqa

kamolov1575@gmail.com · Kamolov Muxammad

---

<a name="ru"></a>

## Русский

### 1. Введение

Humogram — мессенджер на базе Telegram со встроенной защитой, которая
**работает на вашем устройстве**: сканером вирусов для приложений на телефоне,
проверкой файлов и ссылок в чатах и защитой ссылок.

- **Наш сканер сам ничего не собирает и не отправляет.** Приложения, файлы,
  их цифровые отпечатки (SHA-256) и ссылки проверяются только на вашем телефоне.
- **Защита ссылок** скачивает с сервера разработчика (lists.skycoax.uz) один
  общедоступный список доверенных и заблокированных сайтов и приложений;
  ничего о ваших ссылках и приложениях не отправляется.
- **«Отправить на проверку»** — единственное исключение, и только если вы им
  воспользуетесь: если нажать эту ссылку под предупреждением о сайте или у
  приложения, отмеченного сканером, и подтвердить, разработчику на проверку
  уходит имя этого сайта — или имя пакета приложения, отпечаток его подписи,
  название и версия.
- **Как мессенджер** приложение использует Telegram, поэтому данные вашей
  переписки обрабатываются на серверах Telegram.

### 2. Кто отвечает за ваши данные

- **Переписка — Telegram Messenger (Telegram LLC / FZ-LLC).** Данные аккаунта и
  переписки проходят через систему Telegram и хранятся там:
  <https://telegram.org/privacy>.
- **Покупки — Google (Google Play).**
- **Приложение, сканер и список сайтов — разработчик (Kamolov Muxammad).** У
  разработчика есть один сервер (адреса lists.skycoax.uz и hg.skycoax.uz). Он
  раздаёт общедоступный список сайтов и приложений для защиты ссылок и сканера
  вирусов (см. раздел 3) и — если вы нажали «Подробнее» в напоминании об
  облачном пароле — обучающее видео. Он не получает от вас ничего, кроме
  технических данных, которые несёт любой веб-запрос (IP-адреса, номера сборки
  приложения и времени запроса), и того, что вы сами отправите кнопкой
  «Отправить на проверку» (см. раздел 3). Сервер размещён на
  VPS в Ташкенте (Узбекистан), хостинг-провайдер — AIRNET LLC. Поэтому, даже
  если вы пользуетесь приложением в другой стране, данные этого запроса
  обрабатываются в Узбекистане.

### 3. Какие данные обрабатываются

Так как Humogram — клиент Telegram, следующее отправляется на **серверы
Telegram** (как в официальном приложении):

| Данные | Кто обрабатывает | Когда |
|---|---|---|
| Номер телефона | Telegram | Вход в аккаунт (обязательно) |
| Имя, имя пользователя, «о себе», фото профиля | Telegram | Профиль |
| E-mail для двухэтапной проверки | Telegram | Если задан |
| Контакты | Telegram | При синхронизации контактов |
| Сообщения, медиа, документы, голосовые, звонки в обычных чатах | Telegram | При переписке |
| Геолокация | Telegram | Если вы ею делитесь |
| Покупки (Premium, Stars, подарки) | Google Play → Telegram | При покупке |

**Секретные чаты** защищены сквозным шифрованием и остаются только на ваших
устройствах.

Описанные ниже проверки выполняются на самом телефоне. Наружу уходят только две
вещи: запрос на скачивание списка, описанный в пункте «Защита ссылок», и —
только если вы ею воспользуетесь — «Отправить на проверку», описанная в конце
этого раздела.

#### Файлы и ссылки в чатах

Полученные файлы и ссылки в сообщениях проверяются на вашем телефоне по
правилам и базе, встроенным в приложение. Для этого никуда не отправляются ни
файлы, ни их цифровые отпечатки (SHA-256), ни имена файлов, ни ссылки, ни текст
сообщений.

#### Сканер вирусов (приложения на телефоне)

Сканер вирусов проверяет установленные на телефоне приложения на известные
вредоносные программы и на признаки, характерные для банковских троянов и
программ, ворующих SMS. Он работает полностью на телефоне: проверку запускаете
вы сами (Настройки → Безопасность устройства) или, если вы разрешите, она
выполняется при каждом запуске Humogram.

Только на самом телефоне сканер читает:

- приложения, которые Android позволяет Humogram видеть: приложения со значком
  в меню приложений; приложения, которые предлагают службу специальных
  возможностей, доступ к уведомлениям или функции SMS; а также магазины
  приложений и установщики на телефоне. Humogram находит их через запросы
  видимости пакетов Android (package visibility) и не использует разрешение на
  просмотр всех приложений (QUERY_ALL_PACKAGES);
- для каждого такого приложения: имя пакета, название и версию; какое
  приложение или магазин его установил; выданы ли ему разрешения на SMS или
  звонки; включено ли оно как служба специальных возможностей, как приложение с
  доступом к уведомлениям, как администратор устройства или как приложение для
  SMS по умолчанию и может ли показываться поверх других приложений; скрыт ли
  его значок; отпечаток его сертификата подписи;
- для приложений, установленных не из магазина приложений, — отпечатки
  SHA-256, SHA-1 и MD5 установочного файла;
- APK-файлы (установочные файлы приложений) в собственных папках загрузок и
  кеша Humogram.

Всё это сравнивается с правилами и списком отпечатков известных вредоносных
программ, которые поставляются внутри приложения, и со списком приложений
разработчика — проверенных (имя пакета вместе с сертификатом подписи
издателя) и известных вредоносных, — который приходит в том же общедоступном
файле, что и список сайтов (см. «Защита ссылок»). **Ничего из того, что сканер
читает или находит, никуда не отправляется** — ни разработчику, ни в Telegram,
ни кому-либо ещё: для проверки сканер не обращается к интернету. Единственное
исключение — приложение, которое вы сами отправите на проверку (см. ниже
«Отправить на проверку»). Результаты хранятся только
на телефоне, во внутреннем хранилище Humogram, недоступном другим приложениям.
Это хранилище не попадает в резервные копии и стирается при удалении
приложения или очистке его данных.

#### Проверка устройства и уровень защиты

На странице Настройки → Humogram приложение проверяет на самом телефоне: есть
ли блокировка экрана, есть ли признаки root, включена ли отладка по USB и
насколько давно было последнее обновление безопасности. Для этого читаются
несколько системных настроек и проверяется наличие файлов. Есть ли у аккаунта
облачный пароль (2FA), спрашивается у Telegram — так же, как это делают
настройки конфиденциальности официального приложения. Результаты и
рассчитанный из них уровень защиты (процент) показываются только на телефоне и
никуда не отправляются.

#### Защита ссылок

Когда защита ссылок включена (по умолчанию она включена), ссылки в чатах на
сайты, которых нет в списке доверенных, показываются красным и открываются
только после двух подтверждений; для сайтов из чёрного списка показывается
более строгое предупреждение. Ссылки проверяются на вашем телефоне.

Чтобы списки не устаревали, приложение скачивает с сервера разработчика один
общедоступный файл — <https://lists.skycoax.uz/v1/lists.json>: при запуске
приложения, при возврате в него, примерно раз в пять минут, пока оно открыто,
а также когда вы открываете ссылку с предупреждением (если список не
проверялся в последние несколько минут). Файл одинаков для всех. В запросе нет
ни ссылок, ни адресов сайтов, ни сообщений, ни идентификаторов аккаунта или
устройства — только то, что несёт любой веб-запрос: ваш IP-адрес и заголовок
User-Agent с названием приложения и номером сборки
(`Humogram/<номер сборки>`). Если на телефоне уже есть список, передаётся ещё
метка его версии,
чтобы сервер мог ответить «не изменился». Список подписан разработчиком, а
ссылки сверяются с ним на вашем телефоне. В том же файле приходит список
приложений разработчика для сканера вирусов.

Для запросов списка сервер не хранит IP-адреса: в журнал по этому файлу
записываются только время, результат и номер сборки. Из них сервер также
считает, сколько раз в день список запрашивали и с каких версий приложения.

Защиту ссылок можно выключить: Настройки → Humogram → Защита ссылок. Сканер
вирусов пользуется тем же файлом, поэтому, пока включено «Проверять на вирусы
при запуске», приложение продолжает его скачивать; если выключить оба
переключателя, список не скачивается вовсе.

#### Отправить на проверку

Если предупреждение о ссылке называет сайт, который вы знаете как настоящий
(например, новый госсервис или банк), или сканер вирусов отметил приложение, в
котором вы уверены, под предупреждением можно нажать **«Отправить на
проверку»**. Появится окно, где точно сказано, что будет отправлено, и только
после вашего подтверждения приложение отправит один запрос на
<https://lists.skycoax.uz/v1/requests>:

- для сайта — его имя (например, `new-service.example.uz`), но не ссылку, не её
  путь и не чат, в котором она была;
- для приложения — имя пакета, отпечаток SHA-256 его сертификата подписи,
  название и версию; ничего о других ваших приложениях.

Как любой веб-запрос, он несёт ваш IP-адрес и User-Agent
`Humogram/<номер сборки>`. В нём нет аккаунта, номера телефона, идентификатора
устройства или сообщений. Сервер не сохраняет IP-адрес и User-Agent вместе с
запросом: он хранит только то, что было отправлено, сколько раз и когда, в
очереди, которую просматривает разработчик. Когда разработчик принял решение,
запрос уходит из очереди и остаётся в истории — что отправлено, сколько раз и
какое принято решение — до года после последней отправки, затем удаляется;
если сайт или приложение настоящие, они попадают в
общедоступный список, и ссылки на сайт перестают быть красными — или
приложение перестаёт помечаться — у всех. Без вашего нажатия и подтверждения
на проверку не отправляется ничего.

### 4. Для чего используются данные

Переписка, звонки и передача файлов (через Telegram); поиск знакомых среди
контактов; подписки и покупки (Google Play); защита от вредоносных приложений,
файлов и ссылок (проверка — только на устройстве); обновление списков сайтов и
приложений; проверка сайтов и приложений, которые вы отправили на проверку;
безопасность сервиса.

### 5. Передача третьим сторонам

Мы не продаём ваши данные. Использование приложения отправляет данные переписки
в **Telegram LLC**, данные о покупках — в **Google**. Сканер на устройстве не
передаёт никому ничего. Сведения о запросах к серверу списков и то, что вы
отправили на проверку, разработчик никому не передаёт.

### 6. Сколько храним

У разработчика не хранятся ваш аккаунт, переписка или результаты проверок.
Сервер списков не хранит IP-адреса для запросов списка. Запрос, отправленный
на проверку, хранится без каких-либо сведений об отправителе: в очереди — пока
разработчик не примет решение, затем в истории (что отправлено, сколько раз,
какое решение) — до года после последней отправки, после чего удаляется.
Данные переписки — в
Telegram (можно удалить в Telegram), покупки — у Google и Telegram. Результаты
и рабочие данные сканера хранятся только на устройстве и удаляются вместе с
приложением или при очистке его данных.

### 7. Ваши права

Вы можете удалить аккаунт Telegram (<https://my.telegram.org/auth?to=delete>);
управлять покупками через Google Play; связаться с нами по вопросам.

### 8. Как это проверить

Приложение с открытым кодом (GPLv3): <https://github.com/skycoax/Humogram>.
Написанное здесь можно проверить в коде.

### 9. Дети и возрастные ограничения

Humogram предназначен для пользователей от 13 лет и не рассчитан на детей
младше 13 лет. Если закон вашей страны устанавливает более высокий возраст для
пользования онлайн-сервисами или согласия на обработку данных (например, 16 лет
в некоторых странах ЕС), до этого возраста пользоваться приложением можно только
с разрешения родителя или опекуна.

Часть материалов, которые люди публикуют в Telegram в открытом доступе,
предназначена только для взрослых. Приложение по умолчанию скрывает медиа с
отметкой 18+ и показывает их, только если вы включите контент 18+ в настройках
чатов и подтвердите, что вам исполнилось 18 лет; там, где этого требует закон
(например, в Великобритании), дополнительно проводится проверка возраста.

Если вы считаете, что приложением пользуется ребёнок младше 13 лет или что
данные ребёнка были обработаны по ошибке, свяжитесь с нами.

### 10. Связь

kamolov1575@gmail.com · Kamolov Muxammad

---

<a name="en"></a>

## English

### 1. Introduction

Humogram is a messenger built on the Telegram platform, with built-in
protection that **runs on your device**: a virus scanner for the apps on your
phone, checks of the files and links you receive in chats, and link protection.

- **Our scanner collects and sends nothing by itself.** Apps, files, their
  fingerprints (SHA-256) and links are checked only on your phone.
- **Link protection** downloads one public list of trusted and blocked sites and
  apps from the developer's server (lists.skycoax.uz); nothing about your links
  or apps is sent.
- **Send for review** is the one exception, and only when you use it: if you
  tap it under a link warning or on an app the scanner flagged, and confirm,
  that site's name — or that app's package name, signature fingerprint, name
  and version — is sent to the developer to check.
- **As a messenger**, the app uses Telegram, so your messaging data is processed
  on Telegram's servers.

### 2. Who is responsible for your data

- **Messaging — Telegram Messenger (Telegram LLC / FZ-LLC).** Account and
  conversation data passes through and is stored on Telegram's system:
  <https://telegram.org/privacy>.
- **Purchases — Google (Google Play).**
- **The app, the scanner and the site list — the developer (Kamolov
  Muxammad).** The developer runs one server (lists.skycoax.uz and
  hg.skycoax.uz). It serves the public list of sites and apps used by link
  protection and the virus scanner (see section 3) and, when you tap "Learn
  more" on the cloud-password reminder, the explainer video. It receives
  nothing from you beyond the technical data every web request carries — your
  IP address, the app's build number and the time of the request — except
  what you yourself send with "Send for review" (see section 3). The server
  is a VPS in Tashkent, Uzbekistan (hosting provider AIRNET LLC), so if you
  use the app from another country, this request data is processed in
  Uzbekistan.

### 3. What data is processed

Because Humogram is a Telegram client, the following is sent to **Telegram's
servers** (the same as the official app):

| Data | Processed by | When |
|---|---|---|
| Phone number | Telegram | Sign in (required) |
| Name, username, bio, profile photo | Telegram | Profile |
| Two-step verification email | Telegram | If set |
| Contacts | Telegram | If contact sync is on |
| Messages, media, documents, voice, calls in normal chats | Telegram | When messaging |
| Location | Telegram | If you share it |
| Purchases (Premium, Stars, gifts) | Google Play → Telegram | When you buy |

**Secret chats** are end-to-end encrypted and stay only on your devices.

The checks described below run on your phone itself. Only two things go out:
the request for the list described under Link protection, and — only if you
use it — "Send for review", described at the end of this section.

#### Files and links in chats

Files you receive and links in messages are checked on your phone, against
rules and a database built into the app. Files, their fingerprints (SHA-256),
file names, links and message text are not sent anywhere for this.

#### Virus scanner (apps on your phone)

The virus scanner checks the apps installed on your phone for known malware and
for signs typical of banking trojans and SMS-stealing apps. It runs entirely on
your phone: you start a check yourself (Settings → Device security) or, if you
allow it, it runs each time Humogram starts.

On the phone only, the scanner reads:

- the apps Android lets Humogram see: apps with an icon in the app launcher;
  apps that offer an accessibility service, notification access or SMS
  features; and the app stores and installers on the phone. Humogram finds them
  through Android's package-visibility queries and does not use the permission
  to see all apps (QUERY_ALL_PACKAGES);
- for each of these apps: its package name, name and version; which app or
  store installed it; whether it has been granted SMS or phone-call
  permissions; whether it is active as an accessibility service, as a
  notification listener, as a device administrator or as the default SMS app,
  and whether it may draw over other apps; whether its launcher icon is hidden;
  and the fingerprint of its signing certificate;
- for apps installed outside an app store, the SHA-256, SHA-1 and MD5
  fingerprints of the installer file;
- APK files (app installers) in Humogram's own download and cache folders.

All of this is compared with rules and a list of known-malware fingerprints
that ship inside the app, and with the developer's list of apps — apps vouched
for (a package together with its publisher's signing certificate) and known
malware — which arrives in the same public file as the site list (see Link
protection). **Nothing the scanner reads or finds is sent anywhere** — not to
the developer, not to Telegram, not to anyone else: the scanner does not go
online at all. The one exception is an app you yourself send for review (see
"Send for review" below). Results are kept only on your phone, in Humogram's
private storage that other apps cannot read. That storage is excluded from
backups and is erased when you uninstall the app or clear its data.

#### Device check and protection level

On the Settings → Humogram page the app checks, on the phone itself, whether the
device shows signs of root, whether USB
debugging is on and how old the last security update is. It does this by
reading a few system settings and checking whether certain files exist.
Whether your account has a cloud password (2FA) is asked of Telegram, the same
way the official app's Privacy settings ask. The results, and the protection
level (a percentage) worked out from them, are shown only on your phone and are
not sent anywhere.

#### Link protection

When link protection is on (it is on by default), links in chats to sites that
are not on the list of trusted sites are shown in red and open only after you
confirm twice; sites on the blocked list get a stronger warning. Links are
checked on your phone.

To keep the lists current, the app downloads one public file from the
developer's server, <https://lists.skycoax.uz/v1/lists.json>: when the app
starts, when you return to it, about every five minutes while it is open, and
when you open a link that shows a warning (unless the list was checked in the
last few minutes). The file is the same for everyone. The request contains no
link, site address, message, account or device identifier — only what any web
request carries: your IP address and a User-Agent header naming the app and its
build number (`Humogram/<build number>`). If the phone already has a list, the
request also names that list's version, so that the server can answer
"unchanged". The list is signed by the developer, and links are matched
against it on your phone. The same file carries the developer's list of apps
for the virus scanner.

The server keeps no IP addresses for list requests: its log for this file
records only the time, the result and the build number. From these the server
also counts how many times a day the list is requested, and from which app
versions.

You can switch link protection off in Settings → Humogram → Link protection.
The virus scanner uses the same file, so the app still downloads it while
"Scan for viruses at launch" is on; with both switched off, it does not
download the list at all.

#### Send for review

If a link warning names a site you know is genuine — a new government or bank
service, say — or the virus scanner flags an app you are sure is genuine, you
can tap **Send for review** under the warning. A dialog then says exactly what
will be sent, and only if you confirm does the app send one request to
<https://lists.skycoax.uz/v1/requests>:

- for a site: its name (for example `new-service.example.uz`) — not the link,
  its path, or the chat it was in;
- for an app: its package name, the SHA-256 fingerprint of its signing
  certificate, its name and its version — nothing about your other apps.

Like any web request it also carries your IP address and the
`Humogram/<build number>` User-Agent. It contains no account, phone number,
device identifier or message. The server does not store the IP address or the
User-Agent with the request: it keeps only what was sent, how many times and
when, in a queue the developer reviews. Once the developer has decided, the
request leaves the queue and stays in a record — what was sent, how many times
and what was decided — for up to a year after it was last sent, and is then
deleted; if the site or app is genuine, it is added to the public
list, and its links stop being red — or the app stops being flagged — for
everyone. Nothing is ever sent for review without your tap and confirmation.

### 4. How data is used

Messaging, calls and file transfer (via Telegram); finding people you know among
your contacts; subscriptions and purchases (Google Play); protection from
malicious apps, files and links (checked on the device only); keeping the site
and app lists up to date; reviewing the sites and apps you send for review;
service security.

### 5. Sharing with third parties

We do not sell your data. Using the app sends messaging data to **Telegram LLC**
and purchase data to **Google**. The on-device scanner discloses nothing to
anyone, and the developer does not pass on anything about requests to the list
server or anything you send for review.

### 6. Data retention

The developer keeps no account, message or scan data about you. The list
server keeps no IP addresses for list requests. A request you send for review
is kept with nothing about who sent it: in the queue until the developer has
decided on it, then as a record (what was sent, how many times, the decision)
for up to a year after it was last sent, and is then deleted. Messaging data
is retained by
Telegram (you can delete it in Telegram); purchases by Google and Telegram. The
scanner's results and working data stay only on your device and are deleted
when you uninstall the app or clear its data.

### 7. Your rights

You can delete your Telegram account (<https://my.telegram.org/auth?to=delete>);
manage purchases through Google Play; and contact us with questions.

### 8. How to check this

The app is open source (GPLv3): <https://github.com/skycoax/Humogram>. You can
check what is written here against the code.

### 9. Children and age limits

Humogram is intended for users aged 13 and over and is not directed to children
under 13. If the law of your country sets a higher age for using online services
or for consenting to the processing of your data (for example, 16 in some EU
countries), you may use the app below that age only with a parent's or
guardian's permission.

Some content that people share publicly on Telegram is meant for adults only.
The app hides media marked 18+ by default and shows it only after you turn on
18+ content in Chat Settings and confirm that you are at least 18; where the law
requires it (for example, in the UK), an additional age check is carried out.

If you believe a child under 13 is using the app, or that a child's data has
been processed in error, please contact us.

### 10. Contact

kamolov1575@gmail.com · Kamolov Muxammad
