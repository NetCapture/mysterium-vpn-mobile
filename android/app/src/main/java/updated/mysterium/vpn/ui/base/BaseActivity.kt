package updated.mysterium.vpn.ui.base

import android.app.ActivityOptions
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import network.mysterium.vpn.BuildConfig
import network.mysterium.vpn.R
import network.mysterium.vpn.databinding.PopUpInsufficientFundsBinding
import network.mysterium.vpn.databinding.PopUpRetryRegistrationBinding
import network.mysterium.vpn.databinding.PopUpTopUpAccountBinding
import network.mysterium.vpn.databinding.PopUpWiFiErrorBinding
import org.koin.android.ext.android.inject
import updated.mysterium.vpn.common.extensions.TAG
import updated.mysterium.vpn.common.extensions.observeOnce
import updated.mysterium.vpn.common.localisation.LocaleHelper
import updated.mysterium.vpn.model.connection.ConnectionType
import updated.mysterium.vpn.model.manual.connect.ConnectionState
import updated.mysterium.vpn.model.manual.connect.Proposal
import updated.mysterium.vpn.model.pushy.PushyTopic
import updated.mysterium.vpn.notification.Notifications
import updated.mysterium.vpn.ui.base.BaseViewModel.Companion.CONNECT_BALANCE_LIMIT
import updated.mysterium.vpn.ui.connection.ConnectionActivity
import updated.mysterium.vpn.ui.custom.view.ConnectionToolbar
import updated.mysterium.vpn.ui.home.selection.HomeSelectionActivity
import updated.mysterium.vpn.ui.home.selection.HomeSelectionViewModel
import updated.mysterium.vpn.ui.top.up.coingate.amount.TopUpAmountActivity
import java.util.*

abstract class BaseActivity : AppCompatActivity() {

    protected var connectionStateToolbar: ConnectionToolbar? = null
    protected val baseViewModel: BaseViewModel by inject()
    private val homeSelectionViewModel: HomeSelectionViewModel by inject()
    protected var isInternetAvailable = true
    protected var connectionState = ConnectionState.NOTCONNECTED
    protected val pushyNotifications = Notifications(this)
    private val dialogs = emptyList<Dialog>().toMutableList()
    private var insufficientFoundsDialog: AlertDialog? = null
    private var wifiErrorDialog: AlertDialog? = null
    private lateinit var alertDialogBuilder: AlertDialog.Builder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setLayoutDirection()
        baseViewModel.checkInternetConnection()
        alertDialogBuilder = AlertDialog.Builder(this)
        subscribeViewModel()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            LocaleHelper.onAttach(
                context = newBase,
                currentLanguage = baseViewModel.getUserCurrentLanguageCode()
            )
        )
    }

    override fun onResume() {
        super.onResume()
        baseViewModel.checkCurrentConnection()
    }

    override fun onPause() {
        closeAllPopUps()
        super.onPause()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    open fun retryLoading() {
        // Override in activity for handle retry loading click
    }

    open fun showConnectionHint() {
        // Override in activity for show connection hint
    }

    fun initToolbar(connectionToolbar: ConnectionToolbar) {
        connectionStateToolbar = connectionToolbar
    }

    fun createPopUp(popUpView: View, cancelable: Boolean): AlertDialog {
        alertDialogBuilder.apply {
            setView(popUpView)
            setCancelable(cancelable)
            create().apply {
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window?.setLayout(
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT
                )
            }
        }
        val dialog = alertDialogBuilder.create()
        dialog.apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialogs.add(dialog)
        return dialog
    }

    fun detailedErrorPopUp(
        retryAction: () -> Unit
    ) {
        val bindingPopUp = PopUpRetryRegistrationBinding.inflate(layoutInflater)
        val dialog = createPopUp(bindingPopUp.root, false)
        bindingPopUp.description.setText(R.string.pop_up_registration_failed_description)
        bindingPopUp.title.setText(R.string.pop_up_registration_failed_title)
        bindingPopUp.tryAgainButton.setOnClickListener {
            retryAction.invoke()
            dialog.dismiss()
        }
        bindingPopUp.cancelButton.setOnClickListener {
            dialog.dismiss()
            finish()
        }
        dialog.show()
    }

    fun wifiNetworkErrorPopUp(retryAction: () -> Unit) {
        if (wifiErrorDialog == null && !isFinishing) {
            val bindingPopUp = PopUpWiFiErrorBinding.inflate(layoutInflater)
            wifiErrorDialog = createPopUp(bindingPopUp.root, false)
            bindingPopUp.retryButton.setOnClickListener {
                wifiErrorDialog?.dismiss()
                wifiErrorDialog = null
                retryAction()
            }
            wifiErrorDialog?.show()
        }
    }

    fun insufficientFundsPopUp(onContinueAction: (() -> Unit)? = null) {
        val bindingPopUp = PopUpInsufficientFundsBinding.inflate(layoutInflater)
        val dialog = createPopUp(bindingPopUp.root, false)
        bindingPopUp.topUpButton.setOnClickListener {
            navigateToPayment()
        }
        bindingPopUp.continueButton.setOnClickListener {
            dialog.dismiss()
            onContinueAction?.invoke()
        }
        dialog.show()
    }

    fun closeAllPopUps() {
        dialogs.forEach {
            it.dismiss()
        }
    }

    fun establishConnectionListeners() {
        baseViewModel.establishListeners()
    }

    protected open fun protectedConnection() {
        connectionStateToolbar?.protectedState(true)
    }

    private fun unprotectedConnection() {
        connectionStateToolbar?.unprotectedState()
    }

    private fun subscribeViewModel() {
        baseViewModel.balance.observe(this) {
            if (it < BaseViewModel.BALANCE_LIMIT) {
                pushyNotifications.subscribe(PushyTopic.LESS_THEN_HALF_MYST)
            } else {
                pushyNotifications.unsubscribe(PushyTopic.LESS_THEN_HALF_MYST)
            }
        }
        baseViewModel.balanceRunningOut.observe(this) {
            balanceRunningOutPopUp()
        }
        baseViewModel.connectionState.observe(this) {
            connectionState = it
            if (
                it == ConnectionState.CONNECTED ||
                it == ConnectionState.ON_HOLD ||
                it == ConnectionState.IP_NOT_CHANGED
            ) {
                isHintAlreadyShown()
                protectedConnection()
            } else {
                unprotectedConnection()
            }
        }
        baseViewModel.insufficientFunds.observe(this) {
            insufficientFundsPopUp()
        }
        baseViewModel.isInternetAvailable.observe(this) { isAvailable ->
            if (!isAvailable) {
                wifiNetworkErrorPopUp {
                    baseViewModel.checkInternetConnection()
                }
            } else if (!isInternetAvailable) {
                wifiErrorDialog?.dismiss()
                wifiErrorDialog = null
                retryLoading()
            }
            isInternetAvailable = isAvailable
        }
    }

    private fun isHintAlreadyShown() {
        if (!baseViewModel.isHintAlreadyShown()) {
            showConnectionHint()
        }
    }

    private fun balanceRunningOutPopUp() {
        if (insufficientFoundsDialog == null) {
            val bindingPopUp = PopUpTopUpAccountBinding.inflate(layoutInflater)
            insufficientFoundsDialog = createPopUp(bindingPopUp.root, false)
            bindingPopUp.topUpButton.setOnClickListener {
                insufficientFoundsDialog?.dismiss()
                insufficientFoundsDialog = null
                navigateToPayment()
            }
            bindingPopUp.continueButton.setOnClickListener {
                insufficientFoundsDialog?.dismiss()
                insufficientFoundsDialog = null
            }
            insufficientFoundsDialog?.show()
        }
    }

    private fun setLayoutDirection() {
        if (Locale.getDefault().language == "ar") {
            window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        } else {
            window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
    }

    fun navigateToConnectionIfConnectedOrHome(isBackTransition: Boolean = true) {
        val intent = if (
            connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.CONNECTING ||
            connectionState == ConnectionState.ON_HOLD
        ) {
            Intent(this, ConnectionActivity::class.java)
        } else {
            Intent(this, HomeSelectionActivity::class.java)
        }
        intent.apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent, getTransitionAnimation(isBackTransition))
    }

    fun navigateToConnectionIfBalanceOrHome() {
        baseViewModel.balance.observeOnce(this) { balance ->
            if (balance >= CONNECT_BALANCE_LIMIT) {
                navigateToSmartConnection(isBackTransition = false, isConnectIntent = true)
            } else {
                val intent = Intent(this, HomeSelectionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        }
    }

    fun navigateToConnection(proposal: Proposal) {
        if ((baseViewModel.balance.value ?: 0.0) >= CONNECT_BALANCE_LIMIT) {
            navigateToManualConnection(proposal)
        } else {
            insufficientFundsPopUp()
        }
    }

    fun navigateToConnection(isBackTransition: Boolean? = null, isConnectIntent: Boolean = false) {
        if ((baseViewModel.balance.value ?: 0.0) >= CONNECT_BALANCE_LIMIT) {
            navigateToSmartConnection(isBackTransition, isConnectIntent)
        } else {
            insufficientFundsPopUp()
        }
    }

    private fun navigateToManualConnection(proposal: Proposal) {
        val intent = Intent(this, ConnectionActivity::class.java).apply {
            putExtra(ConnectionActivity.CONNECTION_TYPE_KEY, ConnectionType.MANUAL_CONNECT.type)
            putExtra(ConnectionActivity.EXTRA_PROPOSAL_MODEL, proposal)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun navigateToSmartConnection(
        isBackTransition: Boolean? = null,
        isConnectIntent: Boolean = false
    ) {
        if (connectionState == ConnectionState.CONNECTED || isConnectIntent) {
            val intent = Intent(this, ConnectionActivity::class.java).apply {
                if (isConnectIntent) {
                    putExtra(
                        ConnectionActivity.CONNECTION_TYPE_KEY,
                        ConnectionType.SMART_CONNECT.type
                    )
                    putExtra(ConnectionActivity.COUNTRY_CODE_KEY, getCountryCode())
                }
                flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent, getTransitionAnimation(isBackTransition))
        }
    }

    fun navigateToPayment() {
        baseViewModel.getGateways().observe(this) {
            it.onSuccess { result ->
                val intent = if (BuildConfig.FLAVOR == "playstore") {
                    Intent(
                        this,
                        Class.forName("updated.mysterium.vpn.ui.top.up.play.billing.amount.usd.PlayBillingAmountUsdActivity")
                    )
                } else {
                    val gateways = result.filterNotNull()
                    if (gateways.size == 1) {
                        Intent(this, TopUpAmountActivity::class.java).apply {
                            putExtra(
                                TopUpAmountActivity.PAYMENT_METHOD_EXTRA_KEY,
                                gateways[0].gateway
                            )
                        }
                    } else {
                        val gatewayValues = gateways.map { it.gateway }
                        Intent(
                            this@BaseActivity,
                            Class.forName("updated.mysterium.vpn.ui.payment.method.PaymentMethodActivity")
                        ).apply {
                            putExtra("gatewaysExtra", gatewayValues.toTypedArray())
                        }
                    }
                }
                startActivity(intent)
                it.onFailure { error ->
                    Log.e(TAG, "getPaymentScreen failed with error ${error.message}")
                }
            }
        }
    }

    private fun getTransitionAnimation(isBackTransition: Boolean?): Bundle? {
        return if (isBackTransition == true) {
            ActivityOptions.makeCustomAnimation(
                applicationContext,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            ).toBundle()
        } else {
            ActivityOptions.makeCustomAnimation(
                applicationContext,
                R.anim.slide_in_right,
                R.anim.slide_out_left
            ).toBundle()
        }
    }

    private fun getCountryCode(): String? {
        return homeSelectionViewModel.getPreviousCountryCode()
    }

}
