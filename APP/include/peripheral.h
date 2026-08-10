/********************************************************************************
 * peripheral.h — роль GAP Peripheral
 *******************************************************************************/
#ifndef __PERIPHERAL_H
#define __PERIPHERAL_H

#include "CONFIG.h"

#define SBP_START_DEVICE_EVT        0x0001
#define SBP_PARAM_UPDATE_EVT        0x0002

/* Интервал рекламы, единицы 625 мкс. 160 = 100 мс. */
#define DEFAULT_ADVERTISING_INTERVAL        160

/* Интервал соединения, единицы 1.25 мс.
 * 24 = 30 мс — компромисс: телеметрия раз в 200 мс проходит с запасом,
 * а окно между connection event достаточно велико, чтобы наш конвейер
 * измерения (пик ~820 мкс раз в 8 мс) в него укладывался. */
#define DEFAULT_DESIRED_MIN_CONN_INTERVAL   24
#define DEFAULT_DESIRED_MAX_CONN_INTERVAL   80      /* 100 мс */
#define DEFAULT_DESIRED_SLAVE_LATENCY       0
#define DEFAULT_DESIRED_CONN_TIMEOUT        300     /* 3 с, единицы 10 мс */

#define SBP_PARAM_UPDATE_DELAY              MS1_TO_SYSTEM_TIME(4000)

extern uint8_t Peripheral_TaskID;

void     Peripheral_Init(void);
uint16_t Peripheral_ProcessEvent(uint8_t task_id, uint16_t events);
uint8_t  Peripheral_IsConnected(void);
void     Peripheral_StopAdvertising(void);
void     Peripheral_StartAdvertising(void);

#endif /* __PERIPHERAL_H */
