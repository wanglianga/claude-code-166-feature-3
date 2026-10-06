package cn.townhc.homebed.service;

import cn.townhc.homebed.domain.entity.NotifyMessage;
import cn.townhc.homebed.domain.entity.SysUser;
import cn.townhc.homebed.repository.NotifyMessageRepository;
import cn.townhc.homebed.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 通知服务：高危事件向家庭医生、家属、转诊医院三方推送。
 * 演示环境以站内通知 + 短信回执快照形式落库留痕（不真正外发短信/对接医院平台）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private final NotifyMessageRepository notifyRepository;
    private final SysUserRepository userRepository;

    public record SentNotify(String receiverName, String channel, String status) {}

    /** 向单个接收账号发送（站内+短信快照） */
    @Transactional
    public NotifyMessage sendUser(Long receiverId, String receiverName, String channel,
                                  String subject, String content,
                                  String bizType, String bizNo, Long patientId) {
        SysUser receiver = receiverId == null ? null
                : userRepository.findById(receiverId).orElse(null);
        NotifyMessage m = new NotifyMessage();
        m.setReceiver(receiver);
        m.setReceiverName(receiverName == null && receiver != null ? receiver.getName() : receiverName);
        m.setChannel(channel);
        m.setSubject(subject);
        m.setContent(content);
        m.setBizType(bizType);
        m.setBizNo(bizNo);
        m.setPatientId(patientId);
        m.setDeliverStatus("SENT");
        m.setSentAt(LocalDateTime.now());
        return notifyRepository.save(m);
    }

    /** 向外部机构（转诊医院）发送对接通知，无平台账号 */
    @Transactional
    public NotifyMessage sendExternal(String receiverName, String channel,
                                      String subject, String content,
                                      String bizType, String bizNo, Long patientId) {
        return sendUser(null, receiverName, channel, subject, content, bizType, bizNo, patientId);
    }

    /** 高危三方通知：家庭医生 / 家属 / 转诊医院 */
    @Transactional
    public List<SentNotify> notifyCritical(Long doctorId, String doctorName,
                                           Long familyUserId, String familyName, String familyPhone,
                                           String hospitalName, String hospitalContact,
                                           String subject, String content,
                                           String bizNo, Long patientId) {
        List<SentNotify> result = new ArrayList<>();
        if (doctorId != null) {
            sendUser(doctorId, doctorName, "站内+语音", subject, content,
                    "REFERRAL", bizNo, patientId);
            result.add(new SentNotify(doctorName, "站内+语音", "SENT"));
        }
        if (familyUserId != null) {
            sendUser(familyUserId, familyName, "站内+短信", subject,
                    content + (familyPhone != null ? "（联系电话 " + familyPhone + "）" : ""),
                    "REFERRAL", bizNo, patientId);
            result.add(new SentNotify(familyName, "站内+短信", "SENT"));
        }
        if (hospitalName != null && !hospitalName.isBlank()) {
            sendExternal(hospitalName + (hospitalContact != null ? "·" + hospitalContact : ""),
                    "区域转诊平台", subject, content, "REFERRAL", bizNo, patientId);
            result.add(new SentNotify(hospitalName, "区域转诊平台", "SENT"));
        }
        log.info("高危通知已发出 {} 方：bizNo={}", result.size(), bizNo);
        return result;
    }

    @Transactional(readOnly = true)
    public List<NotifyMessage> byPatient(Long patientId) {
        return notifyRepository.findByPatientIdOrderBySentAtDesc(patientId);
    }

    @Transactional(readOnly = true)
    public List<NotifyMessage> byBizNo(String bizNo) {
        return notifyRepository.findByBizNoOrderBySentAtAsc(bizNo);
    }
}
