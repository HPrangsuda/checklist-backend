package com.acme.checklist.scheduler;

/**
 * ชื่อกุญแจของแต่ละ job — ต้องไม่ซ้ำกันทั้งระบบ
 * (job ไหนใช้ชื่อซ้ำกัน จะถือว่าเป็น job เดียวกันและกันกันเอง)
 */
public final class SchedulerLocks {

    private SchedulerLocks() {}

    // ---- Calibration ----
    public static final String CALIBRATION_COPY_NEXT_YEAR = "calibration_copy_next_year";
    public static final String CALIBRATION_NOTIFY_DAILY   = "calibration_notify_responsible_supervisor";
    public static final String CALIBRATION_NOTIFY_MANAGER = "calibration_notify_manager";
    public static final String CALIBRATION_NOTIFY_ADMINS  = "calibration_notify_admins";

    // ---- Maintenance ----
    public static final String MAINTENANCE_COPY_NEXT_YEAR = "maintenance_copy_next_year";
    public static final String MAINTENANCE_NOTIFY_DAILY   = "maintenance_notify_responsible_supervisor";
    public static final String MAINTENANCE_NOTIFY_MANAGER = "maintenance_notify_manager";
    public static final String MAINTENANCE_NOTIFY_ADMINS  = "maintenance_notify_admins";

    // ---- Checklist ----
    public static final String CHECKLIST_OVERDUE_WEEKLY       = "checklist_overdue_weekly";
    public static final String CHECKLIST_OVERDUE_MONTHLY      = "checklist_overdue_monthly";
    public static final String CHECKLIST_RESET_BY_RESET_TIME  = "checklist_reset_by_reset_time";
    public static final String CHECKLIST_AUTO_SAVE_WEEKLY     = "checklist_auto_save_weekly";
    public static final String CHECKLIST_AUTO_SAVE_MONTHLY    = "checklist_auto_save_monthly";

    // ---- KPI ----
    public static final String KPI_CREATE_MONTHLY    = "kpi_create_monthly";
    public static final String KPI_RECALCULATE_DAILY = "kpi_recalculate_daily";

    // ---- Lark sync ----
    public static final String DEPARTMENT_SYNC_LARK = "department_sync_lark";

    /*
     * เลือกเวลาล็อกตามรูปแบบของ method:
     *
     * ASYNC  — method จบด้วย .subscribe() → method return ทันที งานจริงรันเบื้องหลัง
     *          ต้องบังคับถือกุญแจขั้นต่ำ ไม่อย่างนั้นกุญแจถูกปล่อยภายในไม่กี่ ms
     *          แล้ว instance ที่นาฬิกาช้ากว่านิดเดียวจะรันซ้ำ
     *
     * BLOCKING — method จบด้วย .block() → กุญแจถูกถือตลอดเวลาที่ทำงานจริงอยู่แล้ว
     *          ขั้นต่ำแค่กันนาฬิกาแต่ละเครื่องคลาดกันเล็กน้อย
     */
    public static final String ASYNC_LOCK_AT_LEAST    = "5m";
    public static final String ASYNC_LOCK_AT_MOST     = "30m";
    public static final String BLOCKING_LOCK_AT_LEAST = "1m";
    public static final String BLOCKING_LOCK_AT_MOST  = "30m";

    // ชื่อเดิมที่ Calibration / Maintenance ใช้อยู่ (ค่าเท่ากับ ASYNC)
    public static final String NOTIFY_LOCK_AT_LEAST = ASYNC_LOCK_AT_LEAST;
    public static final String NOTIFY_LOCK_AT_MOST  = ASYNC_LOCK_AT_MOST;
    public static final String COPY_LOCK_AT_LEAST   = "30m";
    public static final String COPY_LOCK_AT_MOST    = "1h";
}