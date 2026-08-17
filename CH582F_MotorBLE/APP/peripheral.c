/********************************************************************************
 * peripheral.c — роль GAP Peripheral
 *
 * Урезанная версия примера Peripheral из CH58xBLE EVT: убраны RSSI, смена PHY,
 * SimpleProfile; добавлен наш motor_service.
 *******************************************************************************/
#include "CONFIG.h"
#include "devinfoservice.h"
#include "peripheral.h"
#include "motor_service.h"
#include "motor_task.h"
#include "buzzer.h"

uint8_t Peripheral_TaskID = INVALID_TASK_ID;

static uint16_t s_connHandle  = GAP_CONNHANDLE_INIT;
static uint8_t  s_adv_inhibit = 0;   /* выставляется перед уходом в сон */

/* Имя устройства в scan response */
static uint8_t scanRspData[] = {
    0x0C,
    GAP_ADTYPE_LOCAL_NAME_COMPLETE,
    'M', 'o', 't', 'o', 'r', 'C', 't', 'r', 'l', '1',

    0x05,
    GAP_ADTYPE_SLAVE_CONN_INTERVAL_RANGE,
    LO_UINT16(DEFAULT_DESIRED_MIN_CONN_INTERVAL),
    HI_UINT16(DEFAULT_DESIRED_MIN_CONN_INTERVAL),
    LO_UINT16(DEFAULT_DESIRED_MAX_CONN_INTERVAL),
    HI_UINT16(DEFAULT_DESIRED_MAX_CONN_INTERVAL),

    0x02,
    GAP_ADTYPE_POWER_LEVEL,
    0};

/* Реклама: флаги + UUID нашего сервиса, чтобы приложение фильтровало по нему */
static uint8_t advertData[] = {
    0x02,
    GAP_ADTYPE_FLAGS,
    GAP_ADTYPE_FLAGS_GENERAL | GAP_ADTYPE_FLAGS_BREDR_NOT_SUPPORTED,

    0x03,
    GAP_ADTYPE_16BIT_MORE,
    LO_UINT16(MOTORPROFILE_SERV_UUID),
    HI_UINT16(MOTORPROFILE_SERV_UUID)};

static uint8_t attDeviceName[GAP_DEVICE_NAME_LEN] = "MotorCtrl1";

static void peripheralStateNotificationCB(gapRole_States_t newState, gapRoleEvent_t *pEvent);
static void peripheralParamUpdateCB(uint16_t connHandle, uint16_t connInterval,
                                    uint16_t connSlaveLatency, uint16_t connTimeout);
static void Peripheral_ProcessTMOSMsg(tmos_event_hdr_t *pMsg);

static gapRolesCBs_t Peripheral_PeripheralCBs = {
    peripheralStateNotificationCB,
    NULL,
    peripheralParamUpdateCB};

static gapBondCBs_t Peripheral_BondMgrCBs = {NULL, NULL, NULL};

/*********************************************************************
 * @fn      Peripheral_Init
 */
void Peripheral_Init(void)
{
    Peripheral_TaskID = TMOS_ProcessEventRegister(Peripheral_ProcessEvent);

    {
        uint8_t  adv_enable = TRUE;
        uint16_t min_i = DEFAULT_DESIRED_MIN_CONN_INTERVAL;
        uint16_t max_i = DEFAULT_DESIRED_MAX_CONN_INTERVAL;

        GAPRole_SetParameter(GAPROLE_ADVERT_ENABLED, sizeof(uint8_t), &adv_enable);
        GAPRole_SetParameter(GAPROLE_SCAN_RSP_DATA, sizeof(scanRspData), scanRspData);
        GAPRole_SetParameter(GAPROLE_ADVERT_DATA, sizeof(advertData), advertData);
        GAPRole_SetParameter(GAPROLE_MIN_CONN_INTERVAL, sizeof(uint16_t), &min_i);
        GAPRole_SetParameter(GAPROLE_MAX_CONN_INTERVAL, sizeof(uint16_t), &max_i);
    }

    {
        uint16_t advInt = DEFAULT_ADVERTISING_INTERVAL;
        GAP_SetParamValue(TGAP_DISC_ADV_INT_MIN, advInt);
        GAP_SetParamValue(TGAP_DISC_ADV_INT_MAX, advInt);
    }

    /* Спаривание без MITM: пин-кода на устройстве нет, вводить негде.
     * Если нужна защита от чужого доступа к настройкам — включи MITM
     * и статический пароль, но тогда ioCap должен соответствовать. */
    {
        uint8_t pairMode = GAPBOND_PAIRING_MODE_WAIT_FOR_REQ;
        uint8_t mitm     = FALSE;
        uint8_t bonding  = TRUE;
        uint8_t ioCap    = GAPBOND_IO_CAP_NO_INPUT_NO_OUTPUT;

        GAPBondMgr_SetParameter(GAPBOND_PERI_PAIRING_MODE, sizeof(uint8_t), &pairMode);
        GAPBondMgr_SetParameter(GAPBOND_PERI_MITM_PROTECTION, sizeof(uint8_t), &mitm);
        GAPBondMgr_SetParameter(GAPBOND_PERI_IO_CAPABILITIES, sizeof(uint8_t), &ioCap);
        GAPBondMgr_SetParameter(GAPBOND_PERI_BONDING_ENABLED, sizeof(uint8_t), &bonding);
    }

    GGS_AddService(GATT_ALL_SERVICES);
    GATTServApp_AddService(GATT_ALL_SERVICES);
    DevInfo_AddService();
    MotorService_AddService();

    GGS_SetParameter(GGS_DEVICE_NAME_ATT, sizeof(attDeviceName), attDeviceName);

    tmos_set_event(Peripheral_TaskID, SBP_START_DEVICE_EVT);
}

uint8_t Peripheral_IsConnected(void)
{
    return (s_connHandle != GAP_CONNHANDLE_INIT);
}

void Peripheral_StopAdvertising(void)
{
    uint8_t adv_enable = FALSE;
    s_adv_inhibit = 1;
    GAPRole_SetParameter(GAPROLE_ADVERT_ENABLED, sizeof(uint8_t), &adv_enable);
}

void Peripheral_StartAdvertising(void)
{
    uint8_t adv_enable = TRUE;
    s_adv_inhibit = 0;
    GAPRole_SetParameter(GAPROLE_ADVERT_ENABLED, sizeof(uint8_t), &adv_enable);
}

/*********************************************************************
 * @fn      Peripheral_ProcessEvent
 */
uint16_t Peripheral_ProcessEvent(uint8_t task_id, uint16_t events)
{
    if(events & SYS_EVENT_MSG)
    {
        uint8_t *pMsg;
        if((pMsg = tmos_msg_receive(Peripheral_TaskID)) != NULL)
        {
            Peripheral_ProcessTMOSMsg((tmos_event_hdr_t *)pMsg);
            tmos_msg_deallocate(pMsg);
        }
        return (events ^ SYS_EVENT_MSG);
    }

    if(events & SBP_START_DEVICE_EVT)
    {
        GAPRole_PeripheralStartDevice(Peripheral_TaskID, &Peripheral_BondMgrCBs,
                                      &Peripheral_PeripheralCBs);
        return (events ^ SBP_START_DEVICE_EVT);
    }

    if(events & SBP_PARAM_UPDATE_EVT)
    {
        GAPRole_PeripheralConnParamUpdateReq(s_connHandle,
                                             DEFAULT_DESIRED_MIN_CONN_INTERVAL,
                                             DEFAULT_DESIRED_MAX_CONN_INTERVAL,
                                             DEFAULT_DESIRED_SLAVE_LATENCY,
                                             DEFAULT_DESIRED_CONN_TIMEOUT,
                                             Peripheral_TaskID);
        return (events ^ SBP_PARAM_UPDATE_EVT);
    }

    return 0;
}

/*********************************************************************
 * @fn      Peripheral_ProcessTMOSMsg
 */
static void Peripheral_ProcessTMOSMsg(tmos_event_hdr_t *pMsg)
{
    switch(pMsg->event)
    {
        case GAP_MSG_EVENT:
            break;

        case GATT_MSG_EVENT:
            break;

        default:
            break;
    }
}

/*********************************************************************
 * @fn      peripheralStateNotificationCB
 */
static void peripheralStateNotificationCB(gapRole_States_t newState, gapRoleEvent_t *pEvent)
{
    switch(newState & GAPROLE_STATE_ADV_MASK)
    {
        case GAPROLE_STARTED:
            PRINT("BLE started\n");
            break;

        case GAPROLE_ADVERTISING:
            if(pEvent->gap.opcode == GAP_LINK_TERMINATED_EVENT)
            {
                s_connHandle = GAP_CONNHANDLE_INIT;
                MotorService_SetConnHandle(INVALID_CONNHANDLE);
                tmos_stop_task(Motor_TaskID, MOTOR_EVT_TELEMETRY);
                PRINT("Disconnected, reason %x\n", pEvent->linkTerminate.reason);
            }
            break;

        case GAPROLE_CONNECTED:
            if(pEvent->gap.opcode == GAP_LINK_ESTABLISHED_EVENT)
            {
                gapEstLinkReqEvent_t *e = (gapEstLinkReqEvent_t *)pEvent;
                s_connHandle = e->connectionHandle;
                MotorService_SetConnHandle(s_connHandle);
                Motor_KickIdle();
                buzzer_ok();        /* телефон подключился, слышно с корпуса */
                tmos_start_task(Peripheral_TaskID, SBP_PARAM_UPDATE_EVT, SBP_PARAM_UPDATE_DELAY);
                PRINT("Connected, int %d\n", e->connInterval);
            }
            break;

        case GAPROLE_WAITING:
            if(pEvent->gap.opcode == GAP_LINK_TERMINATED_EVENT)
            {
                s_connHandle = GAP_CONNHANDLE_INIT;
                MotorService_SetConnHandle(INVALID_CONNHANDLE);
                tmos_stop_task(Motor_TaskID, MOTOR_EVT_TELEMETRY);
            }
            /* Реклама заново — но не если готовимся ко сну, иначе стек
             * будет будить чип по своему RTC и сон не наступит. */
            if(!s_adv_inhibit)
            {
                uint8_t adv_enable = TRUE;
                GAPRole_SetParameter(GAPROLE_ADVERT_ENABLED, sizeof(uint8_t), &adv_enable);
            }
            break;

        default:
            break;
    }
}

/*********************************************************************
 * @fn      peripheralParamUpdateCB
 */
static void peripheralParamUpdateCB(uint16_t connHandle, uint16_t connInterval,
                                    uint16_t connSlaveLatency, uint16_t connTimeout)
{
    PRINT("Conn param: int %d lat %d tout %d\n", connInterval, connSlaveLatency, connTimeout);
}
