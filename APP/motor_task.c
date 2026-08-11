/********************************************************************************
 * motor_task.c — задача TMOS помпы
 *
 * IDLE ──старт──> BOOST ──boost_time──> RUN ──стоп/таймаут/разряд──> IDLE
 *
 * Всё, что сложнее, из проекта убрано сознательно: у помпы постоянная
 * нагрузка и нечему клинить.
 *******************************************************************************/
#include "CONFIG.h"
#include "board.h"
#include "pwm_motor.h"
#include "settings.h"
#include "motor_task.h"
#include "motor_service.h"
#include "peripheral.h"
#include "buzzer.h"
#include "ubutton.h"

/* Глубокий сон живёт в main.c — там же возня с пинами перед засыпанием. */
extern void Board_Sleep(void);

uint8_t Motor_TaskID = INVALID_TASK_ID;

/* --------------------------------------------------------------- состояние */
static motor_state_t s_state = M_IDLE;
static uint8_t       s_cmd_start = 0;
static uint8_t       s_cmd_stop  = 0;
static uint8_t       s_stop_reason = STOPREASON_NONE;

static uint16_t s_pot_raw;            /* 0..4095, сглажен            */
static uint16_t s_pwm_pm;             /* выданная скважность         */
static uint16_t s_vbat_mv;

static uint32_t s_state_ms;           /* время в текущем состоянии   */
static uint32_t s_run_ms;             /* время непрерывной работы    */
static uint32_t s_idle_ms;
static uint32_t s_boot_ms;            /* сколько прошло с момента сброса */
static uint8_t  s_force_sleep;        /* команда «уснуть» игнорирует окно */
static uint16_t s_vbat_div;           /* делитель периода замера VBAT */
static uint8_t  s_vbat_arm;           /* питание подано, читаем на след. тике */
static uint8_t  s_sleep_countdown;

/* Кнопка */
static ubutton_t s_btn;
static uint8_t   s_reboot_armed;      /* сбросить чип, как доиграет сигнал */

/*********************************************************************
 * @fn      adc_read
 *
 * @brief   Одиночное преобразование. Занимает ~4 мкс на 8 МГц, поэтому
 *          блокировка безобидна — конвейера на прерываниях больше нет.
 *          Первую выборку после смены канала выбрасываем.
 */
static uint16_t adc_read(uint8_t channel)
{
    ADC_ChannelCfg(channel);
    (void)ADC_ExcutSingleConver();
    return ADC_ExcutSingleConver();
}

/*********************************************************************
 * @fn      read_pot
 *
 * @brief   Потенциометр с лёгким сглаживанием: без него дребезг младших
 *          разрядов АЦП слышен как шелест ШИМ.
 */
static void read_pot(void)
{
    uint16_t raw = adc_read(AIN_POT);
    s_pot_raw = (uint16_t)(((uint32_t)s_pot_raw * 3 + raw) >> 2);
}

/*********************************************************************
 * @fn      boot_grace_passed
 *
 * @brief   Первые boot_grace_s секунд после сброса сон запрещён.
 *
 *          В Shutdown ядро обесточено, отладчик к чипу не цепляется.
 *          Без этого окна прошивку с коротким sleep_tout_s пришлось бы
 *          перезаливать только через ISP-загрузчик (кнопка BOOT при сбросе).
 *          В отличие от BOOT_DELAY_MS ничего не блокирует: BLE рекламируется,
 *          помпа работает, просто устройство не уходит спать.
 */
static uint8_t boot_grace_passed(void)
{
    if(s_force_sleep)           return 1;   /* явная команда сильнее окна */
    if(g_set.boot_grace_s == 0) return 1;
    return (s_boot_ms >= (uint32_t)g_set.boot_grace_s * 1000U);
}

/*********************************************************************
 * @fn      vbat_tick
 *
 * @brief   Замер напряжения аккумулятора делителем на PA14. Ветка делителя
 *          обесточена ключом и включается только на время замера, поэтому
 *          в покое она не потребляет ничего.
 *
 *          Растянуто на два тика вместо блокирующей задержки: на первом
 *          подаём питание, на втором (через 10 мс) читаем и гасим.
 *          Постоянная времени делителя 22к‖18к с 10 нФ — 99 мкс, за 10 мс
 *          устанавливается стократно. Ядро при этом ни разу не блокируется,
 *          и connection event BLE не страдает.
 *
 *          Средний ток: ~180 мкА в течение 10 мс раз в секунду = 1.8 мкА.
 */
static void vbat_tick(void)
{
    if(s_vbat_arm)
    {
        uint16_t raw = adc_read(AIN_VBAT);
        VBAT_PWR_OFF();
        s_vbat_arm = 0;
        s_vbat_mv  = ADC_RAW_TO_MV(raw, g_set.vbat_scale_q12);
        return;
    }

    if(++s_vbat_div >= (VBAT_PERIOD_MS / MOTOR_TICK_MS))
    {
        s_vbat_div = 0;
        VBAT_PWR_ON();
        s_vbat_arm = 1;      /* прочитаем на следующем тике */
    }
}

/* Отображение потенциометра в скважность между pwm_min и pwm_max */
static uint16_t pot_to_permille(void)
{
    uint32_t span = (uint32_t)(g_set.pwm_max - g_set.pwm_min);
    return (uint16_t)(g_set.pwm_min + ((uint32_t)s_pot_raw * span) / 4095U);
}

static void apply_pwm(uint16_t pm)
{
    s_pwm_pm = pm;
    MotorPwm_SetDutyPermille(pm);
}

/*********************************************************************
 * @fn      boost_permille
 *
 * @brief   Скважность стартового рывка. boost_power — АБСОЛЮТНОЕ значение,
 *          а не добавка: рывок должен пробить трение покоя и столб воды
 *          независимо от того, куда выкручена ручка. Слабее рабочей
 *          скважности рывок быть не может.
 */
static uint16_t boost_permille(void)
{
    uint16_t b = g_set.boost_power;
    uint16_t p = pot_to_permille();

    if(b > 1000) b = 1000;
    if(p > b)    b = p;
    return b;
}

/*********************************************************************
 * @fn      vdrop_monitor
 *
 * @brief   Детектор просадки VDD33. Даёт NMI, обработчик в main.c
 *          немедленно перезапускает чип.
 *
 *          Зачем он, если есть аппаратный LVR: у LVR порог 1.8/2.05/2.3 В
 *          (даташит табл. 20-2, строка Non-CH583X) и он не настраивается.
 *          Между банкой и чипом стоит LDO, поэтому при просадке банки
 *          с 3.7 до 3.0 В на VDD33 остаётся ~2.9 В — до LVR далеко, а ядро
 *          от быстрого провала уже виснет. Детектор ловит раньше.
 *
 *          Порог 2.5 В (уровень 4) единственный гарантированно выше
 *          максимума LVR, то есть срабатывает до него.
 *
 *          Детектор ест 210 мкА, поэтому включается только на время работы
 *          помпы — на фоне 0.7 А это ничто, а в простое не мешает.
 */
static void vdrop_monitor(uint8_t on)
{
#if(DEBUG_FRIENDLY)
    (void)on;
    PowerMonitor(DISABLE, HALevel_1V9);     /* мешает отладчику — выключен */
#else
    if(on && g_set.vdrop_level && (g_set.vdrop_level <= 4))
    {
        /* уровни 1..4 → HALevel_1V9(0), _2V1(1), _2V3(2), _2V5(3) */
        PowerMonitor(ENABLE, (VolM_LevelypeDef)(g_set.vdrop_level - 1));
    }
    else
    {
        PowerMonitor(DISABLE, HALevel_1V9);
    }
#endif
}

/*********************************************************************
 * @fn      motor_off
 */
static void motor_off(uint8_t reason)
{
    apply_pwm(0);
    MotorPwm_Disable();
    vdrop_monitor(0);
    s_state       = M_IDLE;
    s_state_ms    = 0;
    s_run_ms      = 0;
    s_stop_reason = reason;

    /* Остановку по кнопке озвучивать не надо: щелчок уже прозвучал на
     * нажатии, как в оригинале. Голос подаём только на аварийные причины. */
    switch(reason)
    {
        case STOPREASON_TIMEOUT: buzzer_warning(); break;   /* сухой ход */
        default:                                   break;
    }
}

/*********************************************************************
 * Публичные команды
 */
void Motor_Start(void)  { s_cmd_start = 1; s_cmd_stop = 0; Motor_KickIdle(); }
void Motor_Stop(void)   { s_cmd_stop  = 1; s_cmd_start = 0; Motor_KickIdle(); }
void Motor_Toggle(void) { if(s_state == M_IDLE) Motor_Start(); else Motor_Stop(); }

uint8_t       Motor_IsStopped(void) { return (s_state == M_IDLE); }
motor_state_t Motor_GetState(void)  { return s_state; }

/*********************************************************************
 * @fn      App_SleepAllowed
 *
 * @brief   Разрешение автосна стеку BLE. Переопределяет слабую заглушку
 *          в HAL/SLEEP.c, вызывается из CH58X_LowPower().
 *
 *          LowPower_Sleep() останавливает PLL и HSE, а от Fsys тактуются
 *          и счётчик PWMX, и TMR1. Уснуть посреди работы означает застывший
 *          выход мотора (при «застывании в единице» — полностью открытый
 *          ключ на всю длительность сна) и рваный тон пищалки.
 *
 *          Пока помпа крутится, чип не спит вообще. Заодно это стабильное
 *          окно для отладчика: запустил насос и подключайся.
 *          Ток МК на фоне 0.7 А мотора всё равно не виден.
 */
uint8_t App_SleepAllowed(void)
{
    if(s_state != M_IDLE)     return 0; /* ШИМ замер бы вместе с Fsys  */
    if(Buzzer_IsBusy())       return 0; /* тон порвался бы             */
    if(!boot_grace_passed())  return 0; /* окно, чтобы успел отладчик  */
    return 1;
}

uint32_t Motor_IdleMs(void)  { return s_idle_ms; }

/*********************************************************************
 * @fn      Motor_KickIdle
 *
 * @brief   Любая активность (пуск, стоп, кнопка, пакет по BLE) сбрасывает
 *          отсчёт простоя.
 *
 *          Если засыпание уже началось, его надо отменить целиком: реклама
 *          к этому моменту погашена и сама не вернётся — Peripheral_Stop-
 *          Advertising() взводит s_adv_inhibit, который блокирует
 *          автоматический перезапуск в колбэке GAPROLE_WAITING. Без явного
 *          Peripheral_StartAdvertising() устройство осталось бы работающим,
 *          но невидимым в эфире до следующего сброса.
 */
void Motor_KickIdle(void)
{
    s_idle_ms = 0;

    if(s_sleep_countdown)
    {
        s_sleep_countdown = 0;
        Peripheral_StartAdvertising();
    }
}

/*********************************************************************
 * @fn      Motor_ForceSleep
 *
 * @brief   Явная команда «уснуть» по BLE. Останавливает помпу и снимает
 *          запрет на сон, наложенный boot_grace_s. Само засыпание
 *          произойдёт на ближайших тиках через штатную двухшаговую
 *          процедуру — уснуть прямо из колбэка стека нельзя.
 */
void Motor_ForceSleep(void)
{
    Motor_Stop();
    s_force_sleep = 1;
    s_idle_ms = (uint32_t)g_set.sleep_tout_s * 1000U;   /* не ждать таймаут */
}

/*********************************************************************
 * @fn      Motor_GetTelemetry
 */
void Motor_GetTelemetry(telemetry_t *t)
{
    t->type        = NTF_TELEMETRY;
    t->state       = (uint8_t)s_state;
    t->stop_reason = s_stop_reason;
    t->pwm_pct     = (uint8_t)(s_pwm_pm / 10);
    t->pwm_pm      = s_pwm_pm;
    t->pot_raw     = s_pot_raw;
    t->vbat_mv     = s_vbat_mv;
}

/*********************************************************************
 * @fn      Motor_Init
 */
void Motor_Init(void)
{
    Motor_TaskID = TMOS_ProcessEventRegister(Motor_ProcessEvent);

    MotorPwm_Init();
    MotorPwm_SetFreq(g_set.pwm_freq_run);
    MotorPwm_SetDutyPermille(0);

    /* Входной буфер включён — источники высокоомные (потенциометр 10к,
     * делитель 22к/18к). PGA 0 дБ: полезный вход 0..2.0 В, оба канала
     * спроектированы под этот потолок. */
    ADC_ExtSingleChSampInit(SampleFreq_8, ADC_PGA_0);

    s_pot_raw = adc_read(AIN_POT);        /* без этого первый пуск дёрнется */

    /* Первый замер аккумулятора блокирующий: BLE ещё не рекламируется,
     * мешать некому, а отсечка по разряду должна знать напряжение сразу. */
    VBAT_PWR_ON();
    DelayUs(1000);
    s_vbat_mv = ADC_RAW_TO_MV(adc_read(AIN_VBAT), g_set.vbat_scale_q12);
    VBAT_PWR_OFF();

    UB_Init(&s_btn);

    /* Сторожевой таймер: ловит зависание независимо от причины.
     * Счёт Fsys/131072 = 2.18 мс на такт, переполнение 8 бит → 559 мс.
     * Кормим в тике задачи каждые 10 мс, запаса вагон: даже запись
     * настроек во flash (до 34 мс) укладывается. */
#if(DEBUG_FRIENDLY == 0)
    WWDG_SetCounter(0);
    WWDG_ResetCfg(ENABLE);
#endif

    s_state = M_IDLE;
    s_idle_ms = 0;

    tmos_start_task(Motor_TaskID, MOTOR_EVT_TICK, MS1_TO_SYSTEM_TIME(MOTOR_TICK_MS));
}

/*********************************************************************
 * @fn      handle_commands
 */
static void handle_commands(void)
{
    if(s_cmd_stop)
    {
        s_cmd_stop = 0;
        motor_off(STOPREASON_USER);
        return;
    }

    if(s_cmd_start && (s_state == M_IDLE))
    {
        MotorPwm_Enable();
        vdrop_monitor(1);
        s_state_ms    = 0;
        s_run_ms      = 0;
        s_stop_reason = STOPREASON_NONE;

        if(g_set.boost_en && g_set.boost_time)
        {
            MotorPwm_SetFreq(g_set.pwm_freq_boost);
            apply_pwm(boost_permille());
            s_state = M_BOOST;
        }
        else
        {
            MotorPwm_SetFreq(g_set.pwm_freq_run);
            apply_pwm(pot_to_permille());
            s_state = M_RUN;
        }
    }
    s_cmd_start = 0;
}

/*********************************************************************
 * @fn      run_state_machine
 *
 * @brief   Вызывается раз в MOTOR_TICK_MS.
 */
static void run_state_machine(void)
{
    s_state_ms += MOTOR_TICK_MS;

    switch(s_state)
    {
        case M_IDLE:
            break;

        case M_BOOST:
            if(s_state_ms >= g_set.boost_time)
            {
                MotorPwm_SetFreq(g_set.pwm_freq_run);
                apply_pwm(pot_to_permille());
                s_state    = M_RUN;
                s_state_ms = 0;
            }
            break;

        case M_RUN:
            s_run_ms += MOTOR_TICK_MS;

            /* ручка живая на ходу */
            apply_pwm(pot_to_permille());

            /* Авто-стоп: бутыль опустела, а помпа гонит воздух */
            if(g_set.max_run_s &&
               (s_run_ms >= (uint32_t)g_set.max_run_s * 1000U))
            {
                motor_off(STOPREASON_TIMEOUT);
            }
            break;

        default:
            break;
    }
}

/*********************************************************************
 * @fn      poll_key
 *
 * @brief   Порт ScreenNormal() из CH32V003_MotorControl_Volume.
 *          Поведение для пользователя должно совпадать с тем проектом.
 *
 *          нажатие       — щелчок + ПЕРЕКЛЮЧИТЬ помпу (сразу, не по
 *                          отпусканию)
 *          удержание 2 с — мелодия shutdown, затем сброс чипа:
 *                            отпустил после сигнала → обычный старт
 *                            держишь дальше         → ISP-загрузчик
 *          5 кликов      — сброс (кнопка уже отпущена, значит обычный старт)
 *
 *          «Лестница» импульсов удержания из оригинала выброшена вместе
 *          с четырьмя экранами настройки: SET_POWER, SET_BOOST_ENABLE,
 *          SET_BOOST_POWER и SET_BOOST_TIME стали параметрами BLE.
 *
 *          Уход в сон здесь НЕ вызывается. В оригинале это делал
 *          b.timeout() через секунду после отпускания; у нас тем же
 *          занимается таймер простоя sleep_tout_s, который заодно
 *          учитывает соединение BLE.
 */
static void poll_key(void)
{
    UB_Tick(&s_btn, KEY_PRESSED(), MOTOR_TICK_MS);

    if(UB_Busy(&s_btn)) Motor_KickIdle();

    if(UB_Press(&s_btn))
    {
        buzzer_ios_click();
        Motor_Toggle();
    }

    if(UB_Hold(&s_btn))
    {
        buzzer_shutdown();
        Motor_Stop();
        s_reboot_armed = 1;
    }

    if(UB_HasClicks(&s_btn) && (UB_GetClicks(&s_btn) == 5))
    {
        buzzer_shutdown();
        s_reboot_armed = 1;
    }
}

/*********************************************************************
 * @fn      Motor_ProcessEvent
 */
uint16_t Motor_ProcessEvent(uint8_t task_id, uint16_t events)
{
    if(events & MOTOR_EVT_TICK)
    {
        tmos_start_task(Motor_TaskID, MOTOR_EVT_TICK, MS1_TO_SYSTEM_TIME(MOTOR_TICK_MS));

#if(DEBUG_FRIENDLY == 0)
        WWDG_SetCounter(0);         /* покормить сторожевой таймер */
#endif

        /* Счётчик окна для отладчика. Растёт только до порога — так он
         * не переполнится за годы работы. */
        if(s_boot_ms < 0xFFFF0000U) s_boot_ms += MOTOR_TICK_MS;

        read_pot();
        vbat_tick();

        Buzzer_Tick();

        handle_commands();
        poll_key();
        run_state_machine();

        LED_OFF();
        if(s_state != M_IDLE)
        {
            LED_ON();
            Motor_KickIdle();
        }
        else
        {
            s_idle_ms += MOTOR_TICK_MS;
        }

        /* Сброс. Ждём только окончания сигнала — состояние кнопки
         * НАМЕРЕННО не проверяется, и это ключевой момент.
         *
         * PB22 одновременно пользовательская кнопка и пин ISP-загрузчика,
         * который читается в момент сброса. Отсюда два исхода одного жеста:
         *
         *   держать до сигнала и ОТПУСТИТЬ  → PB22 высокий → обычный старт
         *   держать до сигнала и НЕ ОТПУСКАТЬ → PB22 низкий → ISP-загрузчик
         *
         * Без этого в ISP на батарейном питании попасть нечем: кнопка RST
         * на плате по умолчанию мертва, опция CFG_RESET_EN сброшена
         * с завода (даташит, табл. 2-3), а питание не передёрнуть. */
        if(s_reboot_armed && !Buzzer_IsBusy())
        {
            MotorPwm_Disable();
            PRINT("reboot\n");
            while((R8_UART1_LSR & RB_LSR_TX_ALL_EMP) == 0);
            sys_safe_access_enable();
            R8_RST_WDOG_CTRL |= RB_SOFTWARE_RESET;
            sys_safe_access_disable();
            while(1);
        }

        Settings_Tick();

        /* Глубокий сон в два шага: сначала гасим рекламу и даём стеку
         * несколько тиков доработать свои RTC-события, только потом
         * вызываем Board_Sleep. Уснуть сразу после StopAdvertising
         * нельзя — стек тут же разбудит чип по своему таймеру. */
#if(DEBUG_FRIENDLY == 0)
        if(s_sleep_countdown)
        {
            if(--s_sleep_countdown == 0) Board_Sleep();
        }
        else if((s_state == M_IDLE) &&
                !Peripheral_IsConnected() &&
                boot_grace_passed() &&
                (s_force_sleep ||
                 (g_set.sleep_tout_s &&
                  (s_idle_ms >= (uint32_t)g_set.sleep_tout_s * 1000U))))
        {
            Peripheral_StopAdvertising();
            s_sleep_countdown = 20;      /* 200 мс */
        }
#endif

        return (events ^ MOTOR_EVT_TICK);
    }

    if(events & MOTOR_EVT_TELEMETRY)
    {
        telemetry_t t;
        Motor_GetTelemetry(&t);
        MotorService_Notify((uint8_t *)&t, sizeof(t));
        tmos_start_task(Motor_TaskID, MOTOR_EVT_TELEMETRY, MS1_TO_SYSTEM_TIME(MOTOR_TELEMETRY_MS));
        return (events ^ MOTOR_EVT_TELEMETRY);
    }

    return 0;
}
