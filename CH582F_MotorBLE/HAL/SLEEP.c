/********************************** (C) COPYRIGHT *******************************
 * File Name          : SLEEP.c
 * Author             : WCH
 * Version            : V1.2
 * Date               : 2022/01/18
 * Description        : ˯�����ü����ʼ��
 *********************************************************************************
 * Copyright (c) 2021 Nanjing Qinheng Microelectronics Co., Ltd.
 * Attention: This software (modified or not) and binary are used for 
 * microcontroller manufactured by Nanjing Qinheng Microelectronics.
 *******************************************************************************/

/******************************************************************************/
/* ͷ�ļ����� */
#include "HAL.h"

/*******************************************************************************
 * @fn          CH58X_LowPower
 *
 * @brief       ����˯��
 *
 * @param   time    - ���ѵ�ʱ��㣨RTC����ֵ��
 *
 * @return      state.
 */
/*******************************************************************************
 * ПРАВКА ПРОЕКТА (не из штатного SDK).
 *
 * LowPower_Sleep() останавливает PLL и HSE. Всё, что тактируется от Fsys,
 * замирает: счётчик PWMX застывает и выход мотора фиксируется на текущем
 * уровне, прерывания TMR1 не приходят и тон пищалки рвётся.
 *
 * Стек усыпляет чип в промежутках между своими событиями — при нашем тике
 * задачи это куски по ~10 мс. Для мотора это недопустимо: «застывание
 * в единице» держит ключ полностью открытым.
 *
 * Поэтому приложение может запретить автосон. Слабая заглушка разрешает
 * всё, сильную версию даёт motor_task.c.
 ******************************************************************************/
__attribute__((weak)) uint8_t App_SleepAllowed(void)
{
    return 1;
}

uint32_t CH58X_LowPower(uint32_t time)
{
#if(defined(HAL_SLEEP)) && (HAL_SLEEP == TRUE)
    uint32_t time_sleep, time_curr;
    unsigned long irq_status;

    if(!App_SleepAllowed()) return 2;   /* 2 — «не спали», штатный код возврата */

    SYS_DisableAllIrq(&irq_status);
    time_curr = RTC_GetCycle32k();
    // ���˯��ʱ��
    if (time < time_curr) {
        time_sleep = time + (RTC_TIMER_MAX_VALUE - time_curr);
    } else {
        time_sleep = time - time_curr;
    }
    
    // ��˯��ʱ��С����С˯��ʱ���������˯��ʱ�䣬��˯��
    if ((time_sleep < SLEEP_RTC_MIN_TIME) || 
        (time_sleep > SLEEP_RTC_MAX_TIME)) {
        SYS_RecoverIrq(irq_status);
        return 2;
    }

    RTC_SetTignTime(time);
    SYS_RecoverIrq(irq_status);
  #if(DEBUG == Debug_UART1) // ʹ���������������ӡ��Ϣ��Ҫ�޸����д���
    while((R8_UART1_LSR & RB_LSR_TX_ALL_EMP) == 0)
    {
        __nop();
    }
  #endif
    // LOW POWER-sleepģʽ
    if(!RTCTigFlag)
    {
        LowPower_Sleep(RB_PWR_RAM2K | RB_PWR_RAM30K | RB_PWR_EXTEND);
        if(RTCTigFlag) // ע�����ʹ����RTC����Ļ��ѷ�ʽ����Ҫע���ʱ32M����δ�ȶ�
        {
            time += WAKE_UP_RTC_MAX_TIME;
            if(time > 0xA8C00000)
            {
                time -= 0xA8C00000;
            }
            RTC_SetTignTime(time);
            LowPower_Idle();
        }
        HSECFG_Current(HSE_RCur_100); // ��Ϊ�����(�͹��ĺ�����������HSEƫ�õ���)
    }
    else
    {
        return 3;
    }
#endif
    return 0;
}

/*******************************************************************************
 * @fn      HAL_SleepInit
 *
 * @brief   ����˯�߻��ѵķ�ʽ   - RTC���ѣ�����ģʽ
 *
 * @param   None.
 *
 * @return  None.
 */
void HAL_SleepInit(void)
{
#if(defined(HAL_SLEEP)) && (HAL_SLEEP == TRUE)
    sys_safe_access_enable();
    R8_SLP_WAKE_CTRL |= RB_SLP_RTC_WAKE; // RTC����
    sys_safe_access_disable();              //
    sys_safe_access_enable();
    R8_RTC_MODE_CTRL |= RB_RTC_TRIG_EN;  // ����ģʽ
    sys_safe_access_disable();              //
    PFIC_EnableIRQ(RTC_IRQn);
#endif
}
