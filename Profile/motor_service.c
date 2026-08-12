/********************************************************************************
 * motor_service.c — GATT-сервис управления мотором
 *******************************************************************************/
/* primaryServiceUUID / characterUUID / charUserDescUUID / clientCharCfgUUID
 * объявлены в CH58xBLE_LIB.h — gattprofile.h из примера не нужен. */
#include "CONFIG.h"
#include "motor_service.h"
#include "app_proto.h"
#include "settings.h"
#include "motor_task.h"

/* ------------------------------------------------------------------- UUID */
static const uint8_t motorServUUID[ATT_BT_UUID_SIZE] = {
    LO_UINT16(MOTORPROFILE_SERV_UUID), HI_UINT16(MOTORPROFILE_SERV_UUID)};
static const uint8_t motorCmdUUID[ATT_BT_UUID_SIZE] = {
    LO_UINT16(MOTORPROFILE_CMD_UUID), HI_UINT16(MOTORPROFILE_CMD_UUID)};
static const uint8_t motorDataUUID[ATT_BT_UUID_SIZE] = {
    LO_UINT16(MOTORPROFILE_DATA_UUID), HI_UINT16(MOTORPROFILE_DATA_UUID)};

/* -------------------------------------------------------------- атрибуты */
static const gattAttrType_t motorService = {ATT_BT_UUID_SIZE, motorServUUID};

static uint8_t cmdProps  = GATT_PROP_WRITE | GATT_PROP_WRITE_NO_RSP;
static uint8_t cmdValue[3] = {0};
static uint8_t cmdUserDesp[] = "Command\0";

static uint8_t dataProps = GATT_PROP_NOTIFY;
static uint8_t dataValue[1] = {0};      /* реальные данные уходят в notify */
static gattCharCfg_t dataConfig[PERIPHERAL_MAX_CONNECTION];
static uint8_t dataUserDesp[] = "Telemetry\0";

static uint16_t s_connHandle = INVALID_CONNHANDLE;

/* Следующий параметр к выгрузке; PID__COUNT — выгрузка не идёт */
static uint8_t s_dump_next = PID__COUNT;

/* Порядок строк должен совпадать с MOTORPROFILE_DATA_VALUE_POS */
static gattAttribute_t motorAttrTbl[] = {
    /* 0 — объявление сервиса */
    {
        {ATT_BT_UUID_SIZE, primaryServiceUUID},
        GATT_PERMIT_READ,
        0,
        (uint8_t *)&motorService},

    /* 1 — объявление характеристики CMD */
    {
        {ATT_BT_UUID_SIZE, characterUUID},
        GATT_PERMIT_READ,
        0,
        &cmdProps},

    /* 2 — значение CMD */
    {
        {ATT_BT_UUID_SIZE, motorCmdUUID},
        GATT_PERMIT_WRITE,
        0,
        cmdValue},

    /* 3 — описание CMD */
    {
        {ATT_BT_UUID_SIZE, charUserDescUUID},
        GATT_PERMIT_READ,
        0,
        cmdUserDesp},

    /* 4 — объявление характеристики DATA */
    {
        {ATT_BT_UUID_SIZE, characterUUID},
        GATT_PERMIT_READ,
        0,
        &dataProps},

    /* 5 — значение DATA (MOTORPROFILE_DATA_VALUE_POS) */
    {
        {ATT_BT_UUID_SIZE, motorDataUUID},
        0,
        0,
        dataValue},

    /* 6 — CCCD DATA */
    {
        {ATT_BT_UUID_SIZE, clientCharCfgUUID},
        GATT_PERMIT_READ | GATT_PERMIT_WRITE,
        0,
        (uint8_t *)dataConfig},

    /* 7 — описание DATA */
    {
        {ATT_BT_UUID_SIZE, charUserDescUUID},
        GATT_PERMIT_READ,
        0,
        dataUserDesp},
};

/* ------------------------------------------------------------- прототипы */
static bStatus_t motor_ReadAttrCB(uint16_t connHandle, gattAttribute_t *pAttr,
                                  uint8_t *pValue, uint16_t *pLen, uint16_t offset,
                                  uint16_t maxLen, uint8_t method);
static bStatus_t motor_WriteAttrCB(uint16_t connHandle, gattAttribute_t *pAttr,
                                   uint8_t *pValue, uint16_t len, uint16_t offset,
                                   uint8_t method);
static void      motor_HandleConnStatusCB(uint16_t connHandle, uint8_t changeType);
static void      handle_packet(const uint8_t *p, uint16_t len);

gattServiceCBs_t motorServiceCBs = {
    motor_ReadAttrCB,
    motor_WriteAttrCB,
    NULL};

/*********************************************************************
 * @fn      MotorService_AddService
 */
bStatus_t MotorService_AddService(void)
{
    GATTServApp_InitCharCfg(INVALID_CONNHANDLE, dataConfig);
    linkDB_Register(motor_HandleConnStatusCB);

    return GATTServApp_RegisterService(motorAttrTbl,
                                       GATT_NUM_ATTRS(motorAttrTbl),
                                       GATT_MAX_ENCRYPT_KEY_SIZE,
                                       &motorServiceCBs);
}

void MotorService_SetConnHandle(uint16_t handle)
{
    s_connHandle = handle;
    s_dump_next  = PID__COUNT;   /* недоигранная выгрузка в новое соединение не тянется */
}

/*********************************************************************
 * @fn      MotorService_Notify
 *
 * @brief   Буфер под уведомление выделяет стек, освобождать при ошибке
 *          обязательно — иначе утечка в куче BLE.
 */
bStatus_t MotorService_Notify(uint8_t *pData, uint16_t len)
{
    attHandleValueNoti_t noti;
    uint16_t             cfg;

    if(s_connHandle == INVALID_CONNHANDLE) return bleNotConnected;

    cfg = GATTServApp_ReadCharCfg(s_connHandle, dataConfig);
    if(!(cfg & GATT_CLIENT_CFG_NOTIFY)) return bleIncorrectMode;

    if(len > (ATT_MTU_SIZE - 3)) return bleInvalidRange;

    noti.len = len;
    noti.pValue = GATT_bm_alloc(s_connHandle, ATT_HANDLE_VALUE_NOTI, len, NULL, 0);
    if(noti.pValue == NULL) return bleMemAllocError;

    tmos_memcpy(noti.pValue, pData, len);
    noti.handle = motorAttrTbl[MOTORPROFILE_DATA_VALUE_POS].handle;

    if(GATT_Notification(s_connHandle, &noti, FALSE) != SUCCESS)
    {
        GATT_bm_free((gattMsg_t *)&noti, ATT_HANDLE_VALUE_NOTI);
        return FAILURE;
    }
    return SUCCESS;
}

/*********************************************************************
 * @fn      MotorService_DumpTick
 *
 * @brief   Выгрузка по OP_DUMP_ALL: один параметр за вызов.
 *
 *          Темп задаёт сам стек. Пока в очереди есть место, пакет уходит
 *          и счётчик двигается; когда буферы кончились, Notify возвращает
 *          ошибку и тот же параметр повторяется на следующем тике. Так
 *          серия сама подстраивается под интервал соединения и не теряет
 *          хвост, как это делал прежний цикл.
 *
 *          Ошибки не про буферы (нет соединения, клиент не включил
 *          уведомления) выгрузку прекращают — повторять нечего.
 */
void MotorService_DumpTick(void)
{
    param_ntf_t pn;
    bStatus_t   st;

    if(s_dump_next >= PID__COUNT) return;

    pn.type  = NTF_PARAM;
    pn.id    = s_dump_next;
    pn.value = Settings_Get(s_dump_next);

    st = MotorService_Notify((uint8_t *)&pn, sizeof(pn));

    if(st == SUCCESS)
    {
        s_dump_next++;
    }
    else if((st != bleMemAllocError) && (st != FAILURE))
    {
        s_dump_next = PID__COUNT;
    }
}

/*********************************************************************
 * @fn      handle_packet
 *
 * @brief   Разбор трёхбайтового пакета [op][lo][hi].
 */
static void handle_packet(const uint8_t *p, uint16_t len)
{
    uint8_t  op;
    uint16_t value;
    ack_ntf_t   ack;
    param_ntf_t pn;

    if(len < 3) return;

    op    = p[0];
    value = (uint16_t)p[1] | ((uint16_t)p[2] << 8);

    Motor_KickIdle();

    if(op < OP_GET_PARAM)
    {
        /* запись параметра */
        ack.type   = NTF_ACK;
        ack.cmd    = op;
        ack.status = Settings_Set(op, value) ? 0 : 1;
        MotorService_Notify((uint8_t *)&ack, sizeof(ack));
        return;
    }

    switch(op)
    {
        case OP_GET_PARAM:
            pn.type  = NTF_PARAM;
            pn.id    = (uint8_t)(value & 0xFF);
            pn.value = Settings_Get(pn.id);
            MotorService_Notify((uint8_t *)&pn, sizeof(pn));
            break;

        case OP_DUMP_ALL:
            /* Только взводим счётчик. Слать всё циклом нельзя: очередь стека
             * BLE_BUFF_NUM = 5 пакетов, за одно событие соединения радио
             * выпускает BLE_TX_NUM_EVENT = 1, а цикл прокручивается за
             * микросекунды — хвост серии просто терялся.
             * Выгружает MotorService_DumpTick() из тика задачи мотора. */
            s_dump_next = 0;
            break;

        case OP_COMMAND:
        {
            uint8_t cmd = (uint8_t)(value & 0xFF);
            uint8_t st  = 0;

            switch(cmd)
            {
                case CMD_MOTOR_STOP:     Motor_Stop();  break;
                case CMD_MOTOR_START:    Motor_Start(); break;
                /* Стирание страницы блокирует ядро на единицы десятков мс:
                 * выход ШИМ застынет, connection event пропадут. На ходу
                 * отказываем — приложение попросит остановить помпу. */
                case CMD_SAVE:
                    st = (Motor_IsStopped() && Settings_SaveNow()) ? 0 : 1;
                    break;
                case CMD_FACTORY_RESET:  Settings_Defaults(); Settings_RequestSave(); break;
                case CMD_TELEMETRY_ON:
                    tmos_start_task(Motor_TaskID, MOTOR_EVT_TELEMETRY, 1);
                    break;
                case CMD_TELEMETRY_OFF:
                    tmos_stop_task(Motor_TaskID, MOTOR_EVT_TELEMETRY);
                    break;
                case CMD_SLEEP:
                    /* Уснуть прямо здесь нельзя — мы внутри колбэка стека,
                     * соединение ещё живо. Взводим флаг и роняем линк;
                     * уснёт задача мотора на своём тике. Флаг заодно
                     * усыпляет и при sleep_tout_s = 0. */
                    Motor_ForceSleep();
                    GAPRole_TerminateLink(s_connHandle);
                    break;
                default: st = 1; break;
            }

            ack.type = NTF_ACK;
            ack.cmd  = cmd;
            ack.status = st;
            MotorService_Notify((uint8_t *)&ack, sizeof(ack));
            break;
        }

        default:
            break;
    }
}

/*********************************************************************
 * @fn      motor_ReadAttrCB
 */
static bStatus_t motor_ReadAttrCB(uint16_t connHandle, gattAttribute_t *pAttr,
                                  uint8_t *pValue, uint16_t *pLen, uint16_t offset,
                                  uint16_t maxLen, uint8_t method)
{
    *pLen = 0;
    return ATT_ERR_ATTR_NOT_FOUND;
}

/*********************************************************************
 * @fn      motor_WriteAttrCB
 */
static bStatus_t motor_WriteAttrCB(uint16_t connHandle, gattAttribute_t *pAttr,
                                   uint8_t *pValue, uint16_t len, uint16_t offset,
                                   uint8_t method)
{
    bStatus_t status = SUCCESS;
    uint16_t  uuid;

    if(pAttr->type.len != ATT_BT_UUID_SIZE) return ATT_ERR_INVALID_HANDLE;

    uuid = BUILD_UINT16(pAttr->type.uuid[0], pAttr->type.uuid[1]);

    switch(uuid)
    {
        case MOTORPROFILE_CMD_UUID:
            if(offset != 0)  return ATT_ERR_ATTR_NOT_LONG;
            if(len != 3)     return ATT_ERR_INVALID_VALUE_SIZE;
            tmos_memcpy(pAttr->pValue, pValue, 3);
            /* Обработка идёт прямо здесь: колбэк вызывается из задачи GATT,
             * не из прерывания, и работает единицы микросекунд.
             * Во флеш здесь никто не пишет: параметр ложится в ОЗУ, а
             * запись идёт отдельной командой CMD_SAVE. */
            handle_packet(pValue, len);
            break;

        /* CCCD телеметрии */
        case GATT_CLIENT_CHAR_CFG_UUID:
            status = GATTServApp_ProcessCCCWriteReq(connHandle, pAttr, pValue, len,
                                                    offset, GATT_CLIENT_CFG_NOTIFY);
            break;

        default:
            status = ATT_ERR_ATTR_NOT_FOUND;
            break;
    }

    return status;
}

/*********************************************************************
 * @fn      motor_HandleConnStatusCB
 */
static void motor_HandleConnStatusCB(uint16_t connHandle, uint8_t changeType)
{
    if(connHandle == LOOPBACK_CONNHANDLE) return;

    if((changeType == LINKDB_STATUS_UPDATE_REMOVED) ||
       ((changeType == LINKDB_STATUS_UPDATE_STATEFLAGS) && (!linkDB_Up(connHandle))))
    {
        GATTServApp_InitCharCfg(connHandle, dataConfig);
    }
}
