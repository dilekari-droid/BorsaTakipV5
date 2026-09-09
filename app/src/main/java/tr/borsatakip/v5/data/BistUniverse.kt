package tr.borsatakip.v5.data

/**
 * Bu liste yalnızca ana sağlayıcıya hiç ulaşılamadığı ve daha önce dinamik evren önbelleğe
 * alınmadığı durumda yedek kaynağın kullanabileceği küçük güvenlik listesidir.
 * Normal tarama evreni GET /v1/bist/symbols üzerinden dinamik gelir ve SettingsStore'da önbelleklenir.
 */
object BistUniverse {
    val safetyFallback = listOf(
        "AEFES","AKBNK","ASELS","ASTOR","BIMAS","EKGYO","ENKAI","EREGL","FROTO","GARAN",
        "GUBRF","ISCTR","KCHOL","KRDMD","MGROS","PETKM","PGSUS","SAHOL","SASA","SISE",
        "TAVHL","TCELL","THYAO","TOASO","TTKOM","TUPRS","VAKBN","YKBNK"
    )
}
