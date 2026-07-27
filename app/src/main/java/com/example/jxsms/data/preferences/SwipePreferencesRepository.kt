package com.example.jxsms.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.jxsms.domain.model.SmsCategory
import com.example.jxsms.domain.model.SwipeAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("jx_settings")

val DefaultTagColors: Map<SmsCategory, Long> = mapOf(
    SmsCategory.OTP to 0xFF245A9A,
    SmsCategory.ADVERTISEMENT to 0xFF9A4A20,
    SmsCategory.DELIVERY to 0xFF276A43,
    SmsCategory.PERSON to 0xFF60458F,
    SmsCategory.NOTICE to 0xFF80620E,
    SmsCategory.UNKNOWN to 0xFF515A66
)

data class UserPreferences(
    val left: SwipeAction = SwipeAction.DELETE,
    val right: SwipeAction = SwipeAction.DELETE,
    val riskAccepted: Boolean = false,
    val notificationDeleteOnLeft: Boolean = false,
    val mergeConversations: Boolean = false,
    val tagColors: Map<SmsCategory, Long> = DefaultTagColors,
    val categoryOverrides: Map<Long, SmsCategory> = emptyMap()
)

class SwipePreferencesRepository(private val context: Context) {
    private val left = stringPreferencesKey("left_swipe")
    private val right = stringPreferencesKey("right_swipe")
    private val risk = booleanPreferencesKey("mms_risk_accepted")
    private val notificationDeleteOnLeft = booleanPreferencesKey("notification_delete_on_left")
    private val mergeConversations = booleanPreferencesKey("merge_conversations")
    private val tagColorKeys = SmsCategory.entries.associateWith {
        stringPreferencesKey("tag_color_${it.name.lowercase()}")
    }
    private val categoryOverrides = stringSetPreferencesKey("category_overrides")
    val preferences: Flow<UserPreferences> = context.dataStore.data.map { p ->
        UserPreferences(
            left = p[left]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.DELETE,
            right = p[right]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.DELETE,
            riskAccepted = p[risk] ?: false,
            notificationDeleteOnLeft = p[notificationDeleteOnLeft] ?: false,
            mergeConversations = p[mergeConversations] ?: false,
            tagColors = SmsCategory.entries.associateWith { category ->
                p[tagColorKeys.getValue(category)]?.toLongOrNull() ?: DefaultTagColors.getValue(category)
            },
            categoryOverrides = p[categoryOverrides].orEmpty().mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val id = entry.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
                val category = runCatching {
                    SmsCategory.valueOf(entry.substring(separator + 1))
                }.getOrNull() ?: return@mapNotNull null
                id to category
            }.toMap()
        )
    }
    suspend fun setLeft(value: SwipeAction) = context.dataStore.edit { it[left] = value.name }
    suspend fun setRight(value: SwipeAction) = context.dataStore.edit { it[right] = value.name }
    suspend fun setRiskAccepted(value: Boolean) = context.dataStore.edit { it[risk] = value }
    suspend fun setNotificationDeleteOnLeft(value: Boolean) =
        context.dataStore.edit { it[notificationDeleteOnLeft] = value }
    suspend fun setMergeConversations(value: Boolean) =
        context.dataStore.edit { it[mergeConversations] = value }
    suspend fun setTagColor(category: SmsCategory, color: Long) =
        context.dataStore.edit { it[tagColorKeys.getValue(category)] = color.toString() }
    suspend fun setCategoryOverride(id: Long, category: SmsCategory) = context.dataStore.edit { p ->
        p[categoryOverrides] = p[categoryOverrides].orEmpty()
            .filterNot { it.substringBefore('=') == id.toString() }
            .toSet() + "$id=${category.name}"
    }
}
