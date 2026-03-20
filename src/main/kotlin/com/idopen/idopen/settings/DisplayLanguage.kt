package com.idopen.idopen.settings

import java.util.Locale

enum class DisplayLanguage {
    ZH_CN,
    EN_US,
    ;

    fun toggle(): DisplayLanguage {
        return if (this == ZH_CN) EN_US else ZH_CN
    }

    companion object {
        fun default(): DisplayLanguage {
            return if (Locale.getDefault().language.lowercase().startsWith("zh")) ZH_CN else EN_US
        }

        fun fromStored(value: String?): DisplayLanguage {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default()
        }
    }
}
