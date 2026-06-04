package com.zevclip.sender

import android.app.Activity
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var ipAddressInput: EditText
    private lateinit var portInput: EditText
    private lateinit var pairingTokenInput: EditText
    private lateinit var textInput: EditText
    private lateinit var sendButton: Button
    private lateinit var pullButton: Button
    private lateinit var statusText: TextView
    private lateinit var discoverButton: Button
    private lateinit var discoveryStatusText: TextView
    private lateinit var pairingStatusText: TextView
    private lateinit var accessibilityStatusText: TextView
    private lateinit var lastAutoStatusText: TextView
    private lateinit var tileStatusText: TextView
    private lateinit var discoveryManager: MacDiscoveryManager

    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener {
        _, key ->
        if (
            key == ZevClipPreferences.KEY_LAST_AUTO_STATUS ||
            key == ZevClipPreferences.KEY_LAST_TILE_STATUS ||
            key == ZevClipPreferences.KEY_DISCOVERY_STATUS
        ) {
            runOnUiThread { refreshSyncStatuses() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        discoveryManager = MacDiscoveryManager(
            context = this,
            onStatusChanged = { status, isDiscovering ->
                if (!isDestroyed) {
                    discoverButton.isEnabled = !isDiscovering
                    discoveryStatusText.text = getString(R.string.discovery_status, status)
                }
            },
            onEndpointResolved = { host, port ->
                if (!isDestroyed) {
                    ipAddressInput.setText(host)
                    portInput.setText(String.format(Locale.US, "%d", port))
                    ZevClipPreferences.saveEndpoint(this, host, port.toString())
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        ZevClipPreferences.preferences(this)
            .registerOnSharedPreferenceChangeListener(preferencesListener)
    }

    override fun onResume() {
        super.onResume()
        refreshSyncStatuses()
    }

    override fun onPause() {
        ZevClipPreferences.saveEndpoint(
            this,
            ipAddressInput.text.toString(),
            portInput.text.toString()
        )
        ZevClipPreferences.savePairingToken(this, pairingTokenInput.text.toString())
        super.onPause()
    }

    override fun onStop() {
        ZevClipPreferences.preferences(this)
            .unregisterOnSharedPreferenceChangeListener(preferencesListener)
        super.onStop()
    }

    override fun onDestroy() {
        discoveryManager.stop()
        super.onDestroy()
    }

    private fun createContentView(): View {
        val preferences = ZevClipPreferences.preferences(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        content.addView(textView(getString(R.string.screen_title), 26f, Color.BLACK).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(textView(getString(R.string.screen_description), 15f, Color.DKGRAY).apply {
            setPadding(0, dp(6), 0, dp(20))
        })

        content.addView(sectionTitle(R.string.discovery_title))
        content.addView(
            textView(getString(R.string.discovery_instructions), 14f, Color.DKGRAY),
            matchWidth()
        )
        discoverButton = Button(this).apply {
            text = getString(R.string.discover_mac)
            isAllCaps = false
            setOnClickListener { discoveryManager.discover() }
        }
        content.addView(discoverButton, matchWidth(topMargin = 8))

        discoveryStatusText = textView("", 14f, Color.DKGRAY).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(discoveryStatusText, matchWidth())
        content.addView(divider(), dividerLayoutParams(topMargin = 12))

        content.addView(sectionTitle(R.string.pairing_title))
        content.addView(
            textView(getString(R.string.pairing_instructions), 14f, Color.DKGRAY),
            matchWidth()
        )
        content.addView(fieldLabel(R.string.pairing_token_label))
        pairingTokenInput = EditText(this).apply {
            hint = getString(R.string.pairing_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(preferences.getString(ZevClipPreferences.KEY_PAIRING_TOKEN, ""))
        }
        content.addView(pairingTokenInput, matchWidth())

        content.addView(Button(this).apply {
            text = getString(R.string.save_pairing_token)
            isAllCaps = false
            setOnClickListener { savePairingTokenFromUi() }
        }, matchWidth(topMargin = 8))

        pairingStatusText = textView(getString(R.string.pairing_token_not_saved), 14f, Color.DKGRAY).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(pairingStatusText, matchWidth())
        content.addView(divider(), dividerLayoutParams(topMargin = 12))

        content.addView(sectionTitle(R.string.auto_send_title))
        accessibilityStatusText = textView("", 15f, Color.DKGRAY)
        content.addView(accessibilityStatusText, matchWidth())

        content.addView(Button(this).apply {
            text = getString(R.string.open_accessibility_settings)
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, matchWidth(topMargin = 8))

        lastAutoStatusText = textView("", 14f, Color.DKGRAY).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(lastAutoStatusText, matchWidth())
        content.addView(divider(), dividerLayoutParams(topMargin = 12))

        content.addView(sectionTitle(R.string.quick_settings_title))
        content.addView(
            textView(getString(R.string.quick_settings_instructions), 14f, Color.DKGRAY),
            matchWidth()
        )
        content.addView(Button(this).apply {
            text = getString(R.string.add_quick_settings_tile)
            isAllCaps = false
            setOnClickListener { requestQuickSettingsTile() }
        }, matchWidth(topMargin = 8))

        tileStatusText = textView("", 14f, Color.DKGRAY).apply {
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(tileStatusText, matchWidth())
        content.addView(divider(), dividerLayoutParams(topMargin = 12))

        content.addView(sectionTitle(R.string.manual_send_title))
        content.addView(fieldLabel(R.string.mac_ip_label))
        ipAddressInput = EditText(this).apply {
            hint = getString(R.string.mac_ip_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setText(preferences.getString(ZevClipPreferences.KEY_IP_ADDRESS, ""))
        }
        content.addView(ipAddressInput, matchWidth())

        content.addView(fieldLabel(R.string.port_label))
        portInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setText(
                preferences.getString(
                    ZevClipPreferences.KEY_PORT,
                    ZevClipPreferences.DEFAULT_PORT
                )
            )
        }
        content.addView(portInput, matchWidth())

        content.addView(sectionTitle(R.string.pull_mac_clipboard_title))
        content.addView(
            textView(getString(R.string.pull_mac_clipboard_instructions), 14f, Color.DKGRAY),
            matchWidth()
        )
        pullButton = Button(this).apply {
            text = getString(R.string.pull_mac_clipboard)
            isAllCaps = false
            setOnClickListener { pullMacClipboard() }
        }
        content.addView(pullButton, matchWidth(topMargin = 8))

        content.addView(fieldLabel(R.string.text_label))
        textInput = EditText(this).apply {
            hint = getString(R.string.text_hint)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            maxLines = 12
        }
        content.addView(textInput, matchWidth())

        sendButton = Button(this).apply {
            text = getString(R.string.send)
            isAllCaps = false
            setOnClickListener { sendText() }
        }
        content.addView(sendButton, matchWidth(topMargin = 18))

        statusText = textView(getString(R.string.ready), 15f, Color.DKGRAY).apply {
            setPadding(0, dp(16), 0, dp(8))
        }
        content.addView(statusText, matchWidth())

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    private fun sendText() {
        val ipAddress = ipAddressInput.text.toString().trim()
        val portText = portInput.text.toString().trim()
        val text = textInput.text.toString()

        if (!NetworkInputValidator.validateIPv4(ipAddress)) {
            showFailure("Enter a valid IPv4 address, for example 192.168.1.10.")
            ipAddressInput.requestFocus()
            return
        }

        val port = NetworkInputValidator.parsePort(portText)
        if (port == null) {
            showFailure("Enter a port between 1 and 65535.")
            portInput.requestFocus()
            return
        }

        if (text.isEmpty()) {
            showFailure("Enter text to send.")
            textInput.requestFocus()
            return
        }

        val pairingToken = pairingTokenInput.text.toString().trim()
        if (pairingToken.isEmpty()) {
            showFailure("Enter the pairing token shown on the Mac.")
            pairingTokenInput.requestFocus()
            return
        }

        ZevClipPreferences.saveEndpoint(this, ipAddress, port.toString())
        ZevClipPreferences.savePairingToken(this, pairingToken)

        sendButton.isEnabled = false
        statusText.setTextColor(Color.DKGRAY)
        statusText.text = getString(
            R.string.sending_to_endpoint,
            "http://$ipAddress:$port/clipboard"
        )

        thread(name = "ZevClipSender") {
            val result = ClipboardSender.send(ipAddress, port, text, pairingToken)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread

                sendButton.isEnabled = true
                when (result) {
                    is SendResult.Success -> showSuccess(result.message)
                    is SendResult.Failure -> showFailure(result.message)
                }
            }
        }
    }

    private fun pullMacClipboard() {
        val ipAddress = ipAddressInput.text.toString().trim()
        val portText = portInput.text.toString().trim()

        if (!NetworkInputValidator.validateIPv4(ipAddress)) {
            showFailure("Enter a valid IPv4 address, for example 192.168.1.10.")
            ipAddressInput.requestFocus()
            return
        }

        val port = NetworkInputValidator.parsePort(portText)
        if (port == null) {
            showFailure("Enter a port between 1 and 65535.")
            portInput.requestFocus()
            return
        }

        val pairingToken = pairingTokenInput.text.toString().trim()
        if (pairingToken.isEmpty()) {
            showFailure("Enter the pairing token shown on the Mac.")
            pairingTokenInput.requestFocus()
            return
        }

        ZevClipPreferences.saveEndpoint(this, ipAddress, port.toString())
        ZevClipPreferences.savePairingToken(this, pairingToken)

        pullButton.isEnabled = false
        statusText.setTextColor(Color.DKGRAY)
        statusText.text = getString(
            R.string.pulling_from_endpoint,
            "http://$ipAddress:$port/clipboard"
        )

        thread(name = "ZevClipPuller") {
            val result = ClipboardSender.pull(ipAddress, port, pairingToken)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread

                pullButton.isEnabled = true
                when (result) {
                    is PullResult.Success -> copyPulledTextToAndroidClipboard(result.text)
                    PullResult.Empty -> showFailure(getString(R.string.pull_empty))
                    is PullResult.Failure -> showFailure(result.message)
                }
            }
        }
    }

    private fun copyPulledTextToAndroidClipboard(text: String) {
        try {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText(getString(R.string.mac_clipboard_clip_label), text)
            clipboard.setPrimaryClip(clip)
            showSuccess(getString(R.string.pull_success, text.toByteArray(Charsets.UTF_8).size))
        } catch (error: SecurityException) {
            showFailure("Android blocked the clipboard update: ${error.message ?: "permission denied"}")
        }
    }

    private fun savePairingTokenFromUi() {
        val token = pairingTokenInput.text.toString().trim()
        if (token.isEmpty()) {
            pairingStatusText.setTextColor(Color.rgb(180, 32, 32))
            pairingStatusText.text = getString(R.string.pairing_token_empty)
            pairingTokenInput.requestFocus()
            return
        }

        ZevClipPreferences.savePairingToken(this, token)
        pairingStatusText.setTextColor(Color.rgb(24, 120, 54))
        pairingStatusText.text = getString(R.string.pairing_token_saved)
    }

    private fun showSuccess(message: String) {
        statusText.setTextColor(Color.rgb(24, 120, 54))
        statusText.text = message
    }

    private fun showFailure(message: String) {
        statusText.setTextColor(Color.rgb(180, 32, 32))
        statusText.text = message
    }

    private fun refreshSyncStatuses() {
        val enabled = AccessibilityServiceStatus.isEnabled(this)
        accessibilityStatusText.setTextColor(
            if (enabled) Color.rgb(24, 120, 54) else Color.rgb(180, 32, 32)
        )
        accessibilityStatusText.text = getString(
            if (enabled) {
                R.string.accessibility_enabled
            } else {
                R.string.accessibility_not_enabled
            }
        )

        val lastAutoStatus = if (ZevClipPreferences.endpoint(this) == null) {
            getString(R.string.auto_send_waiting_for_endpoint)
        } else {
            ZevClipPreferences.lastAutoStatus(this)
        }

        lastAutoStatusText.text = getString(R.string.last_auto_send_status, lastAutoStatus)
        tileStatusText.text = getString(
            R.string.last_tile_status,
            ZevClipPreferences.lastTileStatus(this)
        )
        discoveryStatusText.text = getString(
            R.string.discovery_status,
            ZevClipPreferences.discoveryStatus(this)
        )

        val hasToken = ZevClipPreferences.pairingToken(this).isNotEmpty()
        pairingStatusText.setTextColor(
            if (hasToken) Color.rgb(24, 120, 54) else Color.DKGRAY
        )
        pairingStatusText.text = getString(
            if (hasToken) {
                R.string.pairing_token_saved
            } else {
                R.string.pairing_token_not_saved
            }
        )
    }

    private fun requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = getSystemService(StatusBarManager::class.java)
            val componentName = ComponentName(this, ClipboardTileService::class.java)
            val icon = Icon.createWithResource(this, R.drawable.ic_sync_clipboard)

            statusBarManager.requestAddTileService(
                componentName,
                getString(R.string.sync_clipboard_tile_label),
                icon,
                mainExecutor
            ) { result ->
                val status = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                        getString(R.string.tile_add_result_added)
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                        getString(R.string.tile_add_result_already_added)
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                        getString(R.string.tile_add_result_not_added)
                    else -> getString(R.string.tile_add_result_open_instructions)
                }

                ZevClipPreferences.setLastTileStatus(this, status)
                refreshSyncStatuses()
            }
        } else {
            ZevClipPreferences.setLastTileStatus(
                this,
                getString(R.string.tile_add_result_open_instructions)
            )
        }
    }

    private fun sectionTitle(textResource: Int): TextView {
        return textView(getString(textResource), 18f, Color.BLACK).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(8))
        }
    }

    private fun fieldLabel(textResource: Int): TextView {
        return textView(getString(textResource), 14f, Color.DKGRAY).apply {
            setPadding(0, dp(14), 0, 0)
        }
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(Color.LTGRAY)
        }
    }

    private fun dividerLayoutParams(topMargin: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply {
            this.topMargin = dp(topMargin)
        }
    }

    private fun textView(value: String, size: Float, color: Int): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
        }
    }

    private fun matchWidth(topMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = dp(topMargin)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

}
