/********************************************************************************
 * pwm_motor.h — ШИМ мотора на PWMX, канал PWM9 (PB7)
 *
 * Таймерный ШИМ (26 бит, любая частота) недоступен: все выходы TMR в QFN28
 * заняты — см. board.h. У PWMX счётчик 8 бит и делитель тактовой 8 бит,
 * поэтому диапазон 919 Гц … 234 кГц, скважность 0..255.
 *******************************************************************************/
#ifndef __PWM_MOTOR_H
#define __PWM_MOTOR_H

#include "board.h"

/* Текущая скважность в тактах, 0..255 */
extern volatile uint8_t g_motor_pwm_duty;

void     MotorPwm_Init(void);
void     MotorPwm_SetFreq(uint32_t hz);
uint32_t MotorPwm_GetFreq(void);
void     MotorPwm_SetDutyPermille(uint16_t pm);
uint16_t MotorPwm_GetDutyPermille(void);
void     MotorPwm_Enable(void);
void     MotorPwm_Disable(void);
uint8_t  MotorPwm_IsEnabled(void);

#endif /* __PWM_MOTOR_H */
