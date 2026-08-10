/********************************************************************************
 * buzzer.h — пищалка на PB4
 *
 * Набор сигналов и их частоты/длительности взяты один в один из
 * github.com/Sacamoto84/CH32V003_MotorControl_Volume (User/buzzer.c),
 * чтобы звуки для пользователя совпадали с прошивкой на CH32V003.
 *
 * Отличие в реализации. Там tone1() блокирующий: крутит busy-wait весь
 * звук. Здесь так нельзя — стек BLE не переживёт полсекунды без ядра.
 * Поэтому тон формируется прерыванием TMR1, а длительности отсчитывает
 * Buzzer_Tick() из задачи. Вызовы buzzer_*() мгновенные, звук играется
 * фоном.
 *
 * Следствие: последовательные вызовы НЕ выстраиваются в очередь. Второй
 * вызов обрывает первый и начинает новую мелодию.
 *
 * TMR1 выбран потому, что его вывод (PA10) занят кварцем 32.768 и наружу
 * ничего не выводится. PWMX не подходит: R8_PWM_CLOCK_DIV и разрядность
 * периода общие для всех каналов, а PWM9 уже занят мотором.
 *
 * Громкость не регулируется — скважность жёстко 50 %. Аналога
 * tone1_vol(freq, dur, vol) нет: для него нужно второе прерывание
 * на период. Подбирай резистор последовательно с излучателем.
 *******************************************************************************/
#ifndef __BUZZER_H
#define __BUZZER_H

#include "board.h"

/* Нота. freq = 0 — пауза той же длительности. */
typedef struct
{
    uint16_t freq;      /* Гц */
    uint16_t ms;
} buz_note_t;

void    Buzzer_Init(void);
void    Buzzer_Tone(uint16_t freq, uint16_t ms);
void    Buzzer_Play(const buz_note_t *seq, uint8_t count);
void    Buzzer_Stop(void);
uint8_t Buzzer_IsBusy(void);
void    Buzzer_Tick(void);          /* каждые MOTOR_TICK_MS */

/* ---- сигналы, имена и тональности как в проекте на CH32V003 ---- */
void buzzer_ok(void);               /* 1000/80, 1500/80              */
void buzzer_error(void);            /* 400/150, 200/200              */
void buzzer_warning(void);          /* 800/150                       */
void buzzer_click(void);            /* 1200/40                       */
void buzzer_critical(void);         /* 3x 250/200                    */
void buzzer_beepboop(void);         /* 1000/60, 700/60               */
void buzzer_notify(void);           /* 1000/100, 800/150             */
void buzzer_ios_click(void);        /* 1800/25                       */
void buzzer_startup(void);          /* 800/120, 1000/120, 1300/160   */
void buzzer_shutdown(void);         /* 1200/150, 900/150, 600/180    */
void buzzer_charging(void);         /* 1500/80, 1800/150             */
void beep_Increment_Max(void);
void beep_Decrement_Min(void);
void beep_Save(void);               /* 1000/40, 1500/80              */

#endif /* __BUZZER_H */
