package cn.townhc.homebed.repository;

import cn.townhc.homebed.domain.entity.Referral;
import cn.townhc.homebed.domain.enums.DomainEnums.ReferralStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ReferralRepository extends JpaRepository<Referral, Long> {
    List<Referral> findByPatientIdOrderByCreatedAtDesc(Long patientId);
    List<Referral> findByStatusOrderByCreatedAtDesc(ReferralStatus status);
    List<Referral> findAllByOrderByCreatedAtDesc();
    List<Referral> findByPatientIdAndStatusIn(Long patientId, Collection<ReferralStatus> statuses);
    List<Referral> findByApplicationId(Long applicationId);
}
