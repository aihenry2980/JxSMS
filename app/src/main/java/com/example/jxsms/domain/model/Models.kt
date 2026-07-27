package com.example.jxsms.domain.model

enum class SmsCategory(val label: String) {
    OTP("认证码"), ADVERTISEMENT("广告"), DELIVERY("快递"),
    PERSON("真人"), NOTICE("通知"), UNKNOWN("其他")
}

enum class SwipeAction(val label: String) {
    DELETE("删除"), MARK_READ_UNREAD("标记已读/未读"), COPY_TEXT("复制短信内容"), NONE("无操作")
}
