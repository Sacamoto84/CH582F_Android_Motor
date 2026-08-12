/********************************************************************************
 * pwm_motor.c — ШИМ мотора на PWMX, канал PWM9, выход PB7
 *
 * Fout = Fsys / R8_PWM_CLOCK_DIV / 256,  делитель 1..255
 *      → 919 Гц … 234 кГц при Fsys = 60 МГц
 * Скважность 8 бит: R8_PWM9_DATA = 0..255.
 *******************************************************************************/
#include "pwm_motor.h"

volatile uint8_t g_motor_pwm_duty = 0;      /* командная скважность, 0..255 */

static uint16_t s_permille = 0;
static uint8_t  s_div      = 15;            /* 60 МГц / 15 / 256 = 15.6 кГц */
static uint8_t  s_enabled  = 0;

/*********************************************************************
 * @fn      MotorPwm_Init
 *
 * @brief   Пин в выход 20 мА, PWMX в режим 256 тактов на период.
 *          Выход остаётся выключенным (ключ закрыт).
 */
void MotorPwm_Init(void)
{
    /* 20 мА, а не 5: заряд затвора AO3400 ~8 нК, на 5 мА фронт растянется
     * до ~1.6 мкс, это 2.6 % периода на 16 кГц — лишний нагрев ключа. */
    GPIOB_ModeCfg(MOTOR_PWM_PIN, GPIO_ModeOut_PP_20mA);
    GPIOB_ResetBits(MOTOR_PWM_PIN);

    /* PWM9 сидит на PB7 при RB_PIN_PWMX = 0 (значение по умолчанию).
     * Единица увела бы PWM4/5/7/8/9 на PA6/PA7/PB1/PB2/PB3, которых
     * в QFN28 нет. */
    GPIOPinRemap(DISABLE, RB_PIN_PWMX);

    PWMX_CLKCfg(s_div);
    PWMX_CycleCfg(PWMX_Cycle_256);

    g_motor_pwm_duty = 0;
    s_permille = 0;
    MOTOR_PWM_DATA_REG = 0;

    s_enabled = 0;
}

/*********************************************************************
 * @fn      MotorPwm_SetFreq
 *
 * @brief   Смена частоты. Скважность в процентах сохраняется, потому что
 *          в PWMX период фиксирован (256), а меняется только делитель.
 */
void MotorPwm_SetFreq(uint32_t hz)
{
    uint32_t div;

    if(hz < MOTOR_PWM_HZ_MIN) hz = MOTOR_PWM_HZ_MIN;
    if(hz > MOTOR_PWM_HZ_MAX) hz = MOTOR_PWM_HZ_MAX;

    div = SYS_CLK_HZ / (hz * MOTOR_PWM_NCYC);
    if(div < 1)   div = 1;
    if(div > 255) div = 255;

    s_div = (uint8_t)div;
    PWMX_CLKCfg(s_div);
}

/*********************************************************************
 * @fn      MotorPwm_GetFreq
 */
uint32_t MotorPwm_GetFreq(void)
{
    return SYS_CLK_HZ / ((uint32_t)s_div * MOTOR_PWM_NCYC);
}

/*********************************************************************
 * @fn      MotorPwm_SetDutyPermille
 *
 * @brief   Скважность 0..1000 промилле → 0..255 тактов.
 */
void MotorPwm_SetDutyPermille(uint16_t pm)
{
    uint8_t d;

    if(pm > 1000) pm = 1000;
    s_permille = pm;

    /* 255, а не 256: DATA = 255 из 256 тактов — максимум, что даёт железо */
    d = (uint8_t)(((uint32_t)pm * 255U) / 1000U);

    g_motor_pwm_duty = d;

    /* Если конвейер измерения ЭДС сейчас держит ШИМ снятым, регистр он
     * восстановит сам из g_motor_pwm_duty. Пишем напрямую только когда
     * выход активен. */
    if(s_enabled)
    {
        MOTOR_PWM_DATA_REG = d;
    }
}

uint16_t MotorPwm_GetDutyPermille(void)
{
    return s_permille;
}

/*********************************************************************
 * @fn      MotorPwm_Enable
 */
void MotorPwm_Enable(void)
{
    if(s_enabled) return;

    /* High_Level = по умолчанию низкий, активный высокий: DATA задаёт
     * длительность открытого состояния ключа. */
    PWMX_ACTOUT(MOTOR_PWM_CH, g_motor_pwm_duty, High_Level, ENABLE);
    s_enabled = 1;
}

/*********************************************************************
 * @fn      MotorPwm_Disable
 *
 * @brief   Снять выход и жёстко посадить пин в ноль — ключ должен быть
 *          закрыт, а не оставлен в третьем состоянии.
 */
void MotorPwm_Disable(void)
{
    MOTOR_PWM_DATA_REG = 0;
    PWMX_ACTOUT(MOTOR_PWM_CH, 0, High_Level, DISABLE);
    GPIOB_ModeCfg(MOTOR_PWM_PIN, GPIO_ModeOut_PP_20mA);
    GPIOB_ResetBits(MOTOR_PWM_PIN);
    s_enabled = 0;
}

uint8_t MotorPwm_IsEnabled(void)
{
    return s_enabled;
}
