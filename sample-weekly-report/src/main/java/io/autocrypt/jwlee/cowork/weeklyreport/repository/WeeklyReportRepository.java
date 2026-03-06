package io.autocrypt.jwlee.cowork.weeklyreport.repository;

import io.autocrypt.jwlee.cowork.weeklyreport.domain.WeeklyReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WeeklyReportRepository extends JpaRepository<WeeklyReportEntity, Long> {
    List<WeeklyReportEntity> findAllByOrderByCreatedAtDesc();
}
