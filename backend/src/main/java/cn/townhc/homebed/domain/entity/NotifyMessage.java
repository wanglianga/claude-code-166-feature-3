package cn.townhc.homebed.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 平台通知记录：高危事件对家庭医生、家属、转诊医院的站内通知/短信推送回执。
 * 演示环境不真正发短信，统一落库为可查看的通知留痕。
 */
@Getter
@Setter
@Entity
@Table(name = "notify_message")
public class NotifyMessage extends BaseEntity {
    /** 接收方账号（医院为虚拟对接账号时可为空） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id")
    private SysUser receiver;

    /** 接收方角色/渠道描述，如“转诊医院·县人民医院急诊” */
    @Column(nullable = false, length = 128)
    private String receiverName;

    /** 通知渠道：站内/短信/电话/医院平台对接 */
    @Column(nullable = false, length = 32)
    private String channel;

    @Column(nullable = false, length = 64)
    private String subject;

    @Column(nullable = false, length = 1024)
    private String content;

    /** 关联业务类型：RISK/REFERRAL 等 */
    @Column(length = 32)
    private String bizType;

    @Column(length = 32)
    private String bizNo;

    private Long patientId;

    /** 送达状态：SENT/FAILED */
    @Column(length = 16)
    private String deliverStatus = "SENT";

    private LocalDateTime sentAt;

    private Boolean readFlag = false;

    private LocalDateTime readAt;
}
