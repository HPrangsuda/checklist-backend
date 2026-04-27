package com.acme.checklist.service;

import com.acme.checklist.entity.Department;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.department.DepartmentDTO;
import com.acme.checklist.payload.department.DepartmentListDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentService {
    private final R2dbcEntityTemplate template;
    private final CommonService commonService;

    public Mono<ApiResponse<Void>> create(DepartmentDTO dto) {
        return validateData(dto)
                .flatMap(validateDTO -> {
                    Department department = buildFromDTO(validateDTO);
                    return commonService.save(department,Department.class)
                            .then(Mono.just(ApiResponse.success("MS001")));

                })
                .onErrorResume(e -> {
                    log.error("Failed to create the department: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("MS002", e.getMessage()));
                });
    }

    public Mono<DepartmentDTO> validateData(DepartmentDTO departmentDTO) {
        if (departmentDTO.getBusinessUnit() == null || departmentDTO.getBusinessUnit().isEmpty()) {
            return Mono.error(new ThrowException("MS008"));
        }
        if (departmentDTO.getDepartment() == null || departmentDTO.getDepartment().isEmpty()) {
            return Mono.error(new ThrowException("MS009"));
        }
        if (departmentDTO.getDepartmentCode() == null || departmentDTO.getDepartmentCode().isEmpty()) {
            return Mono.error(new ThrowException("MS010"));
        }

        Criteria criteria = Criteria.where("departmentCode").is(departmentDTO.getDepartmentCode());

        Query query = Query.query(criteria);
        return template.select(query, Department.class)
                .collectList()
                .flatMap(existingDepartment -> {
                    return Mono.just(departmentDTO);
                });
    }

    public Department buildFromDTO(DepartmentDTO departmentDTO) {
        return Department.builder()
                .id(departmentDTO.getId())
                .businessUnit(departmentDTO.getBusinessUnit())
                .department(departmentDTO.getDepartment())
                .departmentCode(departmentDTO.getDepartmentCode())
                .division(departmentDTO.getDivision())
                .build();
    }

    public Mono<ListResponse<List<DepartmentListDTO>>> getList(String keyword, List<Long> ids, int index, int size) {
        Pageable pageable = PageRequest.of(index, size, Sort.by(Sort.Direction.DESC, "id"));
        boolean hasIds = ids != null && !ids.isEmpty();
        return commonService.getSelectedItems(hasIds, ids, index, size, Department.class)
                .flatMap(selectedItems -> {
                    Criteria criteria = Criteria.empty();
                    if (StringUtils.hasText(keyword) && hasIds) {
                        criteria = Criteria
                                .where("department").like("%" + keyword + "%").ignoreCase(true)
                                .and("id").notIn(ids);
                    } else if (StringUtils.hasText(keyword)) {
                        criteria = Criteria
                                .where("business_unit").like("%" + keyword + "%").ignoreCase(true);
                    } else if (hasIds) {
                        criteria = Criteria
                                .where("id").notIn(ids);
                    }
                    return commonService.getPagedList(
                            index,
                            size,
                            criteria,
                            selectedItems,
                            pageable,
                            Department.class,
                            this::convertDepartmentListDTOs);
                });
    }

    private Flux<DepartmentListDTO> convertDepartmentListDTOs(List<Department> departments) {
        return Flux.fromIterable(departments)
                .map(DepartmentListDTO::from);
    }
}
