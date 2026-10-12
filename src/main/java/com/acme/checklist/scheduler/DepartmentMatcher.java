package com.acme.checklist.scheduler;

import com.acme.checklist.entity.Department;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DepartmentMatcher {

    private final Map<String, String> byCode = new HashMap<>();
    private final Map<String, String> byName = new HashMap<>();
    private final Map<String, String> byId   = new HashMap<>();

    private DepartmentMatcher() {}

    static DepartmentMatcher of(List<Department> departments) {
        DepartmentMatcher m = new DepartmentMatcher();
        for (Department d : departments) {
            if (d.getId() == null) continue;
            String key = String.valueOf(d.getId());
            putIfPresent(m.byCode, d.getDepartmentCode(), key);
            putIfPresent(m.byName, d.getDepartment(), key);
            m.byId.put(key, key);
        }
        return m;
    }

    /** คืน key กลางของแผนก หรือ null ถ้าจับคู่ไม่ได้ */
    String resolve(String raw) {
        String k = normalize(raw);
        if (k == null) return null;
        if (byCode.containsKey(k)) return byCode.get(k);
        if (byName.containsKey(k)) return byName.get(k);
        return byId.get(k);
    }

    private static void putIfPresent(Map<String, String> map, String raw, String key) {
        String k = normalize(raw);
        if (k != null) map.putIfAbsent(k, key);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }
}