package com.acme.checklist.service;

import com.acme.checklist.entity.Member;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.MemberPrincipal;
import com.acme.checklist.payload.PagedResponse;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommonService {

    private final R2dbcEntityTemplate template;

    public <T> Mono<T> save(T entity, Class<T> clazz) {
        return template.insert(clazz).using(entity);
    }

    public <T> Mono<T> update(Long id, Update update, Class<T> clazz) {
        return template.update(clazz)
                .matching(Query.query(Criteria.where("id").is(id)))
                .apply(update)
                .then(getById(id, clazz));
    }

    public <T> Mono<List<T>> getByIds(List<Long> ids, Class<T> clazz) {
        if (ids == null || ids.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }
        Query query = Query.query(Criteria.where("id").in(ids));
        return template.select(query, clazz).collectList();
    }

    public Mono<Void> deleteByQuery(Query query, Class<?> clazz) {
        return template.delete(query, clazz).then();
    }

    //delete
    public <T> Mono<ApiResponse<Void>> deleteEntitiesByIds(
            List<Long> ids,
            Class<T> entityClass,
            String notFoundError,
            String successMessage,
            String errorMessage,
            Function<T, String> nameExtractor,
            Function<List<String>, Mono<Void>> postDeleteTask) {
        return getByIds(ids, entityClass)
                .flatMap(existingEntities -> {
                    if (existingEntities.size() < ids.size()) {
                        return Mono.just(ApiResponse.<Void>error(notFoundError));
                    }
                    List<String> names;
                    if (nameExtractor == null) {
                        names = new ArrayList<>();
                    } else {
                        names = existingEntities.stream()
                                .map(nameExtractor)
                                .toList();
                    }
                    Query query = Query.query(Criteria.where("id").in(ids));
                    return deleteByQuery(query, entityClass)
                            .then(Mono.defer(() -> postDeleteTask.apply(names)))
                            .then(Mono.just(ApiResponse.success(successMessage)));
                })
                .onErrorResume(e -> Mono.just(ApiResponse.error(errorMessage, e.getMessage())));
    }

    public <T> Mono<Void> delete(Long id, Class<T> clazz) {
        return template.delete(clazz)
                .matching(Query.query(Criteria.where("id").is(id)))
                .all()
                .then();
    }

    public <T> Mono<T> getById(Long id, Class<T> clazz) {
        return template.selectOne(
                Query.query(Criteria.where("id").is(id)),
                clazz
        );
    }

    //audit
    public Mono<Context> auditContext() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth != null && auth.isAuthenticated())
                .map(auth -> {
                    Object principal = auth.getPrincipal();
                    if (principal instanceof MemberPrincipal mp) {
                        return Context.of(
                                "X-Member-Id", mp.memberId(),
                                "X-Department-Id", mp.departmentId()
                        );
                    }
                    return Context.empty();
                });
    }

    //page
    public <T, R> Mono<PagedResponse<R>> executePagedQuery(
            int index,
            int size,
            Query query,
            Criteria criteria,
            Class<T> clazz,
            Function<List<T>, Flux<R>> converter
    ) {
        return Mono.zip(
                        getByDataList(query, clazz),
                        getCount(criteria, clazz)
                )
                .flatMap(tuple -> {
                    List<T> results = tuple.getT1();
                    Long totalCount = tuple.getT2();
                    if (results.isEmpty()) {
                        return Mono.just(PagedResponse.<R>success("PG001", index, size));
                    }
                    int totalPages = (int) Math.ceil((double) totalCount / size);
                    return converter.apply(results)
                            .collectList()
                            .map(dtoList -> PagedResponse.success("PG002", dtoList, totalPages, totalCount, index, size));
                })
                .onErrorResume(e -> Mono.just(PagedResponse.error("PG003", e.getMessage())));
    }

    public <T> Mono<Long> getCount(@Nullable Criteria criteria, Class<T> clazz) {
        Query query = (criteria != null) ? Query.query(criteria) : Query.empty();
        return template.count(query, clazz);
    }

    public <T> Mono<List<T>> getByDataList(Query pageQuery, Class<T> clazz) {
        return template.select(pageQuery, clazz).collectList();
    }

    public Pageable pageable(int index, int size, String sortBy) {
        return PageRequest.of(index, size, Sort.by(Sort.Direction.DESC, sortBy));
    }

    public Mono<Map<Long, Member>> fetchMembersByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return Mono.just(Collections.emptyMap());
        }
        return getAllMemberByIds(ids)
                .collectMap(Member::getId);
    }

    public Flux<Member> getAllMemberByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Flux.empty();
        }
        return template
                .select(Member.class)
                .matching(Query.query(Criteria.where("id").in(ids)))
                .all();
    }

    //list
    public <T>Mono<List<T>> getSelectedItems(Boolean hasIds, List<Long> ids, int index, int size, Class<T> clazz) {
        if (Boolean.FALSE.equals(hasIds)) {
            return Mono.just(Collections.emptyList());
        }
        Pageable pageable = PageRequest.of(index, size);
        Criteria criteria = Criteria.where("id").in(ids);
        Query query = Query.query(criteria).with(pageable);
        return template.select(query, clazz).collectList();
    }
    public <T, R> Mono<ListResponse<List<R>>> getPagedList(
            int index,
            int size,
            Criteria criteria,
            List<T> selects,
            Pageable pageable,
            Class<T> clazz,
            Function<List<T>, Flux<R>> converter) {

        Query query = Query.query(criteria).with(pageable);

        Mono<List<T>> listMono = getByDataList(query, clazz);
        Mono<Long> countMono = getCount(criteria, clazz);

        return Mono.zip(listMono, countMono)
                .flatMap(tuple -> {
                    List<T> remainingItems = tuple.getT1();
                    long totalRemaining = tuple.getT2();

                    List<T> allItems = new ArrayList<>(selects);
                    allItems.addAll(remainingItems);

                    if (allItems.isEmpty()) {
                        return Mono.just(ListResponse.<List<R>>success("PL001", false));
                    }

                    long totalCount = totalRemaining + selects.size();
                    boolean hasMore = ((long) (index + 1) * size) < totalCount;

                    return converter.apply(allItems)
                            .collectList()
                            .map(dtoList -> ListResponse.success("PL002", hasMore, dtoList));
                })
                .onErrorResume(ex -> Mono.just(ListResponse.error("PL003")));
    }
}
