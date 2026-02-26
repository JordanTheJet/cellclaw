package com.cellclaw.agent

import android.content.Context
import android.content.pm.PackageManager
import com.cellclaw.config.AppConfig
import com.cellclaw.service.AccessibilityBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

enum class AccessMode(val key: String, val displayName: String, val description: String) {
    ALL_ON("all_on", "All Apps On", "CellClaw can interact with every app"),
    SMART("smart", "Smart", "All apps except banking, finance & investment apps"),
    ALL_OFF("all_off", "All Apps Off", "CellClaw cannot interact with any app unless you allow it");

    companion object {
        fun fromKey(key: String): AccessMode =
            entries.firstOrNull { it.key == key } ?: ALL_ON
    }
}

@Singleton
class AppAccessPolicy @Inject constructor(
    private val appConfig: AppConfig,
    @ApplicationContext private val context: Context
) {
    private val _mode = MutableStateFlow(AccessMode.fromKey(appConfig.appAccessMode))
    val mode: StateFlow<AccessMode> = _mode.asStateFlow()

    private val _overrides = MutableStateFlow(appConfig.appAccessOverrides)
    val overrides: StateFlow<Set<String>> = _overrides.asStateFlow()

    fun isAppAllowed(packageName: String): Boolean {
        val currentMode = _mode.value
        val overridden = packageName in _overrides.value

        return when (currentMode) {
            AccessMode.ALL_ON -> {
                // Default allow; overrides are blocked apps
                if (overridden) false else true
            }
            AccessMode.SMART -> {
                // Default allow except financial apps; overrides flip the default
                val isFinancial = isFinancialApp(packageName)
                val defaultAllow = !isFinancial
                if (overridden) !defaultAllow else defaultAllow
            }
            AccessMode.ALL_OFF -> {
                // Default block; overrides are allowed apps
                if (overridden) true else false
            }
        }
    }

    fun isAppOverridden(packageName: String): Boolean {
        return packageName in _overrides.value
    }

    fun setAppOverridden(packageName: String, overridden: Boolean) {
        val current = _overrides.value.toMutableSet()
        if (overridden) current.add(packageName) else current.remove(packageName)
        appConfig.appAccessOverrides = current
        _overrides.value = current
    }

    fun setMode(mode: AccessMode) {
        // Clear overrides when switching modes so the new default applies cleanly
        appConfig.appAccessOverrides = emptySet()
        _overrides.value = emptySet()
        appConfig.appAccessMode = mode.key
        _mode.value = mode
    }

    /**
     * Returns the default allowed state for an app under the current mode,
     * ignoring any per-app overrides.
     */
    fun defaultAllowedForApp(packageName: String): Boolean {
        return when (_mode.value) {
            AccessMode.ALL_ON -> true
            AccessMode.SMART -> !isFinancialApp(packageName)
            AccessMode.ALL_OFF -> false
        }
    }

    // ── Financial app detection ──────────────────────────────────────────

    fun isFinancialApp(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        if (financialPackagePrefixes.any { pkg.startsWith(it) }) return true
        if (pkg in knownFinancialPackages) return true
        // Check app name keywords as a fallback
        val appName = resolveAppLabel(packageName)?.lowercase() ?: return false
        return financialKeywords.any { it in appName }
    }

    private fun resolveAppLabel(packageName: String): String? {
        return try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private val knownFinancialPackages = setOf(
        // ── US Banking ──
        "com.chase.sig.android",
        "com.bankofamerica.cashpromobile",
        "com.wf.wellsfargomobile",
        "com.citi.citimobile",
        "com.usbank.mobilebanking",
        "com.pnc.ecommerce.mobile",
        "com.td",
        "com.capitalone.mobile.banking",
        "com.ally.MobileBanking",
        "com.schwab.mobile",
        "com.discover.mobile",
        "com.usaa.mobile.android.usaa",
        "com.navyfederal.android",
        // ── Europe / UK ──
        "com.revolut.revolut",
        "de.number26.android",                  // N26
        "com.starlingbank.android",
        "com.monzo.android",
        "uk.co.hsbc.hsbcukmobilebanking",
        "com.barclays.android.barclaysmobilebanking",
        "com.lloydsbank.businessmobile",
        "com.db.pwcc.dbmobile",                 // Deutsche Bank
        "com.ing.mobile",                       // ING
        "net.bnpparibas.mescomptes",            // BNP Paribas
        "com.bbva.bbvacontigo",                 // BBVA
        "es.lacaixa.mobile.android.newwapicon",  // CaixaBank
        "com.finansbank.mobile.cepsube",        // QNB Finansbank
        "com.isbankasi.isyerim",                // Isbank (Turkey)
        // ── Latin America ──
        "com.nu.production",                    // Nubank
        "com.mercadopago.wallet",               // Mercado Pago
        "br.com.itau",                          // Itau
        "com.bradesco",                         // Bradesco
        "br.com.bb.android",                    // Banco do Brasil
        "com.banorte.movil",                    // Banorte (Mexico)
        // ── India ──
        "net.one97.paytm",                      // Paytm
        "com.phonepe.app",                      // PhonePe
        "in.org.npci.upiapp",                   // BHIM UPI
        "com.csam.icici.bank.imobile",          // ICICI
        "com.sbi.SBIFreedomPlus",               // SBI
        "com.axis.mobile",                      // Axis Bank
        "com.msf.kbank.mobile",                 // Kotak
        "com.google.android.apps.nbu.paisa.user", // Google Pay (India)
        // ── Southeast Asia ──
        "com.grab.merchant",                    // GrabPay
        "vn.momo.platform",                     // MoMo (Vietnam)
        "id.dana",                              // DANA (Indonesia)
        "com.gojek.gopay",                      // GoPay (Indonesia)
        "sg.com.dbs.dbsmbanking",               // DBS (Singapore)
        "com.maybank2u.life",                   // Maybank (Malaysia)
        "th.co.kbank.kplus",                    // KBank (Thailand)
        // ── East Asia ──
        "jp.mufg.bk.applisp.app",              // MUFG (Japan)
        "jp.co.smbc.direct",                    // SMBC (Japan)
        "com.kakaobank.channel",                // KakaoBank (Korea)
        "com.kbstar.kbbank",                    // KB Bank (Korea)
        "com.tosslab.toss",                     // Toss (Korea)
        // ── Middle East / Africa ──
        "com.alrajhiretailapp",                 // Al Rajhi (Saudi)
        "com.enbd.smartbanking",                // Emirates NBD
        "com.stanbic.bank",                     // Stanbic
        "com.fnb.smartapp.smartphone",          // FNB (South Africa)
        "com.nedbank.retail",                   // Nedbank (South Africa)
        // ── Australia / NZ ──
        "com.commbank.netbank",                 // CommBank
        "au.com.nab.mobile",                    // NAB
        "org.westpac.bank",                     // Westpac
        "au.com.newcastlepermanent",
        // ── Investment / brokerage (global) ──
        "com.robinhood.android",
        "com.etrade.mobilepro.activity",
        "com.fidelity.android",
        "com.thinkorswim.mobile",
        "com.interactivebrokers.ibkr",
        "com.webull.webapp",
        "com.tradingview",
        "com.etoro.app",                        // eToro
        "com.ig.CFD",                           // IG Trading
        "com.saxobank.mobile",                  // Saxo Bank
        "com.plus500",                          // Plus500
        // ── Crypto (global) ──
        "com.coinbase.android",
        "com.binance.dev",
        "com.kraken.trade",
        "piuk.blockchain.android",              // Blockchain.com
        "com.krakenfutures.trade",
        "com.bybit.app",                        // Bybit
        "com.bitfinex.mobileapp",
        "com.okex.tradespot",                   // OKX
        "com.kucoin.market",                    // KuCoin
        // ── Payment / fintech (global) ──
        "com.venmo",
        "com.zellepay.zelle",
        "com.paypal.android.p2pmobile",
        "com.squareup.cash",
        "com.transferwise.android",             // Wise
        "com.remitly.androidapp",               // Remitly
        "com.worldremit.android",               // WorldRemit
        // ── Credit / budgeting ──
        "com.creditkarma.mobile",
        "com.mint",
        "com.ynab.ynab",
        "com.personalcapital.pcapandroid"
    )

    private val financialPackagePrefixes = listOf(
        // US
        "com.chase.", "com.bankofamerica.", "com.wellsfargo.", "com.citi.",
        "com.capitalone.", "com.americanexpress.", "com.schwab.",
        "com.fidelity.", "com.vanguard.", "com.merrilledge.",
        "com.etrade.", "com.tdameritrade.",
        // Global crypto/fintech
        "com.coinbase.", "com.binance.", "com.crypto.",
        // Regional
        "com.revolut.", "de.number26.", "com.monzo.",
        "com.nu.", "br.com.itau.", "br.com.bb.",
        "com.commbank.", "au.com.nab.", "au.com.anz.",
        "jp.mufg.", "jp.co.smbc.", "jp.co.mizuho.",
        "com.kakaobank.", "com.kbstar."
    )

    /** Multilingual keywords — covers EN, ES, PT, FR, DE, IT, TR, HI, JA, KO, AR, ZH and more. */
    private val financialKeywords = listOf(
        // English
        "bank", "banking", "finance", "financial",
        "invest", "brokerage", "trading", "stocks",
        "crypto", "wallet", "credit union",
        "mortgage", "loan", "insurance",
        // Spanish
        "banco", "banca", "finanzas", "inversión", "inversiones",
        "bolsa", "crédito", "préstamo", "seguro",
        // Portuguese
        "finanças", "investimento", "empréstimo",
        "corretora", "seguradora",
        // French
        "banque", "investissement", "bourse",
        "crédit", "prêt", "assurance",
        // German
        "sparkasse", "volksbank", "finanzen",
        "investition", "kredit", "versicherung",
        // Italian
        "banca", "finanza", "investimenti",
        "credito", "prestito", "assicurazione",
        // Turkish
        "bankası", "bankasi", "finans", "yatırım", "yatirim",
        "sigorta", "kredi",
        // Hindi (transliterated)
        "paisa", "nivesh",
        // Japanese
        "銀行", "証券", "投資", "金融", "保険",
        // Korean
        "은행", "증권", "투자", "금융", "보험",
        // Arabic
        "بنك", "مصرف", "استثمار", "تمويل", "تأمين",
        // Chinese
        "银行", "證券", "投资", "金融", "保险"
    )

    // ── Tool-level enforcement ───────────────────────────────────────────

    private val appTargetingTools = setOf(
        "app.launch", "app.automate", "messaging.open",
        "messaging.read", "messaging.reply",
        "screen.read", "screen.capture"
    )

    private val parameterTools = setOf("app.launch", "messaging.open")

    private val foregroundTools = setOf(
        "app.automate", "messaging.read", "messaging.reply",
        "screen.read", "screen.capture"
    )

    fun isAppTargetingTool(toolName: String): Boolean = toolName in appTargetingTools

    fun resolvePackageFromParams(toolName: String, params: JsonObject): String? {
        return when (toolName) {
            "app.launch" -> {
                val pkgName = params["package_name"]?.jsonPrimitive?.contentOrNull
                if (pkgName != null) return pkgName
                val appName = params["app_name"]?.jsonPrimitive?.contentOrNull ?: return null
                resolveAppNameToPackage(appName)
            }
            "messaging.open" -> {
                val app = params["app"]?.jsonPrimitive?.contentOrNull ?: return null
                messagingAppPackages[app]
            }
            else -> null
        }
    }

    fun isForegroundTool(toolName: String): Boolean = toolName in foregroundTools

    private val messagingAppPackages = mapOf(
        "whatsapp" to "com.whatsapp",
        "telegram" to "org.telegram.messenger",
        "instagram" to "com.instagram.android",
        "messages" to "com.google.android.apps.messaging"
    )

    private fun resolveAppNameToPackage(name: String): String? {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps.firstOrNull {
            pm.getApplicationLabel(it).toString().equals(name, ignoreCase = true)
        }?.packageName
    }
}
