package com.idopen.idopen.settings

object LocalizedTextSupport {
    private val mojibakeMarkers = listOf(
        "棰",
        "鍓",
        "鏈",
        "璇",
        "鐧",
        "閿",
        "閵",
        "閺",
        "鐠",
        "缂",
        "閸",
        "閻",
        "瀹",
        "闂",
        "婢",
        "濡",
        "妞",
        "鐎",
        "閹",
        "缁",
    )

    fun choose(language: DisplayLanguage, zh: String, en: String): String {
        if (language != DisplayLanguage.ZH_CN) {
            return en
        }
        return if (looksLikeMojibake(zh)) en else zh
    }

    fun choose(storedLanguage: String?, zh: String, en: String): String {
        return choose(DisplayLanguage.fromStored(storedLanguage), zh, en)
    }

    fun looksLikeMojibake(value: String): Boolean {
        if (value.contains('\uFFFD')) return true
        return mojibakeMarkers.any(value::contains)
    }
}
