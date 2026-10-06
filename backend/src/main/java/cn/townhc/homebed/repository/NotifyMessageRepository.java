package cn.townhc.homebed.repository;

import cn.townhc.homebed.domain.entity.NotifyMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotifyMessageRepository extends JpaRepository<NotifyMessage, Long> {
    List<NotifyMessage> findByPatientIdOrderBySentAtDesc(Long patientId);
    List<NotifyMessage> findByBizNoOrderBySentAtAsc(String bizNo);
    List<NotifyMessage> findByReceiverIdOrderBySentAtDesc(Long receiverId);
    long countByReceiverIdAndReadFlagFalse(Long receiverId);
}
