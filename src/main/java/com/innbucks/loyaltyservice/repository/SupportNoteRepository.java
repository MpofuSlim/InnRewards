package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.SupportNote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.UUID;

/** Append-only by SHAPE: save and reads, no update, no delete. */
public interface SupportNoteRepository extends Repository<SupportNote, UUID> {

    SupportNote save(SupportNote note);

    Page<SupportNote> findBySubjectKindAndSubjectIdOrderByCreatedAtDesc(String subjectKind, String subjectId,
                                                                        Pageable pageable);

    long countBySubjectKindAndSubjectId(String subjectKind, String subjectId);
}
