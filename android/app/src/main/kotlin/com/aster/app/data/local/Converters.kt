package com.aster.app.data.local

import androidx.room.TypeConverter
import com.aster.app.domain.model.MessageStatus

class Converters {
    @TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = status.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = try {
        MessageStatus.valueOf(value)
    } catch (e: IllegalArgumentException) {
        MessageStatus.SENT
    }
}
