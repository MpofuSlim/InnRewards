package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Voucher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;

/** Spring Data picks this up as the {@link VoucherReportQueries} fragment of
 *  {@link VoucherRepository} by its {@code Impl} suffix. */
public class VoucherReportQueriesImpl implements VoucherReportQueries {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Object[]> summaryByStatus(Specification<Voucher> spec) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Voucher> v = cq.from(Voucher.class);
        Predicate where = spec == null ? null : spec.toPredicate(v, cq, cb);
        cq.multiselect(v.get("status"), cb.count(v),
                        cb.coalesce(cb.sum(v.<BigDecimal>get("baseValue")), BigDecimal.ZERO))
                .groupBy(v.get("status"));
        if (where != null) {
            cq.where(where);
        }
        return em.createQuery(cq).getResultList();
    }
}
