package cn.townhc.homebed.repository;

import cn.townhc.homebed.domain.entity.ReferralNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReferralNotificationRepository extends JpaRepository<ReferralNotification, Long> {
    List<ReferralNotification> findByReferralIdOrderBySentAtAsc(Long referralId);
    List<ReferralNotification> findByRecipientUserIdOrderBySentAtDesc(Long userId);
}
