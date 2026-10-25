package com.andrexthedev.autovybz.ui.premium

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.andrexthedev.autovybz.premium.CryptoCoin
import com.andrexthedev.autovybz.premium.Entitlement
import com.andrexthedev.autovybz.premium.EntitlementStore
import com.andrexthedev.autovybz.premium.PremiumPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-shell owner of the current supporter entitlement (the wizard enforces the same policy via the store). */
class PremiumViewModel(app: Application) : AndroidViewModel(app) {
    private val store = EntitlementStore(app)
    private val _entitlement = MutableStateFlow(store.load())
    val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    /** Re-read after the wizard may have started the post-measurement trial. */
    fun refresh() {
        _entitlement.value = store.load()
    }

    fun startTrial() {
        _entitlement.value = store.startTrial()
    }

    fun activateCryptoAlpha(plan: PremiumPlan, coin: CryptoCoin, txid: String): Boolean {
        val updated = runCatching { store.activateCryptoAlpha(plan, coin, txid) }.getOrNull() ?: return false
        _entitlement.value = updated
        return true
    }

    fun resetAlpha() {
        _entitlement.value = store.resetAlpha()
    }
}
