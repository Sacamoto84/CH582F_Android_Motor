/********************************************************************************
 * CONFIG.h — конфигурация стека BLE
 *
 * Замена штатного HAL/include/config.h из CH58xBLE EVT. Оригинал удалён из
 * HAL/include: Windows не различает регистр в именах файлов, и
 * #include "CONFIG.h" из HAL.h резолвился бы то сюда, то туда — в зависимости
 * от порядка путей поиска. Один файл вместо двух снимает вопрос.
 *
 * Ниже полный список опций стека с расшифровкой (перевод комментариев WCH),
 * значениями по умолчанию и тем, что выставлено в этом проекте.
 *******************************************************************************/
#ifndef __CONFIG_H
#define __CONFIG_H

#define ID_CH583                            0x83
#define CHIP_ID                             ID_CH583

#ifdef CH58xBLE_ROM
  #include "CH58xBLE_ROM.H"
#else
  #include "CH58xBLE_LIB.H"
#endif

#include "CH58x_common.h"

/*==============================================================================
 * СПРАВОЧНИК ОПЦИЙ
 *
 * WCH рекомендует задавать их не здесь, а в препроцессоре настроек проекта.
 * Здесь всё под #ifndef, поэтому -D из .cproject всегда перебивает файл.
 *
 * MAC
 *   BLE_MAC             FALSE — брать заводской MAC чипа (GetMACAddress).
 *                       TRUE  — свой, массив MacAddr[6] в main.c.
 * DCDC
 *   DCDC_ENABLE         Встроенный импульсный преобразователь. Экономит около
 *                       трети тока, но требует дросселя между VSW и VDCID.
 * СОН
 *   HAL_SLEEP           Автоматический сон стека между событиями BLE
 *                       (cfg.sleepCB = CH58X_LowPower).
 *   SLEEP_RTC_MIN_TIME  Минимальная длительность сна вне режима Idle,
 *                       в периодах RTC. Короче — не засыпать, не окупится.
 *   SLEEP_RTC_MAX_TIME  Максимальная длительность сна, в периодах RTC.
 *   WAKE_UP_RTC_MAX_TIME
 *                       Время выхода кварца 32 МГц на режим, в периодах RTC.
 *                       WCH даёт: сон и Shutdown — 45, Halt — 45, Idle — 5.
 *                       Мало — соединение будет рваться. Нужен запас.
 * ТЕМПЕРАТУРА
 *   TEM_SAMPLE          Подстройка RF при уходе температуры (порог ~7 °C).
 *                       Однократная калибровка занимает меньше 10 мс.
 * КАЛИБРОВКА
 *   BLE_CALIBRATION_ENABLE  Периодическая калибровка регистров RF.
 *   BLE_CALIBRATION_PERIOD  Её период в мс.
 * SNV
 *   BLE_SNV             Энергонезависимое хранение информации о спаривании.
 *   BLE_SNV_ADDR        Адрес в DataFlash (смещение от 0x70000).
 *   BLE_SNV_BLOCK       Размер блока.
 *   BLE_SNV_NUM         Количество блоков.
 * RTC
 *   CLK_OSC32K          0 — внешний кварц 32768 Гц
 *                       1 — внутренний RC 32000 Гц (по умолчанию)
 *                       2 — внутренний RC 32768 Гц
 *                       Для роли Central внешний кварц ОБЯЗАТЕЛЕН.
 * ПАМЯТЬ
 *   BLE_MEMHEAP_SIZE    Куча стека. Меньше 6 КБ нельзя — BLE_LibInit вернёт
 *                       ошибку и CH58X_BLEInit зависнет.
 * ДАННЫЕ
 *   BLE_BUFF_MAX_LEN    Максимальная длина пакета на соединение.
 *                       27 соответствует ATT_MTU = 23. Диапазон 27…516.
 *   BLE_BUFF_NUM        Сколько пакетов буферизует контроллер.
 *   BLE_TX_NUM_EVENT    Сколько пакетов можно отправить за одно
 *                       connection event.
 *   BLE_TX_POWER        Мощность передатчика. Доступно:
 *                       LL_TX_POWEER_MINUS_16_DBM  …  _MINUS_1_DBM,
 *                       LL_TX_POWEER_0_DBM,
 *                       LL_TX_POWEER_1_DBM … _6_DBM.
 * МНОГОСВЯЗНОСТЬ
 *   PERIPHERAL_MAX_CONNECTION  Сколько соединений в роли slave.
 *   CENTRAL_MAX_CONNECTION     Сколько в роли master.
 *                       Складываются в cfg.ConnectNumber, каждое соединение
 *                       ест кучу BLE_MEMHEAP_SIZE.
 *============================================================================*/

/*==============================================================================
 * ЗНАЧЕНИЯ ЭТОГО ПРОЕКТА
 *============================================================================*/

/* --- отличается от штатного ------------------------------------------------ */

/* Штатное TRUE вернули: конвейера измерения ЭДС на прерываниях АЦП больше
 * нет, поэтому блокирующий HAL_GetInterTempValue() внутри стека никому не
 * мешает. Наши замеры потенциометра и VBAT сами блокирующие и каждый раз
 * заново выставляют канал и PGA. */
#ifndef TEM_SAMPLE
#define TEM_SAMPLE                          TRUE
#endif

/* На WeAct Core Board V1.0 дроссель L1 22 мкГн между VSW и VDCID разведён,
 * VDCID зашунтирован 2.2 мкФ, VINTA — 1 мкФ. Всё как требует даташит. */
#ifndef DCDC_ENABLE
#define DCDC_ENABLE                         TRUE
#endif

/* На плате стоит кварц X1 32.768 кГц ±10ppm на PA10/PA11 — он точнее
 * внутреннего RC. Продублировано в препроцессоре .cproject, как советует WCH. */
#ifndef CLK_OSC32K
#define CLK_OSC32K                          0
#endif

/* Автоматический сон стека между событиями BLE (штатно FALSE) */
#ifndef HAL_SLEEP
#define HAL_SLEEP                           FALSE
#endif

/* Только роль Peripheral, одно соединение. Центральная роль не нужна —
 * это освобождает кучу и снимает требование внешнего 32K (у нас он всё
 * равно есть). */
#ifndef PERIPHERAL_MAX_CONNECTION
#define PERIPHERAL_MAX_CONNECTION           1
#endif
#ifndef CENTRAL_MAX_CONNECTION
#define CENTRAL_MAX_CONNECTION              0
#endif

/* WCH даёт 1400 мкс (~45 периодов RTC). Берём с запасом: недобор выливается
 * в срывы соединения, перебор — только в чуть больший ток. */
#ifndef WAKE_UP_RTC_MAX_TIME
#define WAKE_UP_RTC_MAX_TIME                US_TO_RTC(1600)
#endif

/* Кнопку опрашиваем сами в motor_task, поллер HAL не нужен.
 * Светодиод дёргаем напрямую. */
#ifndef HAL_KEY
#define HAL_KEY                             FALSE
#endif
#ifndef HAL_LED
#define HAL_LED                             FALSE
#endif

/* --- как в штатном --------------------------------------------------------- */

#ifndef BLE_MAC
#define BLE_MAC                             FALSE
#endif

/* Калибровка регистров RF АЦП не трогает — оставляем включённой */
#ifndef BLE_CALIBRATION_ENABLE
#define BLE_CALIBRATION_ENABLE              TRUE
#endif
#ifndef BLE_CALIBRATION_PERIOD
#define BLE_CALIBRATION_PERIOD              120000
#endif

#ifndef SLEEP_RTC_MIN_TIME
#define SLEEP_RTC_MIN_TIME                  US_TO_RTC(1000)
#endif
#ifndef SLEEP_RTC_MAX_TIME
#define SLEEP_RTC_MAX_TIME                  MS_TO_RTC(RTC_TO_MS(RTC_TIMER_MAX_VALUE) - 1000 * 60 * 60)
#endif

/* SNV занимает смещение 0x7E00 в DataFlash, 256 байт. Наши настройки лежат
 * на 0x1000/0x1100 — не пересекаются. CH58X_BLEInit() проверяет это сам
 * и виснет при конфликте. */
#ifndef BLE_SNV
#define BLE_SNV                             TRUE
#endif
#ifndef BLE_SNV_ADDR
#define BLE_SNV_ADDR                        (0x77E00 - FLASH_ROM_MAX_SIZE)
#endif
#ifndef BLE_SNV_BLOCK
#define BLE_SNV_BLOCK                       256
#endif
#ifndef BLE_SNV_NUM
#define BLE_SNV_NUM                         1
#endif

#ifndef BLE_MEMHEAP_SIZE
#define BLE_MEMHEAP_SIZE                    (1024 * 6)
#endif

/* Наши пакеты не длиннее 14 байт, увеличивать MTU не нужно */
#ifndef BLE_BUFF_MAX_LEN
#define BLE_BUFF_MAX_LEN                    27
#endif
#ifndef BLE_BUFF_NUM
#define BLE_BUFF_NUM                        5
#endif
#ifndef BLE_TX_NUM_EVENT
#define BLE_TX_NUM_EVENT                    1
#endif
#ifndef BLE_TX_POWER
#define BLE_TX_POWER                        LL_TX_POWEER_0_DBM
#endif

extern uint32_t MEM_BUF[BLE_MEMHEAP_SIZE / 4];
#if(defined(BLE_MAC)) && (BLE_MAC == TRUE)
extern const uint8_t MacAddr[6];
#endif

#endif /* __CONFIG_H */
