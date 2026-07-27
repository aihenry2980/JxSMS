package com.example.jxsms

import com.example.jxsms.domain.classifier.RuleBasedSmsClassifier
import com.example.jxsms.domain.model.SmsCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleBasedSmsClassifierTest {
    private val classifier = RuleBasedSmsClassifier()

    @Test fun multilingualAndBoundaryCases() {
        val cases = listOf(
            Triple("1588", "[Web발신] 인증번호는 123456입니다.", SmsCategory.OTP),
            Triple("PASS", "본인확인 인증 코드는 A7F2입니다", SmsCategory.OTP),
            Triple("银行", "您的验证码为 836271，请勿泄露", SmsCategory.OTP),
            Triple("Service", "Your verification code is 123-456", SmsCategory.OTP),
            Triple("01012345678", "我们 2026 年再见！", SmsCategory.PERSON),
            Triple("NOTICE", "会议日期为 2026-07-27", SmsCategory.UNKNOWN),
            Triple("NOTICE", "客服电话 021-12345678", SmsCategory.UNKNOWN),
            Triple("SHOP", "订单号 123456 已确认", SmsCategory.NOTICE),
            Triple("SHOP", "(광고) 오늘 배송 특가! 수신거부", SmsCategory.ADVERTISEMENT),
            Triple("CJ대한통운", "택배 운송장 123456 배송예정", SmsCategory.DELIVERY),
            Triple("BANK", "결제 50,000원이 승인되었습니다", SmsCategory.NOTICE),
            Triple("01012345678", "오늘 저녁 같이 먹을까요?", SmsCategory.PERSON),
            Triple("01012345678", "验证码 1234", SmsCategory.OTP),
            Triple("BRAND", "unrecognized payload", SmsCategory.UNKNOWN),
            Triple("BRAND", "", SmsCategory.UNKNOWN),
            Triple("BRAND", "✨★♧", SmsCategory.UNKNOWN),
            Triple("01012345678", "안녕 😊 오늘 어때요?", SmsCategory.PERSON),
            Triple("ALPHA", "parcel shipped, tracking 99887766", SmsCategory.DELIVERY),
            Triple("ALPHA", "payment completed: $25.00", SmsCategory.NOTICE),
            Triple("ALPHA", "SALE coupon delivery discount unsubscribe", SmsCategory.ADVERTISEMENT),
            Triple("택배", "문앞에 배송완료했습니다", SmsCategory.DELIVERY),
            Triple("银行", "您的账户已入账 888.00 元", SmsCategory.NOTICE),
            Triple("NEWS", "到期提醒通知", SmsCategory.NOTICE),
            Triple("SHOP", "限时优惠券促销", SmsCategory.ADVERTISEMENT),
            Triple("COURIER", "out for delivery 123456", SmsCategory.DELIVERY)
        )
        cases.forEach { (sender, body, expected) ->
            assertEquals("$sender / $body", expected, classifier.classify(sender, body, false))
            assertEquals(expected, classifier.classify(sender, body, false))
        }
    }

    @Test fun contactDoesNotOverrideExplicitContent() {
        assertEquals(SmsCategory.DELIVERY, classifier.classify("01012345678", "배송예정입니다", true))
        assertEquals(SmsCategory.ADVERTISEMENT, classifier.classify("friend", "(광고) 할인 수신거부", true))
        assertEquals(SmsCategory.PERSON, classifier.classify("friend", "普通聊天内容", true))
    }
}
