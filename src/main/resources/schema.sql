CREATE TABLE IF NOT EXISTS machine (
    id BIGSERIAL PRIMARY KEY,
    machine_code VARCHAR(15),
    machine_name VARCHAR(255),
    machine_group_id VARCHAR(2),
    machine_type_id VARCHAR(2),
    machine_status VARCHAR(50),
    check_status VARCHAR(50),
    model VARCHAR(100),
    brand VARCHAR(100),
    serial_number VARCHAR(100),
    business_unit VARCHAR(10),
    department VARCHAR(20),
    register_id VARCHAR(10),
    register_date DATE,
    cancel_date DATE,
    reason_cancel VARCHAR(255),
    is_calibration BOOLEAN,
    certificate_period VARCHAR(50),
    maintenance_period VARCHAR(50),
    image TEXT,
    responsible_person_id BIGINT,
    responsible_person_name VARCHAR(255),
    supervisor_id BIGINT,
    manager_id BIGINT,
    work_instruction TEXT,
    machine_number VARCHAR(50),
    qr_code VARCHAR(255),
    reset_period VARCHAR(100),
    note TEXT,
    last_review DATE,
    review_by VARCHAR(10),
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS machine_type (
    id BIGSERIAL PRIMARY KEY,
    machine_group_id VARCHAR(2),
    machine_group_name VARCHAR(200),
    machine_type_id VARCHAR(2),
    machine_type_name VARCHAR(200),
    status VARCHAR(25),
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS question (
    id BIGSERIAL PRIMARY KEY,
    detail VARCHAR(255),
    description VARCHAR(500),
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS department (
    id BIGSERIAL PRIMARY KEY,
    business_unit VARCHAR(10),
    department VARCHAR(10),
    department_code VARCHAR(5),
    division VARCHAR(10),
    status VARCHAR(25)
);

CREATE TABLE IF NOT EXISTS calibration_record (
    id BIGSERIAL PRIMARY KEY,
    machine_code VARCHAR(15),
    machine_name VARCHAR(255),
    years VARCHAR(4),
    due_date DATE,
    start_date DATE,
    certificate_date DATE,
    results VARCHAR(50),
    criteria VARCHAR(255),
    measuring_range VARCHAR(255),
    accuracy VARCHAR(255),
    calibration_range VARCHAR(255),
    calibration_status VARCHAR(100),
    status_follow VARCHAR(100),
    pr_po_date DATE,
    attachment VARCHAR(255),
    note TEXT,
    permissible_capacity VARCHAR(255),
    comment TEXT,
    resolution VARCHAR(255),
    max_uncertainty VARCHAR(255),
    mpe VARCHAR(255),
    check_mpe VARCHAR(255),
    check_resolution VARCHAR(255),
    check_result VARCHAR(255),
    reason_not_pass TEXT,
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS checklist_record (
    id BIGSERIAL PRIMARY KEY,
    check_type VARCHAR(50),
    recheck BOOLEAN,
    machine_code VARCHAR(15),
    machine_name VARCHAR(255),
    machine_status VARCHAR(50),
    machine_checklist TEXT,
    machine_note TEXT,
    image VARCHAR(255),
    user_id VARCHAR(10),
    user_name VARCHAR(100),
    supervisor BIGINT,
    date_supervisor_checked TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    manager BIGINT,
    date_manager_checked TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    checklist_status VARCHAR(100),
    reason_not_checked TEXT,
    job_detail TEXT,
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS kpi (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT,
    employee_name VARCHAR(100),
    years VARCHAR(4),
    months VARCHAR(2),
    check_all BIGINT,
    checked BIGINT,
    manager_id BIGINT,
    supervisor_id BIGINT
);

CREATE TABLE IF NOT EXISTS machine_checklist (
    id BIGSERIAL PRIMARY KEY,
    machine_code VARCHAR(15),
    question_id BIGINT,
    is_choice BOOLEAN,
    check_status BOOLEAN DEFAULT FALSE,
    reset_time VARCHAR(25),
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS maintenance_checklist (
    id BIGSERIAL PRIMARY KEY,
    machine_code VARCHAR(15),
    question_id BIGINT,
    is_choice BOOLEAN,
    check_status BOOLEAN DEFAULT FALSE,
    reset_time VARCHAR(25),
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS maintenance_record (
    id BIGSERIAL PRIMARY KEY,
    machine_code VARCHAR(15),
    machine_name VARCHAR(255),
    years VARCHAR(4),
    round INTEGER,
    due_date DATE,
    plan_date DATE,
    start_date Date,
    actual_date DATE,
    status VARCHAR(100),
    maintenance_by VARCHAR(100),
    responsible_maintenance BIGINT,
    note TEXT,
    attachment VARCHAR(255),
    maintenance_type VARCHAR(255),
    checklist_record_id BIGINT,
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS register_request (
    id BIGSERIAL PRIMARY KEY,
    machine_name VARCHAR(255),
    brand VARCHAR(100),
    model VARCHAR(100),
    serial_number VARCHAR(100),
    price DECIMAL,
    quantity INTEGER,
    watt INTEGER,
    horse_power INTEGER,
    department VARCHAR(3),
    responsible_id BIGINT,
    supervisor_id BIGINT,
    manager_id BIGINT,
    note TEXT,
    attachment TEXT,
    maintenance TEXT,
    calibration TEXT,
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS member (
    id BIGSERIAL PRIMARY KEY ,
    employee_id VARCHAR(15),
    department_id VARCHAR(3),
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    avatar_key VARCHAR(255),
    email VARCHAR(255),
    mobiles VARCHAR(12),
    user_name VARCHAR(50),
    password VARCHAR(100),
    role_type varchar(50),
    supervisor BIGINT,
    manager BIGINT,
    languages VARCHAR(10),
    status VARCHAR(10),
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS refresh_token (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL,
    token TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS responsible_history (
    id BIGSERIAL PRIMARY KEY,
    machine_code VARCHAR(50) NOT NULL,
    responsible_person_id BIGINT NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_mrh_machine FOREIGN KEY (machine_code) REFERENCES machine(machine_code)
);

CREATE INDEX IF NOT EXISTS idx_mrh_machine_code       ON responsible_history(machine_code);
CREATE INDEX IF NOT EXISTS idx_mrh_responsible_person ON responsible_history(responsible_person_id);
CREATE INDEX IF NOT EXISTS idx_mrh_effective_range    ON responsible_history(effective_from, effective_to);
