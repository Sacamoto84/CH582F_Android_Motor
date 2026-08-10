/********************************************************************************
 * motor_service.h — GATT-сервис управления мотором
 *
 * Сервис 0xFFE0
 *   0xFFE1  CMD   write        [op][lo][hi], 3 байта
 *   0xFFE2  DATA  notify       телеметрия / ответы / квитанции, до 20 байт
 *
 * Два характеристика вместо пятнадцати: всё влезает в MTU по умолчанию (23),
 * согласование MTU не нужно, кода в разы меньше, набор параметров расширяется
 * без правки таблицы атрибутов.
 *******************************************************************************/
#ifndef __MOTOR_SERVICE_H
#define __MOTOR_SERVICE_H

#include "CONFIG.h"

#define MOTORPROFILE_SERV_UUID      0xFFE0
#define MOTORPROFILE_CMD_UUID       0xFFE1
#define MOTORPROFILE_DATA_UUID      0xFFE2

/* Индекс атрибута со значением DATA — нужен, чтобы проставить handle в notify */
#define MOTORPROFILE_DATA_VALUE_POS 5

bStatus_t MotorService_AddService(void);
void      MotorService_SetConnHandle(uint16_t handle);

/* Отправить пакет в notify. Возвращает SUCCESS, если ушло. */
bStatus_t MotorService_Notify(uint8_t *pData, uint16_t len);

#endif /* __MOTOR_SERVICE_H */
