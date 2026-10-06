package cn.townhc.homebed.domain.entity;

import cn.townhc.homebed.domain.enums.DomainEnums.NotifyRecipientType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 转诊通知记录：标记高危/救护车转运时，平台向家庭医生、家属、转诊医院
 * 发出的通知留痕（站内信/短信模拟），支持接收人查看与已读。
 */
@Getter
@Setter
@Entity
@Table(name = "referral_notification")
public class ReferralNotification extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referral_id")
    private Referral referral;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotifyRecipientType recipientType;

    /** 站内接收人（医生/家属账号）；医院为外部机构，为空 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_user_id")
    private SysUser recipientUser;

    @Column(length = 64)
    private String recipientName;

    @Column(length = 20)
    private String recipientPhone;

    /** PLATFORM=站内信，SMS=短信(模拟) */
    @Column(nullable = false, length = 16)
    private String channel;

    @Column(nullable = false, length = 512)
    private String content;

    private LocalDateTime sentAt;

    private LocalDateTime readAt;
}
