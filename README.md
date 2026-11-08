# Hotel Booking

Учебная микросервисная система бронирования отелей. Пользователь выбирает номер или поручает выбор системе, получает историю своих бронирований и может отменить бронь. Администратор управляет отелями, номерами и пользователями, просматривает загрузку за период. Автоматический подбор сначала рассматривает свободные номера с минимальным `timesBooked`, затем с минимальным `id`.

## Состав и стек

| Модуль | Порт | Назначение |
|---|---:|---|
| `discovery-server` | 8761 | Реестр Eureka; отдельный процесс |
| `api-gateway` | 8080 | Внешняя точка входа, маршрутизация через Eureka, проверка JWT и `X-Request-Id` |
| `booking-service` | 8081 | Пользователи, аутентификация, собственные бронирования, координация саги |
| `hotel-service` | 8082 | Отели, номера, календарь занятости, рекомендации и статистика |
| `common` | — | Общий код ошибок, JWT, безопасности и трассировки MVC-сервисов; не запускается отдельно |

Базовая версия — **Java 21**, Spring Boot **3.5.16**, Spring Cloud **2025.0.3**, Maven Wrapper. Используются Spring MVC, Spring Data JPA/Hibernate, отдельные H2 для двух бизнес-сервисов, Spring Security OAuth2 Resource Server, Spring Cloud Gateway WebFlux, Eureka, Resilience4j, springdoc-openapi, JUnit 5, MockMvc и WebTestClient.

JDK 25 можно использовать как дополнительный JDK для сборки и запуска без preview-возможностей. Уровень исходников и целевой байткод остаются Java 21; при смене JDK выполните полную проверку проекта. Gateway не зависит от `common`, чтобы не смешивать Servlet/MVC и WebFlux.

## Сборка и проверка

Установите JDK 21 и настройте `JAVA_HOME`. Docker нужен только для варианта запуска через Compose. Все команды ниже выполняются из корня `hotel-booking`.

Windows PowerShell:

```powershell
java -version
.\mvnw.cmd verify
```

Linux/macOS:

```bash
java -version
./mvnw verify
```

Первый запуск Wrapper загружает Maven и зависимости. `verify` выполняет проверку форматирования, компиляцию и тесты. Отчёты тестов находятся в `*/target/surefire-reports/`; артефакты приложений — в `*/target/*-1.0.0.jar`. Для исправления форматирования: `./mvnw spotless:apply` или `.\mvnw.cmd spotless:apply`.

Проверено 8 ноября 2026 на Temurin 21.0.13: **74 теста прошли**, Spotless и сборка всех модулей завершились успешно. Дополнительно четыре собранных приложения запущены вместе: **25 сквозных проверок прошли**, включая восемь одновременных запросов на один номер. Подробнее — [отчёт проверки](docs/verification.md). После изменений повторяйте `verify` и `scripts/smoke.ps1` в своей среде.

## Запуск

После успешной сборки можно использовать PowerShell-скрипты:

```powershell
.\scripts\start.ps1
.\scripts\smoke.ps1
.\scripts\stop.ps1
```

Либо откройте четыре терминала и запустите по одной команде в каждом, начиная с Eureka:

```text
java -jar discovery-server/target/discovery-server-1.0.0.jar
java -jar hotel-service/target/hotel-service-1.0.0.jar
java -jar booking-service/target/booking-service-1.0.0.jar
java -jar api-gateway/target/api-gateway-1.0.0.jar
```

Дождитесь регистрации `HOTEL-SERVICE`, `BOOKING-SERVICE` и `API-GATEWAY` на [странице Eureka](http://localhost:8761/). Health-проверки процессов: `http://localhost:8761/actuator/health`, `http://localhost:8082/actuator/health`, `http://localhost:8081/actuator/health` и `http://localhost:8080/actuator/health`. После старта Eureka клиентам требуется некоторое время для обновления реестра; ранний запрос через Gateway может получить `503`.

Вариант с Docker Compose:

```bash
docker compose up --build
docker compose down
```

Демонстрационные пользователи:

| Логин | Пароль | Роль |
|---|---|---|
| `admin` | `Admin123!` | ADMIN |
| `demo` | `Demo123!` | USER |

Booking Service по умолчанию использует профиль `demo`. Hotel Service создаёт два отеля, шесть номеров, один из которых находится на ремонте, и три исторических интервала: счётчики бронирований уже отличаются, поэтому видно действие сортировки рекомендаций. Отрицательные `bookingId` исторических слотов — демонстрационная предыстория, а не ссылки на существующие брони Booking Service.

**Обе H2 находятся в памяти своих процессов.** Перезапуск сервиса удаляет его данные, историю идемпотентности и незавершённые компенсации. Для чистой демонстрации остановите и запустите все четыре процесса вместе. Раздельный перезапуск бизнес-сервисов может оставить несогласованные демонстрационные данные; этот режим не имитирует надёжное восстановление производственной БД.

## Быстрый сценарий через Gateway

Пример PowerShell использует будущие даты относительно дня запуска:

```powershell
$baseUrl = 'http://localhost:8080'
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/user/auth" -ContentType 'application/json' -Body (@{
    username = 'demo'
    password = 'Demo123!'
} | ConvertTo-Json)
$headers = @{
    Authorization = 'Bearer ' + $login.accessToken
    'X-Request-Id' = [guid]::NewGuid().ToString()
}
$startDate = (Get-Date).AddDays(7).ToString('yyyy-MM-dd')
$endDate = (Get-Date).AddDays(9).ToString('yyyy-MM-dd')

Invoke-RestMethod -Uri "$baseUrl/api/hotels?page=0&size=10" -Headers $headers
Invoke-RestMethod -Uri "$baseUrl/api/rooms/recommend?startDate=$startDate&endDate=$endDate&hotelId=1" -Headers $headers

$requestId = [guid]::NewGuid().ToString()
$bookingJson = @{
    requestId = $requestId
    autoSelect = $true
    hotelId = 1
    startDate = $startDate
    endDate = $endDate
} | ConvertTo-Json
$booking = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/bookings" -Headers $headers -ContentType 'application/json' -Body $bookingJson
$booking

# Тот же requestId и то же тело возвращают существующую бронь.
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/bookings" -Headers $headers -ContentType 'application/json' -Body $bookingJson
Invoke-RestMethod -Uri "$baseUrl/api/bookings?page=0&size=10" -Headers $headers
Invoke-RestMethod -Method Delete -Uri "$baseUrl/api/bookings/$($booking.id)" -Headers $headers
```

Для ручного выбора передайте `autoSelect: false` и положительный `roomId`. Для нового бизнес-запроса создавайте новый UUID `requestId`. `X-Request-Id` — идентификатор трассировки HTTP-запроса; это отдельное поле, не ключ идемпотентности бронирования.

Бронь создаётся со статусом `PENDING`, затем становится `CONFIRMED` или `CANCELLED`. Первый запрос создания возвращает `201`, повтор того же запроса — `200`. Если повтор пришёл, пока первый запрос ещё обрабатывается, в ответе возможен `PENDING`; актуальное состояние доступно по `GET /booking/{id}`. Отмена возвращает `204` и сохраняет запись для истории. Даты существующей брони не редактируются: изменение поездки оформляется отменой и новым запросом.

## API и права

| Метод и путь через Gateway | Доступ | Поведение |
|---|---|---|
| POST `/user/register` | Все | Регистрация USER, выдача JWT |
| POST `/user/auth` | Все | Вход, выдача JWT |
| GET/POST/PATCH `/user` | ADMIN | Список, создание и изменение пользователей |
| GET/DELETE `/user/{id}` | ADMIN | Чтение/удаление пользователя; DELETE также поддерживает `/user?id=...` |
| POST `/booking` | USER, ADMIN | Создание собственной брони, ручной или автоматический выбор |
| GET `/bookings` | USER, ADMIN | Своя история с `page`, `size`, `sort` |
| GET/DELETE `/booking/{id}` | Владелец | Чтение/отмена собственной брони; чужой ID возвращает `404` |
| GET/POST `/api/bookings` | USER, ADMIN | Алиасы списка `/bookings` и создания `/booking` соответственно |
| GET/DELETE `/api/bookings/{id}` | Владелец | Алиасы `/booking/{id}`; ID содержит только цифры |
| GET `/api/hotels`, `/api/hotels/{id}` | USER, ADMIN | Список и карточка отеля |
| POST/PUT/PATCH/DELETE `/api/hotels[/{id}]` | ADMIN | Управление отелями |
| GET `/api/rooms` | USER, ADMIN | Свободные номера за `startDate`, `endDate`; необязательный `hotelId`, пагинация и сортировка |
| GET `/api/rooms/recommend` | USER, ADMIN | Свободные номера по `timesBooked ASC, id ASC` |
| GET `/api/rooms/inventory` | ADMIN | Инвентарь, включая недоступные номера |
| GET `/api/rooms/{id}` | USER, ADMIN | Карточка номера |
| POST/PUT/PATCH/DELETE `/api/rooms[/{id}]` | ADMIN | Управление номерами |
| GET `/api/hotels/statistics` | ADMIN | Загрузка за `startDate`, `endDate`, необязательный `hotelId` |

Основной коллекционный путь `/api/bookings` отображается в `/bookings` для GET и в `/booking` для POST; GET/DELETE `/api/bookings/{числовой id}` отображаются в `/booking/{id}`. Исходные пути `/booking`, `/bookings`, `/user/**` сохраняются. Также доступны префиксные алиасы `/api/bookings/booking/**`, `/api/bookings/bookings`, `/api/bookings/user/**`: для них Gateway удаляет первые два сегмента пути. Маршруты `/api/hotels/**` и `/api/rooms/**` передаются без изменения пути. Заголовок `Authorization` сохраняется, а сервисы повторно проверяют JWT и права.

Внутренние `POST /api/rooms/{id}/confirm-availability` и `POST /api/rooms/{id}/release` доступны только Booking Service с внутренним JWT. Gateway возвращает для них `403` даже администратору и не пересылает запрос. Открытого Discovery Locator, публикующего все сервисы по их имени, нет.

Поиск отелей: `search`, `page`, `size`, `sort`, например `/api/hotels?search=River&sort=name,asc`. Полный контракт и схемы DTO доступны напрямую в документации сервисов:

- [Booking Swagger UI](http://localhost:8081/swagger-ui.html), [Booking OpenAPI JSON](http://localhost:8081/v3/api-docs).
- [Hotel Swagger UI](http://localhost:8082/swagger-ui.html), [Hotel OpenAPI JSON](http://localhost:8082/v3/api-docs).

В Swagger нажмите **Authorize** и вставьте JWT. Документация бизнес-сервисов не агрегируется в Gateway.

## Даты, согласованность и статистика

Период проживания — полуоткрытый интервал `[startDate, endDate)`. Заезд в день предыдущего выезда допустим. Для новой брони требуется `сегодня ≤ startDate < endDate`. Флаг `available` означает эксплуатационную готовность номера, а занятость на конкретные даты определяется календарём резервирований.

Booking Service хранит пользователя и состояние брони; Hotel Service хранит локальное зеркало занятых интервалов. Межсервисных SQL JOIN и общей БД нет. Проверка пересечения и резервирование номера выполняются в одной транзакции Hotel Service под блокировкой строки номера. Сетевой вызов не удерживает транзакцию Booking Service.

При ошибке или тайм-ауте Booking Service переводит бронь в `CANCELLED`, вызывает компенсацию и сохраняет признак `compensationPending`, если освобождение пока недоступно. Фоновая задача повторяет компенсации и отменяет зависшие `PENDING`. Компенсация в Hotel Service оставляет запись `RELEASED`, даже если подтверждение ещё не дошло: поздний запрос не сможет заново занять номер.

`timesBooked` увеличивается один раз при резервировании, уменьшается один раз при отмене и сохраняет завершённые неотменённые проживания. Рекомендация — приоритет кандидатов; окончательный допуск решает атомарное резервирование. При конкурентном конфликте автоматический подбор пробует следующего кандидата.

Статистика возвращает занятые ночи, доступную ёмкость, процент загрузки и строки по номерам. Интервалы обрезаются границами запрошенного периода. Ёмкость считается по всем неудалённым номерам, включая находящиеся сейчас на ремонте: исторический календарь ремонтов не моделируется. Удаление отелей/номеров мягкое, а при текущих или будущих бронях отклоняется с `409`.

## Ошибки и наблюдаемость

Ошибки имеют единый контракт:

```json
{
  "timestamp": "2027-01-01T10:00:00Z",
  "status": 409,
  "code": "ROOM_ALREADY_BOOKED",
  "message": "Room is already reserved for overlapping dates",
  "path": "/api/rooms/1/confirm-availability",
  "traceId": "a4a066c2-1a67-431c-ae6a-2c4d034d41ea",
  "violations": {}
}
```

Типовые статусы: `400` — некорректные параметры; `401` — отсутствующий/невалидный JWT; `403` — недостаточно прав; `404` — отсутствующий или чужой ресурс; `409` — пересечение дат либо конфликт ключа идемпотентности; `503/504` — недоступность/тайм-аут зависимого сервиса.

Gateway принимает безопасный `X-Request-Id` длиной до 64 символов либо генерирует UUID, передаёт его сервису и возвращает в ответе. Логи содержат traceId, ключевые изменения состояния и, где применимо, bookingId. JWT и пароли в штатные логи не выводятся. Eureka и Gateway используют ECS JSON; бизнес-события сервисов содержат именованные поля. В проекте нет развёрнутых Zipkin/Jaeger и Prometheus: Actuator и сквозной идентификатор дают базовую диагностику.

## Конфигурация и границы демонстрации

| Настройка | Назначение |
|---|---|
| `EUREKA_URL` | URL реестра, по умолчанию `http://localhost:8761/eureka/` |
| `JWT_SECRET` | Общий пользовательский ключ HS256 для проверки JWT; минимум 32 байта |
| `INTERNAL_JWT_SECRET` | Отдельный ключ для межсервисных токенов; настройка должна совпадать в Booking/Hotel |
| `HOTEL_SERVICE_URL` | Адрес Hotel для HTTP-клиента; по умолчанию логическое имя `http://hotel-service` |
| `HOTEL_DISCOVERY_ENABLED` | Отключение балансировщика клиента при обращении к фиксированному адресу |
| `DEMO_DATA=false` | Отключение начальных данных Hotel Service |
| `SPRING_PROFILES_ACTIVE` | Booking-профиль `demo` включает демопользователей |

Пароли и ключи по умолчанию предназначены для локального учебного запуска. Для отдельной среды задайте собственные секреты, постоянные БД, миграции схемы, TLS и ограничения сетевого доступа к Eureka и внутренним портам. Пользовательский JWT действует до истечения срока; мгновенный отзыв токенов и распределённое хранилище сессий не реализованы.

Пользовательский issuer/audience фиксированы в общем издателе токенов: `hotel-booking` / `hotel-api`. Параметры проверки Gateway должны соответствовать этому контракту; изменение только Gateway не меняет выпускаемые токены.
