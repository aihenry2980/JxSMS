package com.example.jxsms.role

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent

class DefaultSmsRoleManager(private val context: Context) {
    private val manager = context.getSystemService(RoleManager::class.java)
    fun isDefault(): Boolean = manager.isRoleAvailable(RoleManager.ROLE_SMS) &&
        manager.isRoleHeld(RoleManager.ROLE_SMS)
    fun requestIntent(): Intent = manager.createRequestRoleIntent(RoleManager.ROLE_SMS)
}
