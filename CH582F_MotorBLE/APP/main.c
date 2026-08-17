/********************************************************************************
 * main.c — CH582F: управление коллекторным мотором + BLE
 *
 * Порядок регистрации задач в TMOS определяет приоритет: чем меньше ID,
 * тем выше. Задачи стека обязаны быть первыми, поэтому
 * CH58X_BLEInit() → HAL_Init() → GAPRole_PeripheralInit() → своё.
 *******************************************************************************/
#include "CONFIG.h"
#include "HAL.h"
#include "board.h"
#include "settings.h"
#include "pwm_motor.h"
#include "buzzer.h"
#include "motor_task.h"
#include "peripheral.h"

__attribute__((aligned(4))) uint32_t MEM_BUF[BLE_MEMHEAP_SIZE / 4];

/* Код причины сброса из R8_RESET_STATUS. Индексы совпадают с таблицей
 * имён в main(): 001 — «подача питания ИЛИ LVR». */
#define RESET_FLAG_RPOR     0x01
#define RESET_FLAG_GRWSM    0x05

static uint8_t s_reset_flag;

#if(defined(BLE_MAC)) && (BLE_MAC == TRUE)
const uint8_t MacAddr[6] = {0x84, 0xC2, 0xE4, 0x03, 0x02, 0x02};
#endif

/*********************************************************************
 * @fn      Board_Init
 *
 * @brief   Пины. Аналоговые входы отдельно: GPIOA_ModeCfg() не выключает
 *          на них цифровой приёмник, это делает GPIOAGPPCfg().
 */
static void Board_Init(void)
{
    /* Аналоговые входы: PA12 — потенциометр, PA14 — делитель аккумулятора */
    GPIOA_ModeCfg(PIN_POT | PIN_VBAT, GPIO_ModeIN_Floating);
    GPIOAGPPCfg(ENABLE, ANALOG_IE_MASK);

    /* Кнопка K2 «BOOT» на PB22. Прерывание PB22/PB23 сидит на битах 8/9
     * регистра R16_PB_INT_EN вместе с PB8/PB9 — без ремапа INTX эти биты
     * относятся к PB8/PB9, и пробуждение не сработает. */
    GPIOB_ModeCfg(PIN_KEY, GPIO_ModeIN_PU);
    GPIOPinRemap(ENABLE, RB_PIN_INTX);

    /* Питание потенциометра — PA13, гасится во сне */
    GPIOA_ModeCfg(PIN_POT_PWR, GPIO_ModeOut_PP_5mA);
    POT_PWR_ON();

    /* Ключ ветки делителя аккумулятора — PA15. Выключён = вход, включается
     * только на время замера, поэтому в покое ветка не потребляет. */
    VBAT_PWR_OFF();

    /* Синий светодиод платы на PA8, активный низкий */
    GPIOA_ModeCfg(PIN_LED, GPIO_ModeOut_PP_5mA);
    LED_OFF();

    /* Пищалка: до Settings_Load(), потому что при чистом DataFlash тот
     * сразу пишет дефолты и играет сигнал сохранения. */
    Buzzer_Init();

    /* Свободные ножки — под опору, см. комментарий к UNUSED_PINS_* в board.h.
     * Настройка живёт и во сне: домен ввода-вывода в Shutdown остаётся под
     * питанием, ножки держат последнее состояние — ровно поэтому Board_Sleep
     * гасит их вручную. */
    GPIOA_ModeCfg(UNUSED_PINS_A_PD, GPIO_ModeIN_PD);
    GPIOB_ModeCfg(UNUSED_PINS_B_PD, GPIO_ModeIN_PD);
}

/*********************************************************************
 * @fn      Board_Sleep
 *
 * @brief   Глубокий сон. Мотор выключен, потенциометр (PA13) и ветка
 *          делителя аккумулятора (PA15) обесточены. Пробуждение кнопкой PB22.
 *
 *          Выбран Shutdown, а не Sleep. Причина не в токе (0.2 мкА против
 *          0.7), а в том, что после Shutdown PMU делает GRWSM-сброс и
 *          программа стартует с main() — восстанавливать руками нечего.
 *          Это та же модель, что PWR_EnterSTANDBYMode на CH32V003.
 *
 *          В Sleep пришлось бы вручную поднимать PLL/HSE (останавливаются),
 *          заново инициализировать АЦП (он на аналоговом LDO от VINTA, а не
 *          в домене PWR_CORE) и следить за конфигурацией RF. Регистры TMR и
 *          PWMX при этом уцелели бы — они оба в домене PWR_CORE (рис. 5-1
 *          даташита), так что выбор модуля ШИМ на сон никак не влияет.
 *
 *          Состояние терять не жалко: настройки лежат в DataFlash.
 *          Функция НЕ ВОЗВРАЩАЕТСЯ.
 */
void Board_Sleep(void)
{
    MotorPwm_Disable();
    Buzzer_Stop();          /* иначе ножка может остаться в единице */
    POT_PWR_OFF();
    VBAT_PWR_OFF();
    LED_OFF();

    ADC_DisablePower();

    /* Реклама уже погашена задачей мотора за 200 мс до этого вызова —
     * стеку нужно время доработать свои RTC-события, иначе он разбудит
     * чип сразу же. */

    PWR_PeriphWakeUpCfg(ENABLE, RB_SLP_GPIO_WAKE, Long_Delay);
    GPIOB_ITModeCfg(PIN_KEY, GPIO_ITMode_FallEdge);
    KEY_CLEAR_IT();
    PFIC_EnableIRQ(GPIO_B_IRQn);

#ifdef DEBUG
    PRINT("shutdown\n");
    while((R8_UART1_LSR & RB_LSR_TX_ALL_EMP) == 0);   /* дать строке уйти */
#endif

    /* RAM не сохраняем — всё нужное в DataFlash, а каждый включённый
     * домен добавляет ток. DC-DC внутри выключится принудительно,
     * main() включит его заново после сброса. */
    LowPower_Shutdown(0);

    /* сюда не попадаем: LowPower_Shutdown заканчивается сбросом */
}

__INTERRUPT
__HIGH_CODE
void GPIOB_IRQHandler(void)
{
    KEY_CLEAR_IT();
}

/*********************************************************************
 * @fn      NMI_Handler
 *
 * @brief   Просадка питания: сработал детектор VDD33 (см. vdrop_monitor()).
 *
 *          Аппаратный LVR тут не помощник — его порог 1.8/2.05/2.3 В и он
 *          не настраивается, а между банкой и чипом стоит LDO, который
 *          держит выход заметно выше. Ядро успевает зависнуть раньше.
 *
 *          Гасим ключ мотора напрямую регистрами (вызывать драйвер из NMI
 *          нельзя, он может быть прерван на середине) и перезапускаемся.
 *          Зависший чип с открытым ключом хуже любого сброса.
 */
__INTERRUPT
__HIGH_CODE
void NMI_Handler(void)
{
    MOTOR_PWM_DATA_REG = 0;
    R8_PWM_OUT_EN      = 0;
    GPIOB_ResetBits(MOTOR_PWM_PIN);

    sys_safe_access_enable();
    R8_RST_WDOG_CTRL |= RB_SOFTWARE_RESET;
    sys_safe_access_disable();

    while(1);
}

/*********************************************************************
 * @fn      Main_Circulation
 */
__HIGH_CODE
__attribute__((noinline))
void Main_Circulation(void)
{
    while(1)
    {
        TMOS_SystemProcess();
    }
}

/*********************************************************************
 * @fn      main
 */
int main(void)
{
#if(defined(DCDC_ENABLE)) && (DCDC_ENABLE == TRUE)
    PWR_DCDCCfg(ENABLE);
#endif

    SetSysClock(CLK_SOURCE_PLL_60MHz);

    /* Окно для отладчика — до всего остального. В Shutdown ядро обесточено
     * и WCH-Link к чипу не цепляется, так что без этой паузы прошивку,
     * которая быстро уснула, перезалить можно только через ISP-загрузчик. */
#if(BOOT_DELAY_MS > 0)
    mDelaymS(BOOT_DELAY_MS);
#endif

#ifdef DEBUG
    /* TXD1 на PA9 без ремапа (RB_PIN_UART1 = 0) */
    GPIOA_SetBits(PIN_DBG_TX);
    GPIOA_ModeCfg(PIN_DBG_TX, GPIO_ModeOut_PP_5mA);
    UART1_DefInit();
#else
    /* Без отладочного вывода PA9 остался бы висящим входом. Подтяжка вверх —
     * это и уровень покоя UART, если к ножке всё-таки подключатся. */
    GPIOA_ModeCfg(PIN_DBG_TX, GPIO_ModeIN_PU);
#endif

    PRINT("\n%s\n", VER_LIB);
    PRINT("CH582F pump control\n");

    /* Причина последнего сброса — главный диагност при ловле просадок.
     * Регистр только на чтение, живёт до следующего сброса. */
    {
        static const char *const rst[8] = {
            "SW",       /* 000 программный                          */
            "RPOR",     /* 001 подача питания ИЛИ срабатывание LVR   */
            "WDOG",     /* 010 сторожевой таймер — было зависание    */
            "RST#",     /* 011 кнопка сброса                         */
            "?",
            "GRWSM",    /* 101 пробуждение из Shutdown               */
            "?", "?"
        };
        s_reset_flag = R8_RESET_STATUS & RB_RESET_FLAG;
        PRINT("reset: %s\n", rst[s_reset_flag]);
    }

    Board_Init();

    /* Настройки читаются до инициализации мотора: частота ШИМ и границы
     * скважности берутся оттуда. */
    Settings_Load();
    PRINT("pwm=%d..%d f=%dHz boost=%d/%dms maxrun=%ds\n",
          g_set.pwm_min, g_set.pwm_max, g_set.pwm_freq_run,
          g_set.boost_power, g_set.boost_time, g_set.max_run_s);

    CH58X_BLEInit();
    HAL_Init();
    GAPRole_PeripheralInit();

    Peripheral_Init();
    Motor_Init();

    /* Голос на сброс типа «подача питания». Ловить надо LVR: провал VDD33
     * ниже 2.3 В, который детектор просадки почему-то не перехватил.
     *
     * Отличить LVR от честной подачи питания нельзя — флаг у них общий,
     * поэтому сигнал звучит и при вставке банки. Зато остальные причины
     * сюда не попадают, у каждой свой код: пробуждение из сна GRWSM,
     * сторожевой таймер WDOG, сброс по удержанию кнопки идёт через
     * reboot_as_power_on()... а вот он как раз даёт RPOR намеренно.
     * То есть после удержания кнопки сигнал прозвучит — так и должно быть,
     * это подтверждение, что механизм отработал.
     *
     * Ставится после Motor_Init(): длительности отсчитывает Buzzer_Tick()
     * из задачи мотора, до её запуска мелодия не поехала бы. */
    /* Вышли из глубокого сна по кнопке — сразу наливаем. Почему это нельзя
     * оставить автомату кнопки, расписано в Motor_WokeByKey(). */
    if(s_reset_flag == RESET_FLAG_GRWSM) Motor_WokeByKey();

    if(s_reset_flag == RESET_FLAG_RPOR) buzzer_critical();

    Main_Circulation();
}
