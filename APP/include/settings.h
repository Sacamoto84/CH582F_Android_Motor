/********************************************************************************
 * settings.h — параметры в DataFlash
 *
 * DataFlash CH582F: 0x70000..0x77FFF, 32 КБ. Отдельная от CodeFlash область
 * энергонезависимой памяти, прошивальщик её не трогает. Главное отличие от
 * CodeFlash — гранулярность: стирать можно страницами по 256 байт вместо
 * блоков по 4096, писать и читать — с точностью до байта.
 *
 * Адреса в EEPROM_* задаются СМЕЩЕНИЕМ от 0x70000, не абсолютные.
 *
 * Ресурс 100 000 циклов перезаписи при 5..45 °C, хранение 20 лет
 * (даташит, табл. 20-9).
 *
 * Стек BLE держит SNV (ключи спаривания) по смещению BLE_SNV_ADDR = 0x7E00,
 * 256 байт. CH58X_BLEInit() проверяет пересечение и виснет при конфликте,
 * поэтому свои данные кладём заведомо ниже — на 0x1000.
 *
 * Два слота по 256 байт с чередованием и счётчиком записей: питание может
 * пропасть в момент стирания страницы, и один целый слот всегда остаётся.
 *******************************************************************************/
#ifndef __SETTINGS_H
#define __SETTINGS_H

#include <stdint.h>
#include "app_proto.h"

#define SETTINGS_SLOT_A     0x1000
#define SETTINGS_SLOT_B     0x1100
#define SETTINGS_MAGIC      0x4D50      /* 'PM' — pump */
#define SETTINGS_VERSION    7           /* v7: + vdrop_level */

typedef struct __attribute__((packed))
{
    uint16_t magic;
    uint16_t version;
    uint32_t seq;                /* больше — свежее */

    uint16_t pwm_min;            /* промилле */
    uint16_t pwm_max;            /* промилле */
    uint16_t pwm_freq_run;       /* Гц   */
    uint16_t pwm_freq_boost;     /* Гц   */
    uint16_t boost_en;
    uint16_t boost_power;        /* промилле, абсолютная скважность рывка */
    uint16_t boost_time;         /* мс   */
    uint16_t max_run_s;          /* авто-стоп, 0 = выключен */
    uint16_t vbat_scale_q12;     /* масштаб АЦП -> мВ, калибруется */
    uint16_t vdrop_level;        /* сброс по просадке VDD33, 0 = выкл */
    uint16_t sleep_tout_s;       /* 0 — не засыпать */
    uint16_t boot_grace_s;       /* после сброса сон запрещён столько секунд */

    uint16_t crc;
} settings_t;

extern settings_t g_set;

void     Settings_Load(void);          /* прочитать или залить дефолты */
void     Settings_Defaults(void);
uint8_t  Settings_SaveNow(void);       /* блокирует на единицы мс, мотор должен стоять */
void     Settings_RequestSave(void);   /* отложенная запись */
void     Settings_Tick(void);          /* дёргать из задачи; сам решит, когда писать */

/* Доступ по id для BLE */
uint16_t Settings_Get(uint8_t id);
uint8_t  Settings_Set(uint8_t id, uint16_t value);   /* 1 — принято */

#endif /* __SETTINGS_H */
