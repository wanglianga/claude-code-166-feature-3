package cn.townhc.homebed.repository;

import cn.townhc.homebed.domain.entity.Referral;
import cn.townhc.homebed.domain.enums.DomainEnums.ReferralStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReferralRepository extends JpaRepository<Referral, Long> {
    List<Referral> findByStatusOrderByFlaggedAtDesc(ReferralStatus status);
    List<Referral> findByPatientIdOrderByFlaggedAtDesc(Long patientId);
    List<Referral> findAllByOrderByFlaggedAtDesc();
    long countByStatus(ReferralStatus status);
    long countByStatusIn(List<ReferralStatus> statuses);
    Optional<Referral> findFirstByPatientIdAndStatusInOrderByFlaggedAtDesc(
            Long patientId, List<ReferralStatus> statuses);
}
