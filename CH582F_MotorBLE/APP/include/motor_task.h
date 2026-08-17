/********************************************************************************
 * motor_task.h — задача TMOS: автомат мотора, буст, потенциометр, кнопка
 *
 * Разомкнутый контур, как в CH32V003_MotorControl_Volume: ручка задаёт
 * мощность напрямую. Нагрузка у помпы постоянная, поэтому поддержание
 * оборотов по противо-ЭДС не нужно — вместе с ним ушли два делителя,
 * конвейер измерения на прерываниях и десяток параметров калибровки.
 *******************************************************************************/
#ifndef __MOTOR_TASK_H
#define __MOTOR_TASK_H

#include "CH58x_common.h"
#include "app_proto.h"

/* События задачи */
#define MOTOR_EVT_TICK          0x0001      /* 10 мс: АЦП, автомат, кнопка */
#define MOTOR_EVT_TELEMETRY     0x0002      /* отправка телеметрии в BLE   */

#define MOTOR_TICK_MS           10
#define MOTOR_TELEMETRY_MS      200
#define VBAT_PERIOD_MS          2000    /* как часто мерить аккумулятор */

typedef enum
{
    M_IDLE = 0,     /* мотор выключен                                */
    M_BOOST,        /* стартовый рывок повышенной скважностью         */
    M_RUN           /* рабочий режим, скважность с потенциометра      */
} motor_state_t;

extern uint8_t Motor_TaskID;

void     Motor_Init(void);
uint16_t Motor_ProcessEvent(uint8_t task_id, uint16_t events);

/* Команды — неблокирующие, исполняются на ближайшем тике.
 * Motor_Start возвращает 0, если пуск отклонён отсечкой по разряду. */
uint8_t Motor_Start(void);
void    Motor_Stop(void);
void    Motor_Toggle(void);

uint8_t       Motor_IsStopped(void);
motor_state_t Motor_GetState(void);

/* Снимок для телеметрии */
void   Motor_GetTelemetry(telemetry_t *t);

/* Сколько миллисекунд не было активности (для ухода в сон) */
uint32_t Motor_IdleMs(void);
void     Motor_KickIdle(void);

/* Уснуть по явной команде, не дожидаясь таймаута простоя */
void     Motor_ForceSleep(void);

/* Вызывать из main() при сбросе типа GRWSM — вышли из глубокого сна.
 * Запускает помпу и проглатывает нажатие, которое разбудило чип. */
void     Motor_WokeByKey(void);

#endif /* __MOTOR_TASK_H */
