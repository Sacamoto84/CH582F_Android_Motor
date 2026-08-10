/********************************************************************************
 * buzzer.c — пищалка на PB4, тон переключением ножки в прерывании TMR1
 *
 * Частоты и длительности — из User/buzzer.c проекта на CH32V003.
 *******************************************************************************/
#include "buzzer.h"
#include "motor_task.h"      /* MOTOR_TICK_MS */

/* --------------------------------------------------------------- состояние */
static const buz_note_t *s_seq;
static uint8_t    s_count;
static uint8_t    s_idx;
static int32_t    s_left_ms;
static buz_note_t s_single;

/*********************************************************************
 * @fn      tone_on / tone_off
 *
 * @brief   TMR1 считает ПОЛУпериод: каждое прерывание инвертирует ножку,
 *          два прерывания дают один период меандра.
 */
static void tone_off(void)
{
    TMR1_Disable();
    GPIOB_ResetBits(PIN_BUZZER);
}

static void tone_on(uint16_t freq)
{
    uint32_t half;

    if(freq < 50 || freq > 20000)
    {
        tone_off();                 /* freq = 0 — это пауза */
        return;
    }

    half = SYS_CLK_HZ / (2UL * freq);

    /* Идиома перезапуска из штатного TMR1_TimerInit: сначала ALL_CLEAR
     * (он же останавливает и обнуляет счётчик), потом COUNT_EN.
     * R8_TMR1_INTER_EN этим не затрагивается. */
    R8_TMR1_CTRL_MOD = RB_TMR_ALL_CLEAR;
    R32_TMR1_CNT_END = half;
    R8_TMR1_CTRL_MOD = RB_TMR_COUNT_EN;
}

/*********************************************************************
 * @fn      Buzzer_Init
 */
void Buzzer_Init(void)
{
    GPIOB_ModeCfg(PIN_BUZZER, GPIO_ModeOut_PP_20mA);
    GPIOB_ResetBits(PIN_BUZZER);

    TMR1_TimerInit(SYS_CLK_HZ / 2000);
    TMR1_Disable();
    TMR1_ITCfg(ENABLE, TMR0_3_IT_CYC_END);
    PFIC_EnableIRQ(TMR1_IRQn);

    s_seq     = NULL;
    s_count   = 0;
    s_idx     = 0;
    s_left_ms = 0;
}

/*********************************************************************
 * @fn      Buzzer_Play
 */
void Buzzer_Play(const buz_note_t *seq, uint8_t count)
{
    if(seq == NULL || count == 0) return;

    s_seq     = seq;
    s_count   = count;
    s_idx     = 0;
    s_left_ms = seq[0].ms;
    tone_on(seq[0].freq);
}

void Buzzer_Tone(uint16_t freq, uint16_t ms)
{
    s_single.freq = freq;
    s_single.ms   = ms;
    Buzzer_Play(&s_single, 1);
}

void Buzzer_Stop(void)
{
    tone_off();
    s_seq     = NULL;
    s_count   = 0;
    s_left_ms = 0;
}

uint8_t Buzzer_IsBusy(void)
{
    return (s_seq != NULL);
}

/*********************************************************************
 * @fn      Buzzer_Tick
 *
 * @brief   Шаг отсчёта — MOTOR_TICK_MS, то есть 10 мс. Ноты короче
 *          округляются вверх: щелчок 25 мс прозвучит как 30 мс.
 */
void Buzzer_Tick(void)
{
    if(s_seq == NULL) return;

    s_left_ms -= MOTOR_TICK_MS;
    if(s_left_ms > 0) return;

    if(++s_idx >= s_count)
    {
        Buzzer_Stop();
        return;
    }

    s_left_ms = s_seq[s_idx].ms;
    tone_on(s_seq[s_idx].freq);
}

/*********************************************************************
 * Сигналы. Таблицы const — лежат во flash, копий в ОЗУ нет.
 *********************************************************************/
#define PLAY(tbl)   Buzzer_Play((tbl), sizeof(tbl) / sizeof((tbl)[0]))

static const buz_note_t T_OK[]        = {{1000,  80}, {1500,  80}};
static const buz_note_t T_ERROR[]     = {{ 400, 150}, { 200, 200}};
static const buz_note_t T_WARNING[]   = {{ 800, 150}};
static const buz_note_t T_CLICK[]     = {{1200,  40}};
static const buz_note_t T_CRITICAL[]  = {{ 250, 200}, {0, 80}, {250, 200}, {0, 80}, {250, 200}, {0, 80}};
static const buz_note_t T_BEEPBOOP[]  = {{1000,  60}, { 700,  60}};
static const buz_note_t T_NOTIFY[]    = {{1000, 100}, { 800, 150}};
static const buz_note_t T_IOS_CLICK[] = {{1800,  25}};
static const buz_note_t T_STARTUP[]   = {{ 800, 120}, {1000, 120}, {1300, 160}};
static const buz_note_t T_SHUTDOWN[]  = {{1200, 150}, { 900, 150}, { 600, 180}};
static const buz_note_t T_CHARGING[]  = {{1500,  80}, {1800, 150}};
static const buz_note_t T_INC_MAX[]   = {{1500,  80}, {0, 40}, {1500,  80}, {0, 40}, {1200, 100}};
static const buz_note_t T_DEC_MIN[]   = {{ 800,  80}, {0, 40}, { 800,  80}, {0, 40}, {1000, 100}};
static const buz_note_t T_SAVE[]      = {{1000,  40}, {1500,  80}};

void buzzer_ok(void)          { PLAY(T_OK);        }
void buzzer_error(void)       { PLAY(T_ERROR);     }
void buzzer_warning(void)     { PLAY(T_WARNING);   }
void buzzer_click(void)       { PLAY(T_CLICK);     }
void buzzer_critical(void)    { PLAY(T_CRITICAL);  }
void buzzer_beepboop(void)    { PLAY(T_BEEPBOOP);  }
void buzzer_notify(void)      { PLAY(T_NOTIFY);    }
void buzzer_ios_click(void)   { PLAY(T_IOS_CLICK); }
void buzzer_startup(void)     { PLAY(T_STARTUP);   }
void buzzer_shutdown(void)    { PLAY(T_SHUTDOWN);  }
void buzzer_charging(void)    { PLAY(T_CHARGING);  }
void beep_Increment_Max(void) { PLAY(T_INC_MAX);   }
void beep_Decrement_Min(void) { PLAY(T_DEC_MIN);   }
void beep_Save(void)          { PLAY(T_SAVE);      }

/*********************************************************************
 * @fn      TMR1_IRQHandler
 *
 * @brief   Одна инверсия ножки. Держать коротким: на 2 кГц это 4000
 *          прерываний в секунду, и они конкурируют со стеком BLE.
 */
__INTERRUPT
__HIGH_CODE
void TMR1_IRQHandler(void)
{
    TMR1_ClearITFlag(TMR0_3_IT_CYC_END);
    GPIOB_InverseBits(PIN_BUZZER);
}
