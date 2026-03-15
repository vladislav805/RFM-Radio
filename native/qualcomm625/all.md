# Исследование `RFM-Radio/native/qualcomm625`

## Что это за проект

Это небольшой нативный FM-бинарник для устройств на Qualcomm 625, который:

- напрямую работает с `/dev/radio0` через V4L2;
- поднимает локальный UDP-сервер управления;
- принимает простые текстовые команды;
- рассылает асинхронные FM-события обратно в приложение;
- обрабатывает базовые FM-функции: включение, настройку частоты, seek/scan, RDS, AF, RSSI, stereo/mono.

По сути это мост между Android-приложением и FM-драйвером ядра без JNI, Binder и большого Android-стека вокруг.

## Главная идея архитектуры

Архитектура здесь очень прямая:

`Android app/service` -> UDP localhost command -> `fmbin` -> `fm_wrap.c` -> `fm_ctl.c` -> V4L2 ioctls -> `/dev/radio0`

А обратная дорога событий такая:

`/dev/radio0` event buffers -> `fm_wrap.c` event thread -> разбор RDS/seek/tune/stereo/RSSI -> UDP event datagram -> Android app/service

То есть это отдельный userspace daemon/утилита, а не библиотека.

## Карта файлов

### `main.c`

Точка входа и таблица API-команд.

Что делает:

- определяет список текстовых endpoint-команд;
- парсит входящую строку на аргументы;
- сопоставляет команду с handler-функцией;
- вызывает соответствующую FM-операцию;
- возвращает короткий строковый ответ.

Команды, которые реально поддерживаются:

- `init`
- `enable`
- `disable`
- `setfreq`
- `jump`
- `seekhw`
- `power_mode`
- `rds_toggle`
- `set_stereo`
- `set_antenna`
- `set_region`
- `searchhw`
- `search_cancel`
- `auto_af`

По смыслу `main.c` это command dispatcher поверх UDP.

### `ctl_server.c` / `ctl_server.h`

Сетевой слой управления.

Что здесь происходит:

- открывается UDP-сокет на `127.0.0.1:2112`;
- бинарник получает строковые команды от клиента;
- вызывает callback `api_handler`;
- отправляет ответ клиенту;
- отдельно умеет отправлять асинхронные события на `2113`.

Формат событий:

`${event_id}\x0c${message}`

где `0x0c` используется как разделитель. Это важная часть контракта с верхним приложением.

В `ctl_server.h` определены event id:

- `EVT_ENABLED`
- `EVT_DISABLED`
- `EVT_FREQUENCY_SET`
- `EVT_UPDATE_RSSI`
- `EVT_UPDATE_PS`
- `EVT_UPDATE_RT`
- `EVT_SEEK_COMPLETE`
- `EVT_STEREO`
- `EVT_SEARCH_DONE`
- `EVT_UPDATE_PTY`
- `EVT_UPDATE_PI`
- `EVT_UPDATE_AF`

То есть серверный слой здесь двусторонний:

- входящие команды;
- исходящие уведомления о состоянии радио.

### `fm_wrap.c` / `fm_wrap.h`

Главный orchestration-слой проекта.

Это файл, где из низкоуровневых V4L2 операций собирается уже понятное поведение FM-устройства.

Его задачи:

- запуск FM и ожидание инициализации через system properties;
- подготовка радио после открытия;
- создание потоков для событий и RSSI;
- обработка асинхронных FM interrupt/event буферов;
- обновление глобального состояния `fm_storage`;
- отправка событий наружу через UDP.

Ключевые части:

### 1. Глобальное состояние

`fm_storage` содержит:

- текущую частоту;
- выбранный band;
- mute/stereo mode;
- шаг канала;
- состояние радио (`OFF/RX/TX`);
- RSSI;
- текущие RDS данные (`PS`, `RT`, `PI`, `PTY`);
- флаг доступности сервиса.

Это основной runtime state всего бинарника.

### 2. Обработка событий драйвера

Функция `process_radio_event()` реагирует на события Tavarua/Iris:

- `RADIO_READY`
- `TUNE_SUCC`
- `SEEK_COMPLETE`
- `SCAN_NEXT`
- `NEW_RT_RDS`
- `NEW_PS_RDS`
- `STEREO`
- `MONO`
- `NEW_SRCH_LIST`
- `NEW_AF_LIST`
- `RADIO_DISABLED`

На каждом событии она:

- обновляет `fm_storage`;
- дочитывает нужный буфер из V4L2 через `fm_ctl.c`;
- сериализует данные в простую строку;
- отсылает уведомление в приложение.

Именно здесь возникает вся событийная модель проекта.

### 3. Потоки

В `fm_wrap.c` есть два фоновых потока:

- `interrupt_thread`
  Непрерывно читает буфер событий `TAVARUA_BUF_EVENTS` и прокидывает каждое событие в `process_radio_event()`.

- `fm_thread_rssi`
  Раз в секунду читает RSSI и отправляет `EVT_UPDATE_RSSI`.

То есть проект построен как небольшой event-driven daemon.

### 4. Жизненный цикл включения

Последовательность включения разбита на этапы:

- `fm_command_open()`
  - дергает `setprop hw.fm.mode normal`, `setprop hw.fm.version 0`, `setprop ctl.start fm_dl`;
  - при необходимости `insmod`-ит `radio-iris-transport.ko`;
  - ждет, пока `hw.fm.init == 1`;
  - открывает `/dev/radio0`.

- `fm_command_prepare()`
  - делает `VIDIOC_QUERYCAP`;
  - достает версию/идентификатор драйвера;
  - формирует путь/команду для патчей `fm_qsoc_patches`;
  - передает управление в `fm_command_setup_receiver()`.

- `fm_command_setup_receiver()`
  - в зависимости от transport/chip вызывает `fm_dl` или `fm_qsoc_patches`;
  - переводит приемник в состояние `RX`;
  - настраивает emphasis и spacing;
  - выбирает антенну;
  - запускает event thread и RSSI thread.

Это главный сценарий инициализации.

### 5. Командный API поверх низкого уровня

`fm_wrap.c` предоставляет более человекоориентированные операции:

- `fm_command_disable`
- `fm_command_tune_frequency`
- `fm_command_tune_frequency_by_delta`
- `fm_command_get_tuned_frequency`
- `fm_command_set_mute_mode`
- `fm_command_set_stereo_mode`
- `fm_command_setup_rds`

Это уже не "один ioctl", а законченные пользовательские действия.

### 6. Работа с RDS

`fm_command_setup_rds()`:

- включает/выключает RDS;
- задает стандарт RDS/RBDS;
- выставляет маски групп;
- для не-Rome чипов дополнительно включает `PSALL`.

Здесь видно, что код учитывает аппаратные различия между чипами и transport layer.

## `fm_ctl.c` / `fm_ctl.h`

Это самый низкий пользовательский слой над V4L2.

Если `fm_wrap.c` это orchestration, то `fm_ctl.c` это реальный драйверный adapter.

Что делает:

- хранит глобальный `fd_radio`;
- вызывает `open("/dev/radio0", O_RDWR | O_NONBLOCK)`;
- выставляет V4L2 private controls;
- вызывает `VIDIOC_S_CTRL`, `VIDIOC_G_CTRL`, `VIDIOC_S_TUNER`, `VIDIOC_G_TUNER`, `VIDIOC_S_FREQUENCY`, `VIDIOC_G_FREQUENCY`, `VIDIOC_S_HW_FREQ_SEEK`, `VIDIOC_DQBUF`.

Ключевые группы функций:

### 1. Управление базовыми параметрами

- `fm_receiver_set_state`
- `fm_receiver_set_emphasis`
- `fm_receiver_set_spacing`
- `fm_receiver_set_rds_state`
- `fm_receiver_set_band`
- `fm_receiver_set_rds_system`
- `fm_receiver_set_antenna`
- `fm_receiver_set_mute_mode`
- `fm_receiver_set_power_mode`
- `fm_receiver_set_stereo_mode`

### 2. Настройка частоты и поиск

- `fm_receiver_set_tuned_frequency`
- `fm_receiver_get_tuned_frequency`
- `fm_receiver_search_station_seek`
- `fm_receiver_search_station_list`
- `fm_receiver_cancel_search`
- `fm_receiver_toggle_af_jump`

### 3. Чтение сырых буферов драйвера

- `read_data_from_v4l2`

Это низкоуровневая функция получения содержимого буферов:

- events
- PS
- RT
- AF list
- search list

### 4. Разбор данных драйвера

- `extract_program_service`
  Извлекает PS, PI, PTY.

- `extract_radio_text`
  Извлекает RT.

- `extract_rds_af_list`
  Декодирует список alternative frequencies.

- `extract_search_station_list`
  Декодирует список найденных станций из буфера поиска.

- `fm_receiver_get_rssi`
  Возвращает уровень сигнала через `VIDIOC_G_TUNER`.

`fm_ctl.h` описывает модель состояния:

- `fm_rds_storage`
- `fm_tuner_state`
- `fm_current_storage`

Это полезный файл, если нужно быстро понять, какие данные проект вообще считает значимыми.

## `fmcommon.h`

Общий заголовок с типами и enum.

Здесь определены:

- базовые типы `uint32`, `uint16`, `boolean` и т.д.;
- debug-макросы;
- коэффициент `TUNE_MULT`;
- индексы V4L2 буферов `TAVARUA_BUF_*`;
- enum для band/emphasis/spacing/RDS/power/mute/search;
- структуры конфигурации:
  - `fm_config_data`
  - `fm_rds_options`
  - `fm_search_stations`
  - `fm_search_rds_stations`
  - `fm_search_list_stations`

Это главный словарь доменной модели проекта.

## `utils.c` / `utils.h`

Небольшой набор утилит:

- `wait(ms)`
- конвертация int в строку/hex-строку;
- пересчет между kHz и tuner frequency units;
- получение lower/upper frequency limit по band;
- `file_exists()`

Это чисто вспомогательный слой, но критичен для преобразования частот и startup-логики.

## `detector.c` / `detector.h`

Очень маленький слой определения аппаратной платформы через Android properties:

- `is_smd_transport_layer()`
- `is_rome_chip()`
- `is_cherokee_chip()`

Нужен для выбора правильного способа загрузки/инициализации FM firmware/patches.

То есть это аппаратно-зависимая развилка.

## Сборка и доставка бинарника

### `CMakeLists.txt`

Собирает executable `fmbin`.

Особенности:

- поддержка `armeabi-v7a` и `arm64-v8a`;
- release-флаги на размер и LTO;
- debug-флаги с `-DDEBUG`;
- линковка в один исполняемый файл, без внешних модулей проекта.

### `build.sh`

Скрипт сборки через Android NDK CMake toolchain:

- собирает `armeabi-v7a`;
- собирает `arm64-v8a`;
- затем вызывает `movelibs.sh`.

### `movelibs.sh`

Копирует собранные бинарники в assets Android-приложения:

- `../../app/src/main/assets/fmbin-armv7a`
- `../../app/src/main/assets/fmbin-aarch64`

Это показывает, как бинарник попадает в верхнее приложение: скорее всего приложение разворачивает этот executable из assets и запускает на устройстве.

## Как все работает по шагам

### Сценарий запуска

1. Приложение запускает `fmbin`.
2. `main.c` вызывает `init_server()`.
3. UDP-сервер начинает слушать `127.0.0.1:2112`.
4. Клиент шлет команду `init`.
5. `fm_command_open()` подготавливает систему и открывает `/dev/radio0`.
6. Клиент шлет `enable`.
7. `fm_command_prepare()` и `fm_command_setup_receiver()` переводят радио в `RX`.
8. Стартуют фоновые потоки событий и RSSI.
9. Дальше клиент может вызывать `setfreq`, `seekhw`, `searchhw`, `rds_toggle` и т.д.

### Сценарий событий

1. Драйвер генерирует событие в V4L2 private buffer.
2. `interrupt_thread` вычитывает буфер.
3. `process_radio_event()` определяет тип.
4. При необходимости дочитывает PS/RT/AF/search list.
5. Сериализует событие в строку.
6. Отправляет UDP datagram на `2113`.

Это полностью асинхронная модель.

## Что особенно важно в реализации

### 1. Проект не использует стандартный Android FM stack

Здесь нет:

- JNI;
- Binder;
- Java FM API;
- Qualcomm `qcom.fmradio`;
- `fm_hci`.

Вместо этого проект идет напрямую в V4L2 и system properties. Это делает стек проще, но сильнее привязывает его к конкретному драйверу и устройству.

### 2. Код заточен под Tavarua/Iris private controls

Очень много логики завязано на `V4L2_CID_PRIVATE_TAVARUA_*` и буферы `TAVARUA_BUF_*`.

Это значит:

- код аппаратно зависим;
- перенос на другой FM-драйвер будет дорогим;
- протоколы буферов PS/RT/AF/search list зашиты вручную.

### 3. Коммуникация с приложением предельно простая

Вместо сложного IPC используется localhost UDP с текстовыми командами и короткими строковыми ответами/событиями.

Плюсы:

- просто отлаживать;
- легко запускать отдельно;
- не нужен Java/native bridge.

Минусы:

- нет типобезопасности;
- слабый контракт формата;
- ручной парсинг строк;
- нет контроля за временем жизни выделенной памяти и консистентностью протокола.

### 4. `fm_wrap.c` это центр всего проекта

Если нужно понять только один файл, надо читать именно его. Там сходятся:

- system properties;
- инициализация;
- threads;
- state;
- события;
- отправка уведомлений.

### 5. `main.c` содержит достаточно хрупкий командный слой

Есть несколько характерных особенностей:

- `str_equals(x, y)` сравнивает только первые 5 символов;
- хэш endpoint считается, но реально почти не используется;
- в `handler_jump()` в `response->data` кладется указатель на локальный стековый буфер;
- в `api_handler()` есть утечки памяти на `args_parse()` и `malloc(sizeof(response_t))`;
- ответы в основном строковые и минималистичные.

Это не мешает понять архитектуру, но показывает, что слой протокола утилитарный и не очень строгий.

### 6. Есть следы незавершенной или проблемной функциональности

Самый явный пример: комментарий в `extract_search_station_list()` про нестабильную работу и проблемы с `lower_limit`.

То есть поиск списка станций был больной зоной проекта, и автор это явно зафиксировал прямо в коде.

## Назначение модулей в одном абзаце

- `main.c` принимает команды.
- `ctl_server.c` обеспечивает UDP IPC.
- `fm_wrap.c` управляет жизненным циклом FM и событиями.
- `fm_ctl.c` говорит с драйвером через V4L2.
- `detector.c` выбирает hardware-specific ветки.
- `utils.c` делает вспомогательные преобразования.
- `fmcommon.h` и `fm_ctl.h` описывают доменную модель и сигнатуры.

## С чего лучше читать код

Рекомендуемый порядок:

1. `main.c`
   Чтобы увидеть внешнее API.

2. `fm_wrap.c`
   Чтобы понять реальный жизненный цикл и событийную модель.

3. `fm_ctl.c`
   Чтобы увидеть, какими именно V4L2 вызовами это реализовано.

4. `ctl_server.c`
   Чтобы понять IPC-контракт с приложением.

5. `fmcommon.h`
   Чтобы быстро ориентироваться в enum и структурах.

## Краткий вывод

`native/qualcomm625` это компактный standalone FM daemon для Qualcomm 625, который напрямую управляет FM-драйвером через V4L2 и общается с приложением по localhost UDP. Архитектурно он состоит из трех главных частей:

- командный вход (`main.c` + `ctl_server.c`);
- FM orchestration (`fm_wrap.c`);
- драйверный адаптер (`fm_ctl.c`).

Это решение выглядит как прагматичная замена большому Android FM stack: меньше слоев, меньше зависимостей, но больше аппаратной привязки и больше ручной работы с протоколами и состоянием.
