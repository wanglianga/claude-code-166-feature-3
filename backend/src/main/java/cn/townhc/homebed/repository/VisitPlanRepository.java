package cn.townhc.homebed.repository;

import cn.townhc.homebed.domain.entity.VisitPlan;
import cn.townhc.homebed.domain.enums.DomainEnums.PlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VisitPlanRepository extends JpaRepository<VisitPlan, Long> {
    List<VisitPlan> findByApplicationId(Long applicationId);
    List<VisitPlan> findByPatientIdOrderByStartDateDesc(Long patientId);
    List<VisitPlan> findByPatientIdAndStatus(Long patientId, PlanStatus status);
    List<VisitPlan> findByNurseIdAndStatus(Long nurseId, PlanStatus status);
    List<VisitPlan> findByStatus(PlanStatus status);
    List<VisitPlan> findByDoctorId(Long doctorId);
}
