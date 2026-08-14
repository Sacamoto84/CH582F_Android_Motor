/********************************************************************************
 * ubutton.h — автомат кнопки, порт uButtonVirt.h из
 *             github.com/Sacamoto84/CH32V003_MotorControl_Volume
 *
 * Последовательность состояний сохранена, чтобы поведение кнопки для
 * пользователя совпадало с прошивкой на CH32V003. Отличие в реализации:
 * там C++ и millis(), здесь C и шаг тика. Из таймингов разошёлся только
 * UB_HOLD_TIME, см. комментарий к нему.
 *
 * Из оригинала выброшена «лестница» импульсов удержания (Step/WaitNextStep/
 * ReleaseStep) вместе со счётчиком шагов: она открывала четыре экрана
 * настройки, а настройки переехали в BLE. Всё остальное на месте.
 *
 * Автомат вызывается раз в MOTOR_TICK_MS (10 мс), что совпадает с дебаунсом
 * оригинала. События живут ровно один тик — проверять сразу после UB_Tick().
 *
 *   Idle ──нажали──> Press ──> WaitHold ──отпустили──> Click ──┐
 *                                  │                           │
 *                            5000 мс│                          ├──> Release
 *                                  ▼                           │
 *                                Hold ──> WaitRelease ──────────┘
 *                                          (ReleaseHold, клики сброшены)
 *
 *   Release ──> WaitClicks (500 мс) ──> Clicks ──> WaitTimeout (1000 мс) ──> Timeout
 *******************************************************************************/
#ifndef __UBUTTON_H
#define __UBUTTON_H

#include <stdint.h>

#define UB_DEB_TIME     10      /* дебаунс */
/* Единственное расхождение с оригиналом на CH32V003, где стояло 2000 мс.
 * Удержание здесь ведёт в ISP-загрузчик, и случайно попасть туда неприятно:
 * 5 секунд коротким нажатием не наберёшь. */
#define UB_HOLD_TIME    5000    /* до состояния «удержание» */
#define UB_CLICK_TIME   500     /* окно накопления кликов */
#define UB_TOUT_TIME    1000    /* до события «таймаут» */

typedef enum
{
    UB_IDLE = 0,
    UB_PRESS,           /* событие */
    UB_CLICK,           /* событие: отпущено до удержания */
    UB_WAIT_HOLD,
    UB_HOLD,            /* событие */
    UB_WAIT_RELEASE,
    UB_RELEASE_HOLD,    /* событие: отпущено после удержания */
    UB_RELEASE,         /* событие: отпущено в любом случае */
    UB_WAIT_CLICKS,
    UB_CLICKS,          /* событие: серия кликов досчитана */
    UB_WAIT_TIMEOUT,
    UB_TIMEOUT          /* событие */
} ub_state_t;

typedef struct
{
    ub_state_t state;
    uint32_t   tmr;         /* мс в текущем состоянии */
    uint16_t   deb;         /* мс стабильного уровня */
    uint8_t    raw;         /* последний сырой уровень */
    uint8_t    stable;      /* уровень после дебаунса */
    uint8_t    clicks;
} ubutton_t;

void UB_Init(ubutton_t *b);
void UB_Reset(ubutton_t *b);

/* Дёргать раз в dt_ms. pressed — сырое состояние контакта (1 = нажата). */
void UB_Tick(ubutton_t *b, uint8_t pressed, uint16_t dt_ms);

/* Событийные предикаты — истинны ровно один тик */
#define UB_Press(b)         ((b)->state == UB_PRESS)
#define UB_Click(b)         ((b)->state == UB_CLICK)
#define UB_Hold(b)          ((b)->state == UB_HOLD)
#define UB_ReleaseHold(b)   ((b)->state == UB_RELEASE_HOLD)
#define UB_Release(b)       ((b)->state == UB_RELEASE)
#define UB_HasClicks(b)     ((b)->state == UB_CLICKS)
#define UB_Timeout(b)       ((b)->state == UB_TIMEOUT)

#define UB_GetClicks(b)     ((b)->clicks)

/* Кнопка сейчас в работе — нажата или идёт накопление кликов */
#define UB_Busy(b)          ((b)->state != UB_IDLE)

#endif /* __UBUTTON_H */
