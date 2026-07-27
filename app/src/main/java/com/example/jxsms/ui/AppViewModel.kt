package com.example.jxsms.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.jxsms.JxSmsApplication
import com.example.jxsms.data.preferences.UserPreferences
import com.example.jxsms.data.sms.SmsMessageModel
import com.example.jxsms.data.trash.TrashResult
import com.example.jxsms.data.trash.TrashSmsEntity
import com.example.jxsms.domain.model.SmsCategory
import com.example.jxsms.domain.model.SwipeAction
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as JxSmsApplication).container
    val messages: StateFlow<List<SmsMessageModel>> = c.sms.messages()
        .combine(c.preferences.preferences) { messages, preferences ->
            messages.map { sms ->
                sms.copy(category = preferences.categoryOverrides[sms.id] ?: sms.category)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val conversationMessages: StateFlow<List<SmsMessageModel>> = c.sms.conversationMessages()
        .combine(c.preferences.preferences) { messages, preferences ->
            messages.map { sms ->
                sms.copy(category = preferences.categoryOverrides[sms.id] ?: sms.category)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trash: StateFlow<List<TrashSmsEntity>> = c.trash.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val preferences: StateFlow<UserPreferences> = c.preferences.preferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())

    suspend fun message(id: Long) = c.sms.byId(id)?.let { sms ->
        val override = c.preferences.preferences.first().categoryOverrides[id]
        if (override == null) sms else sms.copy(category = override)
    }
    suspend fun delete(id: Long): TrashResult = c.trash.moveSmsToTrash(id)
    suspend fun restore(id: Long) = c.trash.restore(id)
    fun permanentDelete(id: Long) = viewModelScope.launch { c.trash.permanentlyDelete(id) }
    fun clearTrash() = viewModelScope.launch { c.trash.clear() }
    fun markRead(id: Long, value: Boolean) = viewModelScope.launch {
        if (c.sms.setRead(id, value) && value) c.notifications.cancel(id)
    }
    fun setLeft(value: SwipeAction) = viewModelScope.launch { c.preferences.setLeft(value) }
    fun setRight(value: SwipeAction) = viewModelScope.launch { c.preferences.setRight(value) }
    fun setRiskAccepted(value: Boolean) = viewModelScope.launch { c.preferences.setRiskAccepted(value) }
    fun setNotificationDeleteOnLeft(value: Boolean) = viewModelScope.launch {
        c.preferences.setNotificationDeleteOnLeft(value)
    }
    fun setMergeConversations(value: Boolean) = viewModelScope.launch {
        c.preferences.setMergeConversations(value)
    }
    fun setTagColor(category: SmsCategory, color: Long) = viewModelScope.launch {
        c.preferences.setTagColor(category, color)
    }
    fun setCategory(id: Long, category: SmsCategory) = viewModelScope.launch {
        c.preferences.setCategoryOverride(id, category)
    }
}
