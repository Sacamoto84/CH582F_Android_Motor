/********************************************************************************
 * ubutton.c — автомат кнопки, ужатый порт uButtonVirt.h с CH32V003
 *******************************************************************************/
#include "ubutton.h"

void UB_Init(ubutton_t *b)
{
    b->state  = UB_IDLE;
    b->tmr    = 0;
    b->deb    = 0;
    b->raw    = 0;
    b->stable = 0;
}

void UB_Reset(ubutton_t *b)
{
    b->state = UB_IDLE;
    b->tmr   = 0;
}

/*********************************************************************
 * @fn      debounce
 *
 * @brief   Уровень принимается, только если продержался UB_DEB_TIME.
 *          При тике 10 мс это ровно один тик — как в оригинале, где
 *          pollDebounce() вызывался в быстром цикле с тем же порогом.
 */
static void debounce(ubutton_t *b, uint8_t pressed, uint16_t dt_ms)
{
    if(pressed != b->raw)
    {
        b->raw = pressed;
        b->deb = 0;
        return;
    }

    if(b->stable == pressed) return;

    b->deb += dt_ms;
    if(b->deb >= UB_DEB_TIME)
    {
        b->stable = pressed;
        b->deb    = 0;
    }
}

/*********************************************************************
 * @fn      UB_Tick
 *
 * @brief   Один шаг автомата. Однотактовые состояния-события живут ровно
 *          один вызов, поэтому предикаты читать сразу после.
 *
 *          Короткое нажатие отдельным событием не оформляется: помпа
 *          переключается сразу по UB_PRESS, и отпускание уже ничего
 *          не значит. Поэтому из WAIT_HOLD отпускание ведёт прямо в IDLE.
 */
void UB_Tick(ubutton_t *b, uint8_t pressed, uint16_t dt_ms)
{
    uint8_t p;

    debounce(b, pressed ? 1 : 0, dt_ms);
    p = b->stable;

    b->tmr += dt_ms;

    switch(b->state)
    {
        case UB_IDLE:
            if(p) b->state = UB_PRESS;
            break;

        case UB_PRESS:
            b->state = UB_WAIT_HOLD;
            b->tmr   = 0;
            break;

        case UB_WAIT_HOLD:
            if(!p)
            {
                b->state = UB_IDLE;
            }
            else if(b->tmr >= UB_HOLD_TIME)
            {
                b->state = UB_HOLD;
                b->tmr   = 0;
            }
            break;

        case UB_HOLD:
            b->state = UB_WAIT_RELEASE;
            break;

        /* Сюда попадаем уже взведя сброс: ждём, пока отпустят, чтобы
         * повторное удержание не начало отсчёт заново. */
        case UB_WAIT_RELEASE:
            if(!p) b->state = UB_IDLE;
            break;

        default:
            b->state = UB_IDLE;
            break;
    }
}
