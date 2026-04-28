package com.acme.checklist.service;

import com.acme.checklist.entity.ChecklistRecord;
import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.MaintenanceChecklist;
import com.acme.checklist.entity.MaintenanceRecord;
import com.acme.checklist.entity.Question;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.file.FileUploadDTO;
import com.acme.checklist.payload.maintenance.MaintenanceChDTO;
import com.acme.checklist.payload.maintenance.MaintenanceDTO;
import com.acme.checklist.payload.maintenance.MaintenanceSaveDTO;
import com.acme.checklist.payload.question.QuestionDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceChService {

    private final R2dbcEntityTemplate template;
    private final CommonService        commonService;
    private final FileStorageService   fileStorageService;
    private final ObjectMapper         objectMapper;

    // =========================
    // CREATE ITEM
    // =========================
    public Mono<ApiResponse<Void>> createItem(com.acme.checklist.payload.machineChecklist.MachineChDTO dto) {
        MaintenanceChecklist entity = MaintenanceChecklist.builder()
                .machineCode(dto.getMachineCode())
                .questionId(dto.getQuestionId())
                .isChoice(dto.getIsChoice())
                .checkStatus(dto.getCheckStatus() != null ? dto.getCheckStatus() : false)
                .resetTime(dto.getResetTime())
                .build();
        return commonService.save(entity, MaintenanceChecklist.class)
                .thenReturn(ApiResponse.<Void>success("MC001"))
                .onErrorResume(e -> {
                    log.error("Failed to create maintenance checklist item", e);
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    return Mono.error(new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, msg));
                });
    }

    // =========================
    // DELETE ITEMS
    // =========================
    public Mono<ApiResponse<Void>> deleteItems(List<Long> ids) {
        return commonService.deleteEntitiesByIds(
                ids,
                MaintenanceChecklist.class,
                "MC008",
                "MC006",
                "MC007",
                MaintenanceChecklist::getMachineCode,
                names -> Mono.empty()
        );
    }

    // =========================
    // GET BY MACHINE CODE
    // =========================

    /**
     * ดึง MaintenanceChecklist ทั้งหมดของ machine พร้อม join question
     * ใช้แสดงในแท็บ maintenance ของ machine view
     */
    public Mono<List<MaintenanceChDTO>> getByMachineCode(String machineCode) {
        return template.select(
                        Query.query(
                                Criteria.where("machine_code").is(machineCode)
                        ).sort(org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.ASC, "id")),
                        MaintenanceChecklist.class)
                .collectList()
                .flatMap(items -> {
                    if (items.isEmpty()) return Mono.just(java.util.List.<MaintenanceChDTO>of());

                    List<Long> questionIds = items.stream()
                            .map(MaintenanceChecklist::getQuestionId)
                            .distinct()
                            .collect(java.util.stream.Collectors.toList());

                    return template.select(
                                    Query.query(Criteria.where("id").in(questionIds)),
                                    Question.class)
                            .collectList()
                            .map(questions -> {
                                Map<Long, Question> qMap = questions.stream()
                                        .collect(java.util.stream.Collectors.toMap(
                                                Question::getId, q -> q));
                                return items.stream().map(item -> {
                                    Question q = qMap.get(item.getQuestionId());
                                    return MaintenanceChDTO.builder()
                                            .id(item.getId())
                                            .machineCode(item.getMachineCode())
                                            .isChoice(item.getIsChoice())
                                            .checkStatus(item.getCheckStatus())
                                            .resetTime(item.getResetTime())
                                            .question(q != null ? new QuestionDTO().from(q) : null)
                                            .build();
                                }).collect(java.util.stream.Collectors.toList());
                            });
                });
    }

    // =========================
    // GET BY MAINTENANCE ID
    // =========================

    /**
     * ดึง MaintenanceRecord ตาม id
     * แล้ว query MaintenanceChecklist ตาม machineCode
     * แล้ว join กับ Question เพื่อเอา detail/description
     */
    public Mono<ApiResponse<MaintenanceDTO>> getById(Long id) {
        return template.selectOne(
                        Query.query(Criteria.where("id").is(id)),
                        MaintenanceRecord.class)
                .switchIfEmpty(Mono.error(new RuntimeException("Maintenance record not found: " + id)))
                .flatMap(record ->
                        template.select(
                                        Query.query(
                                                Criteria.where("machine_code").is(record.getMachineCode())
                                        ).sort(org.springframework.data.domain.Sort.by(
                                                org.springframework.data.domain.Sort.Direction.ASC, "id")),
                                        MaintenanceChecklist.class)
                                .collectList()
                                .flatMap(items -> enrichWithQuestions(items, record))
                )
                .map(dto -> ApiResponse.success("MN001", dto))
                .onErrorResume(e -> {
                    log.error("Failed to get maintenance checklist: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MN002", e.getMessage()));
                });
    }

    // =========================
    // ENRICH WITH QUESTIONS
    // =========================

    private Mono<MaintenanceDTO> enrichWithQuestions(
            List<MaintenanceChecklist> items,
            MaintenanceRecord record
    ) {
        if (items.isEmpty()) {
            return Mono.just(buildDetailDTO(record, List.of()));
        }

        List<Long> questionIds = items.stream()
                .map(MaintenanceChecklist::getQuestionId)
                .distinct()
                .collect(Collectors.toList());

        return template.select(
                        Query.query(Criteria.where("id").in(questionIds)),
                        Question.class)
                .collectList()
                .map(questions -> {
                    Map<Long, Question> qMap = questions.stream()
                            .collect(Collectors.toMap(Question::getId, q -> q));

                    List<MaintenanceChDTO> checklistItems = items.stream()
                            .map(item -> {
                                Question q = qMap.get(item.getQuestionId());
                                return MaintenanceChDTO.builder()
                                        .id(item.getId())
                                        .machineCode(item.getMachineCode())
                                        .isChoice(item.getIsChoice())
                                        .checkStatus(item.getCheckStatus())
                                        .resetTime(item.getResetTime())
                                        .question(q != null ? new QuestionDTO().from(q) : null)
                                        .build();
                            })
                            .collect(Collectors.toList());

                    return buildDetailDTO(record, checklistItems);
                });
    }

    // =========================
    // BUILD DTO
    // =========================

    private MaintenanceDTO buildDetailDTO(MaintenanceRecord record, List<MaintenanceChDTO> items) {
        return MaintenanceDTO.builder()
                .id(record.getId())
                .machineCode(record.getMachineCode())
                .machineName(record.getMachineName())
                .years(record.getYears())
                .round(record.getRound())
                .dueDate(record.getDueDate())
                .planDate(record.getPlanDate())
                .startDate(record.getStartDate())
                .actualDate(record.getActualDate())
                .status(record.getStatus())
                .maintenanceBy(record.getMaintenanceBy())
                .responsibleMaintenance(record.getResponsibleMaintenance())
                .note(record.getNote())
                .attachment(record.getAttachment())
                .maintenanceType(record.getMaintenanceType() != null
                        ? record.getMaintenanceType().name() : null)
                .checklistItems(items)
                .build();
    }

    // =========================
    // SAVE (checklist submit)
    // =========================

    public Mono<ApiResponse<Void>> save(String requestJson, FilePart file) {
        MaintenanceSaveDTO dto;
        try {
            dto = objectMapper.readValue(requestJson, MaintenanceSaveDTO.class);
        } catch (Exception e) {
            log.error("Parse error: {}", e.getMessage());
            return Mono.just(ApiResponse.error("MN003", "Invalid request format"));
        }

        Mono<String> imageNameMono = (file != null && StringUtils.hasText(file.filename()))
                ? fileStorageService.uploadFile(file, dto.getUserName()).map(FileUploadDTO::getFileName)
                : Mono.just("");

        MaintenanceSaveDTO finalDto = dto;
        return imageNameMono
                .flatMap(imageName -> {
                    if (StringUtils.hasText(imageName)) finalDto.setImage(imageName);
                    return processSave(finalDto);
                })
                .onErrorResume(e -> {
                    log.error("Failed to save maintenance checklist: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MN004", e.getMessage()));
                });
    }

    // =========================
    // PROCESS SAVE
    // =========================

    private Mono<ApiResponse<Void>> processSave(MaintenanceSaveDTO dto) {
        ChecklistRecord record = ChecklistRecord.builder()
                .checkType("MAINTENANCE")
                .machineCode(dto.getMachineCode())
                .machineName(dto.getMachineName())
                .machineStatus(dto.getMachineStatus())
                .machineChecklist(dto.getMachineChecklist())
                .machineNote(dto.getMachineNote())
                .image(dto.getImage())
                .userId(dto.getUserId())
                .userName(dto.getUserName())
                .supervisor(Long.valueOf(dto.getSupervisor()))
                .manager(Long.valueOf(dto.getManager()))
                .jobDetail(dto.getJobDetail())
                .checklistStatus("COMPLETED")
                .recheck(false)
                .build();

        LocalDate actualDate = dto.getActualDate() != null ? dto.getActualDate() : LocalDate.now();
        String status = (dto.getDueDate() != null && actualDate.isAfter(dto.getDueDate()))
                ? "COMPLETED (LATE)" : "COMPLETED";

        return commonService.save(record, ChecklistRecord.class)
                .flatMap(savedRecord -> {
                    // อัปเดต MaintenanceRecord
                    Mono<Void> updateMaintenance = template.selectOne(
                                    Query.query(Criteria.where("id").is(dto.getMaintenanceRecordId())),
                                    MaintenanceRecord.class)
                            .switchIfEmpty(Mono.error(new RuntimeException(
                                    "Maintenance record not found: " + dto.getMaintenanceRecordId())))
                            .flatMap(maintenance -> {
                                maintenance.setActualDate(actualDate);
                                maintenance.setStatus(status);
                                maintenance.setMaintenanceBy(dto.getMaintenanceBy());
                                maintenance.setResponsibleMaintenance(dto.getResponsibleMaintenance());
                                maintenance.setAttachment(dto.getImage());
                                maintenance.setNote(dto.getMachineNote());
                                maintenance.setChecklistRecordId(savedRecord.getId());
                                return template.update(maintenance).then();
                            });

                    // อัปเดต Machine status
                    Mono<Void> updateMachine = template.update(Machine.class)
                            .matching(Query.query(Criteria.where("machine_code").is(dto.getMachineCode())))
                            .apply(Update.update("machine_status", dto.getMachineStatus()))
                            .then();

                    return updateMaintenance
                            .then(updateMachine)
                            .thenReturn(ApiResponse.<Void>success("MN005"));
                })
                .onErrorResume(e -> {
                    log.error("Failed to process maintenance save: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MN004", e.getMessage()));
                });
    }
}