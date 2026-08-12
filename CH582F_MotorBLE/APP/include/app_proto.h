/********************************************************************************
 * app_proto.h — протокол BLE между прошивкой и приложением
 *
 * Два характеристика вместо десятка: команда (write) и данные (notify).
 * Пакеты укладываются в 20 байт, поэтому обмен работает при MTU по умолчанию
 * (23) и не требует его согласования.
 *******************************************************************************/
#ifndef __APP_PROTO_H
#define __APP_PROTO_H

#include <stdint.h>

/* --------------------------------------------------------------- пакет CMD */
/* Клиент пишет 3 байта: [op][lo][hi]
 *   op  < 0x80  — записать параметр с этим id, значение uint16 (lo,hi)
 *   op == 0x80  — прочитать параметр, id в младшем байте; ответ придёт в notify
 *   op == 0x81  — команда, код в младшем байте
 *   op == 0x82  — выгрузить все параметры серией notify
 */
#define OP_SET_PARAM        0x00    /* op = id параметра */
#define OP_GET_PARAM        0x80
#define OP_COMMAND          0x81
#define OP_DUMP_ALL         0x82

/* ------------------------------------------------------------- id параметров */
typedef enum
{
    PID_PWM_MIN = 0,     /* промилле, скважность при ручке в минимуме         */
    PID_PWM_MAX,         /* промилле, скважность при ручке в максимуме        */
    PID_PWM_FREQ_RUN,    /* Гц, рабочая частота ШИМ                           */
    PID_PWM_FREQ_BOOST,  /* Гц, частота на время рывка                        */
    PID_BOOST_EN,        /* 0/1                                               */
    PID_BOOST_POWER,     /* промилле, АБСОЛЮТНАЯ скважность рывка при старте  */
    PID_BOOST_TIME,      /* мс, длительность рывка                            */
    PID_MAX_RUN_S,       /* с, авто-стоп; 0 = без ограничения                 */
    PID_VBAT_SCALE_Q12,  /* масштаб АЦП -> мВ для канала батареи, Q12         */
    PID_VDROP_LEVEL,     /* сброс по просадке VDD33: 0 выкл, 1=1.9 2=2.1       */
                         /* 3=2.3 4=2.5 В                                      */
    PID_SLEEP_TOUT_S,    /* с бездействия до ухода в сон; 0 = не спать        */
    PID_VBAT_MIN_MV,     /* мВ, ниже порога пуск запрещён; 0 = защиты нет     */
    PID__COUNT
} param_id_t;

/* ----------------------------------------------------------------- команды */
#define CMD_MOTOR_STOP      0
#define CMD_MOTOR_START     1
#define CMD_SAVE            2   /* записать настройки в DataFlash */
#define CMD_FACTORY_RESET   3
#define CMD_SLEEP           4
#define CMD_TELEMETRY_ON    5
#define CMD_TELEMETRY_OFF   6

/* -------------------------------------------------------- пакеты в notify */
#define NTF_TELEMETRY       0x00
#define NTF_PARAM           0x01
#define NTF_ACK             0x02

/* Причина последней остановки — едет в телеметрии */
#define STOPREASON_NONE     0
#define STOPREASON_USER     1
#define STOPREASON_TIMEOUT  2   /* сработал max_run_s */
#define STOPREASON_LOWBAT   3   /* пуск не состоялся: банка ниже vbat_min_mv */

/* Телеметрия, 10 байт, little-endian */
typedef struct __attribute__((packed))
{
    uint8_t  type;        /* NTF_TELEMETRY */
    uint8_t  state;       /* motor_state_t */
    uint8_t  stop_reason;
    uint8_t  pwm_pct;     /* проценты, для наглядности */
    uint16_t pwm_pm;      /* промилле, точное значение */
    uint16_t pot_raw;     /* 0..4095 */
    uint16_t vbat_mv;
} telemetry_t;

/* Ответ на чтение параметра / дамп, 4 байта */
typedef struct __attribute__((packed))
{
    uint8_t  type;        /* NTF_PARAM */
    uint8_t  id;
    uint16_t value;
} param_ntf_t;

/* Квитанция на команду, 3 байта */
typedef struct __attribute__((packed))
{
    uint8_t type;         /* NTF_ACK */
    uint8_t cmd;
    uint8_t status;       /* 0 = ok */
} ack_ntf_t;

#endif /* __APP_PROTO_H */
