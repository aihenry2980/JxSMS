package com.example.jxsms.domain.classifier

import com.example.jxsms.domain.model.SmsCategory

interface SmsClassifier {
    fun classify(sender: String, body: String, contactMatched: Boolean): SmsCategory
}

class RuleBasedSmsClassifier : SmsClassifier {
    private val otpWords = listOf(
        "认证码", "验证码", "动态码", "安全码", "登录码", "校验码", "一次性密码",
        "인증번호", "인증코드", "인증 번호", "인증 코드", "일회용", "보안코드", "본인확인", "확인번호",
        "verification code", "security code", "authentication code", "login code",
        "one-time password", "passcode", "otp"
    )
    private val explicitAds = listOf(
        "(광고)", "[광고]", "수신거부", "무료거부", "무료수신거부",
        "退订", "拒收", "回复td", "unsubscribe", "opt out"
    )
    private val marketing = listOf(
        "折扣", "优惠券", "限时", "促销", "特价", "sale", "discount", "coupon",
        "이벤트", "할인", "쿠폰", "특가"
    )
    private val delivery = listOf(
        "택배", "배송", "배달", "도착예정", "운송장", "집화", "기사님", "문앞", "물품도착",
        "快递", "配送", "派送", "取件", "包裹", "运单", "物流", "已签收", "投递", "驿站", "快递员", "到达",
        "delivery", "delivered", "shipped", "package", "parcel", "tracking", "courier",
        "out for delivery", "shipment"
    )
    private val notices = listOf(
        "안내", "알림", "공지", "결제", "승인", "입금", "출금", "예약", "변경", "취소", "청구",
        "이용내역", "신청", "완료", "접수", "通知", "提醒", "支付", "预约", "扣款", "入账",
        "出账", "申请", "办理", "变更", "取消", "确认", "到期", "confirmation", "notice",
        "alert", "payment", "approved", "transaction", "reservation", "reminder", "cancelled", "completed"
    )
    private val mobile = Regex("""^\+?\d[\d\s-]{7,16}$""")

    override fun classify(sender: String, body: String, contactMatched: Boolean): SmsCategory {
        val text = body.trim().lowercase()
        if (text.isEmpty()) return if (contactMatched) SmsCategory.PERSON else SmsCategory.UNKNOWN
        // Legally/semantically explicit opt-out markers take precedence over all content.
        if (explicitAds.any(text::contains) || ("광고" in text && marketing.any(text::contains))) {
            return SmsCategory.ADVERTISEMENT
        }
        // OTP requires authentication semantics; numbers alone never qualify.
        if (otpWords.any(text::contains)) return SmsCategory.OTP
        if (delivery.any(text::contains)) return SmsCategory.DELIVERY
        if (notices.any(text::contains)) return SmsCategory.NOTICE
        if (marketing.count(text::contains) >= 2) return SmsCategory.ADVERTISEMENT
        if (contactMatched || (mobile.matches(sender.trim()) && looksConversational(text))) {
            return SmsCategory.PERSON
        }
        return SmsCategory.UNKNOWN
    }

    private fun looksConversational(text: String): Boolean =
        text.length in 1..240 && listOf("?", "？", "!", "！", "吗", "呢", "요", "해", "hi", "hello")
            .any(text::contains)
}
