/********************************************************************************
 * settings.c — параметры в DataFlash, два слота с чередованием
 *******************************************************************************/
#include "CONFIG.h"          /* tmos_memset и прочее из CH58xBLE_LIB.h */
#include "settings.h"
#include "board.h"
#include "pwm_motor.h"
#include "motor_task.h"
#include "buzzer.h"

settings_t g_set;

static uint8_t s_save_pending = 0;
static uint8_t s_last_slot    = 1;      /* следующая запись пойдёт в слот A */

/*********************************************************************
 * @fn      crc16
 *
 * @brief   CRC-16/CCITT по всей структуре, кроме поля crc.
 */
static uint16_t crc16(const uint8_t *p, uint32_t len)
{
    uint16_t crc = 0xFFFF;
    uint32_t i;
    uint8_t  b;

    for(i = 0; i < len; i++)
    {
        crc ^= (uint16_t)p[i] << 8;
        for(b = 0; b < 8; b++)
        {
            crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
        }
    }
    return crc;
}

static uint8_t slot_valid(const settings_t *s)
{
    if(s->magic != SETTINGS_MAGIC)     return 0;
    if(s->version != SETTINGS_VERSION) return 0;
    if(s->crc != crc16((const uint8_t *)s, sizeof(settings_t) - 2)) return 0;
    return 1;
}

/*********************************************************************
 * @fn      Settings_Defaults
 *
 * @brief   Помпа для 19-литровой бутыли, 1S Li-ion, мотор 0.7 А.
 */
void Settings_Defaults(void)
{
    tmos_memset(&g_set, 0, sizeof(g_set));

    g_set.magic          = SETTINGS_MAGIC;
    g_set.version        = SETTINGS_VERSION;
    g_set.seq            = 1;

    g_set.pwm_min        = 300;      /* 30 % при ручке в минимуме */
    g_set.pwm_max        = 950;      /* 95 % при ручке в максимуме */
    g_set.pwm_freq_run   = 16000;    /* выше слышимого */
    /* Столько же, сколько рабочая. Момент задаёт скважность, а не частота;
     * низкая частота лишь раздувает пульсации тока и слышна. */
    g_set.pwm_freq_boost = 16000;

    g_set.boost_en       = 1;
    g_set.boost_power    = 800;      /* 80 % — пробить трение покоя и столб воды */
    g_set.boost_time     = 150;      /* мс */

    g_set.max_run_s      = 120;      /* сухой ход, если бутыль пуста */
    g_set.vbat_scale_q12 = VBAT_SCALE_Q12_DEFAULT;
    g_set.vdrop_level    = 4;        /* 2.5 В — выше максимума LVR (2.3) */
    /* Отсчёт идёт только когда помпа СТОИТ и никто не подключён по BLE.
     * Во время работы насоса чип не спит вообще. */
    g_set.sleep_tout_s   = 60;

    /* 3.0 В — нижняя граница разряда 1S Li-ion. Ниже помпу не пускаем:
     * глубокий разряд убивает банку, а просадка под 0.7 А уронит чип. */
    g_set.vbat_min_mv    = 3000;

    /* Полная шкала АЦП по умолчанию: значение нейтральное, ход ручки просто
     * не дотянет до pwm_max. Настоящее число снимается с железа и ставится
     * по BLE — см. комментарий к pot_to_permille(). */
    g_set.pot_raw_max    = 4095;
}

/*********************************************************************
 * @fn      Settings_Load
 */
void Settings_Load(void)
{
    settings_t a, b;
    uint8_t    va, vb;

    EEPROM_READ(SETTINGS_SLOT_A, &a, sizeof(a));
    EEPROM_READ(SETTINGS_SLOT_B, &b, sizeof(b));

    va = slot_valid(&a);
    vb = slot_valid(&b);

    if(va && vb)
    {
        if(a.seq >= b.seq) { g_set = a; s_last_slot = 0; }
        else               { g_set = b; s_last_slot = 1; }
    }
    else if(va) { g_set = a; s_last_slot = 0; }
    else if(vb) { g_set = b; s_last_slot = 1; }
    else
    {
        Settings_Defaults();
        s_last_slot = 1;
        Settings_SaveNow();
    }
}

/*********************************************************************
 * @fn      Settings_SaveNow
 *
 * @brief   Блокирующая операция. Даташит, табл. 20-9: стирание сектора
 *          6/16/30 мс, программирование 1/2/4 мс. Ядро на это время стоит,
 *          поэтому вызывать только при остановленной помпе.
 *
 *          Соединение BLE переживает: при supervision timeout 3 с потеря
 *          одного-двух connection event не рвёт линк.
 *
 *          Ресурс ячейки 100 000 циклов при 5..45 °C (табл. 20-9), поэтому
 *          изменение параметра сюда не приводит: оно применяется в ОЗУ, а
 *          запись идёт только по команде CMD_SAVE. Чередование слотов делит
 *          износ пополам.
 */
uint8_t Settings_SaveNow(void)
{
    uint32_t addr;
    uint8_t  res;

    addr = (s_last_slot == 0) ? SETTINGS_SLOT_B : SETTINGS_SLOT_A;

    g_set.magic   = SETTINGS_MAGIC;
    g_set.version = SETTINGS_VERSION;
    g_set.seq++;
    g_set.crc     = crc16((const uint8_t *)&g_set, sizeof(g_set) - 2);

    res = EEPROM_ERASE(addr, EEPROM_PAGE_SIZE);
    if(res) return 0;

    res = EEPROM_WRITE(addr, &g_set, sizeof(g_set));
    if(res) return 0;

    s_last_slot = (s_last_slot == 0) ? 1 : 0;
    s_save_pending = 0;
    beep_Save();
    return 1;
}

void Settings_RequestSave(void)
{
    s_save_pending = 1;
}

/*********************************************************************
 * @fn      Settings_Tick
 *
 * @brief   Пишем только когда мотор стоит: иначе провалим connection
 *          event BLE и дёрнем ШИМ.
 */
void Settings_Tick(void)
{
    if(!s_save_pending)    return;
    if(!Motor_IsStopped()) return;

    Settings_SaveNow();
}

/*********************************************************************
 * @fn      Settings_Get / Settings_Set
 */
uint16_t Settings_Get(uint8_t id)
{
    switch(id)
    {
        case PID_PWM_MIN:        return g_set.pwm_min;
        case PID_PWM_MAX:        return g_set.pwm_max;
        case PID_PWM_FREQ_RUN:   return g_set.pwm_freq_run;
        case PID_PWM_FREQ_BOOST: return g_set.pwm_freq_boost;
        case PID_BOOST_EN:       return g_set.boost_en;
        case PID_BOOST_POWER:    return g_set.boost_power;
        case PID_BOOST_TIME:     return g_set.boost_time;
        case PID_MAX_RUN_S:      return g_set.max_run_s;
        case PID_VBAT_SCALE_Q12: return g_set.vbat_scale_q12;
        case PID_VDROP_LEVEL:    return g_set.vdrop_level;
        case PID_SLEEP_TOUT_S:   return g_set.sleep_tout_s;
        case PID_VBAT_MIN_MV:    return g_set.vbat_min_mv;
        case PID_POT_RAW_MAX:    return g_set.pot_raw_max;
        default:                 return 0;
    }
}

uint8_t Settings_Set(uint8_t id, uint16_t v)
{
    switch(id)
    {
        case PID_PWM_MIN:        if(v > 1000) return 0; g_set.pwm_min = v; break;
        case PID_PWM_MAX:        if(v > 1000) return 0; g_set.pwm_max = v; break;
        case PID_PWM_FREQ_RUN:   if(v < MOTOR_PWM_HZ_MIN) return 0;
                                 g_set.pwm_freq_run = v;
                                 MotorPwm_SetFreq(v);        /* слышно сразу */
                                 break;
        case PID_PWM_FREQ_BOOST: if(v < MOTOR_PWM_HZ_MIN) return 0;
                                 g_set.pwm_freq_boost = v; break;
        case PID_BOOST_EN:       g_set.boost_en = (v != 0); break;
        case PID_BOOST_POWER:    if(v > 1000) return 0; g_set.boost_power = v; break;
        case PID_BOOST_TIME:     if(v > 5000) return 0; g_set.boost_time = v; break;
        case PID_MAX_RUN_S:      g_set.max_run_s = v; break;
        case PID_VBAT_SCALE_Q12: if(v == 0) return 0; g_set.vbat_scale_q12 = v; break;
        case PID_VDROP_LEVEL:    if(v > 4) return 0; g_set.vdrop_level = v; break;
        case PID_SLEEP_TOUT_S:   g_set.sleep_tout_s = v; break;
        /* Выше 4.2 В смысла нет: заряженная банка 1S столько и даёт,
         * порог выше сделал бы помпу неработоспособной навсегда. */
        case PID_VBAT_MIN_MV:    if(v > 4200) return 0; g_set.vbat_min_mv = v; break;
        /* Ниже 100 ручка стала бы неуправляемо резкой, выше 4095 АЦП
         * не выдаёт — там 12 бит. */
        case PID_POT_RAW_MAX:    if(v < 100 || v > 4095) return 0;
                                 g_set.pot_raw_max = v; break;
        default:                 return 0;
    }

    /* Границы могли переехать друг через друга */
    if(g_set.pwm_min > g_set.pwm_max)
    {
        uint16_t t = g_set.pwm_min;
        g_set.pwm_min = g_set.pwm_max;
        g_set.pwm_max = t;
    }

    /* Во flash НЕ пишем: значение уже действует, а запись — отдельное явное
     * действие пользователя (CMD_SAVE). Иначе каждый шаг ползунка в мастере
     * подбора стоил бы цикла стирания страницы. */
    return 1;
}
