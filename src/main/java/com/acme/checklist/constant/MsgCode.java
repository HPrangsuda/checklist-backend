package com.acme.checklist.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MsgCode {

    // ── Auth ─────────────────────────────────────────────────────
    AUTH_SIGNIN_SUCCESS("Signin successful"),
    AUTH_SIGNIN_FAILED("Invalid username or password"),
    AUTH_ME_SUCCESS("Get session successful"),
    AUTH_UNAUTHORIZED("Unauthorized"),
    AUTH_REFRESH_SUCCESS("Token refreshed"),
    AUTH_REFRESH_FAILED("Invalid or expired refresh token"),
    AUTH_REFRESH_TOKEN_MISSING("Refresh token is required"),
    AUTH_SIGNOUT_SUCCESS("Signout successful"),
    LARK_SIGNIN_REQUESTED("Lark signin requested"),
    MEMBER_NOT_FOUND("Member not found"),

    // ── Pagination & List — common ────────────────────────────────
    PG001("No records found"),
    PG002("Data loaded successfully"),
    PG003("Unable to load data"),
    PL001("No records found"),
    PL002("Data loaded successfully"),
    PL003("Unable to load data"),

    // ── Machine ───────────────────────────────────────────────────
    MS001("Machine created successfully"),
    MS002("Machine created failed"),
    MS003("Machine updated successfully"),
    MS004("Machine updated failed"),
    MS005("Machine with id not found"),
    MS006("Machine successfully deleted"),
    MS007("Machine deleted failed"),
    MS008("Department cannot be empty"),
    MS009("Machine code cannot be empty"),
    MS010("Machine name cannot be empty"),
    MS011("Machine status cannot be empty"),
    MS012("Machine type name cannot be empty"),
    MS013("Reset period cannot be empty"),
    MS020("Responsible person id cannot be empty"),
    MS021("Maintenance frequency cannot be empty"),
    MS022("Groups cannot be empty"),

    // ── Machine Type ──────────────────────────────────────────────
    MT001("Machine type created successfully"),
    MT002("Machine type created failed"),
    MT003("Machine group name cannot be empty"),
    MT004("Machine type name cannot be empty"),
    MT005("Machine type already exists in this group"),
    MT006("Machine group not found"),
    MT007("Machine group name already exists"),
    MT008("Machine type updated successfully"),
    MT009("Machine type updated failed"),
    MT010("Machine type id is required"),
    MT011("Machine type status is required"),

    // ── Member ────────────────────────────────────────────────────
    MB001("Member created successfully"),
    MB002("Member created failed"),
    MB003("Employee ID is required"),
    MB004("First name is required"),
    MB005("Last name is required"),
    MB006("Mobile is required"),
    MB007("Username is required"),
    MB008("Password is required"),
    MB009("Role type is required"),
    MB010("Employee ID already exists"),
    MB011("Username already exists"),
    MB012("Email already exists"),
    MB013("Member loaded successfully"),
    MB014("Member not found"),
    MB015("Failed to get member"),
    MB016("Member ID is required"),
    MB017("Member updated successfully"),
    MB018("Member update failed"),
    MB019("Member deleted successfully"),
    MB020("Member delete failed"),
    MB021("Member not found for deletion"),
    MB022("Mobile number already exists"),

    // ── Question ──────────────────────────────────────────────────
    QB001("Question created successfully"),
    QB002("Question updated successfully"),
    QB003("Question detail is required"),
    QB004("Question detail already exists"),
    QB005("Question ID is required"),
    QB006("Question deleted successfully"),
    QB007("Question delete failed"),
    QB008("Question not found for deletion"),
    QB009("Question not found"),

    // ── Machine Checklist ─────────────────────────────────────────
    MC001("Checklist created successfully"),
    MC002("Checklist updated successfully"),
    MC003("Machine code is required"),
    MC004("Question ID is required"),
    MC005("Checklist ID is required"),
    MC006("Checklist deleted successfully"),
    MC007("Checklist delete failed"),
    MC008("Checklist not found for deletion"),
    MC009("Checklist loaded successfully"),
    MC010("Checklist item not found"),
    MC011("Checklist status reset successfully"),
    MC012("Failed to reset checklist status"),
    MC013("isChoice is required"),
    MC014("Reset time is required");

    private final String message;
}