package io.lmp.copilot.ui

import io.lmp.copilot.app.HealthService
import io.lmp.copilot.domain.llm.HealthStatus
import io.lmp.copilot.domain.llm.ModelInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent

/**
 * Phase 1 settings: Ollama URL, model picker, and "Test connection".
 *
 * Future settings (privacy mode, prompt overrides, multiple providers) plug in here
 * by extending the form — the underlying [CopilotSettings] holder can grow.
 */
class SettingsPanel(
    private val healthService: HealthService,
    private val scope: CoroutineScope,
    private val settings: CopilotSettings,
    private val onModelPicked: (String) -> Unit,
) : JPanel(GridBagLayout()) {

    private val urlField = JTextField(settings.ollamaBaseUrl, 30)
    private val modelCombo = JComboBox<String>(DefaultComboBoxModel(arrayOf(settings.defaultModel)))
    private val statusDot = JLabel("●").apply {
        foreground = Color.GRAY
        font = font.deriveFont(font.size + 6f)
    }
    private val statusText = JLabel("not tested")
    private val testButton = JButton("Test connection")
    private val refreshModelsButton = JButton("Refresh models")

    init {
        modelCombo.isEditable = true
        modelCombo.selectedItem = settings.defaultModel

        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

        var row = 0
        addRow(row++, "Ollama base URL:", urlField)
        addRow(row++, "Default model:", modelCombo)
        addRow(row++, "Status:", buildStatusPanel())
        addButtonsRow(row++)
        addFillerRow(row)

        // JTextField.addActionListener only fires on Enter. Users almost never
        // press Enter after typing a URL — they click "Test connection" instead.
        // Without a focus-loss commit, that click would test the previous URL.
        urlField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                commitUrlField()
            }
        })
        urlField.addActionListener { commitUrlField(); runHealthCheck() }

        testButton.addActionListener {
            // Defensive: re-read both fields right before we ping, in case the
            // focus listener hasn't fired yet (e.g. accelerator key, or some
            // platform L&Fs that don't fire focusLost on button click).
            commitUrlField()
            commitModelCombo()
            runHealthCheck()
        }
        refreshModelsButton.addActionListener {
            commitUrlField()
            refreshModels()
        }
        modelCombo.addActionListener { commitModelCombo() }
    }

    private fun commitUrlField() {
        val typed = urlField.text.trim()
        if (typed.isNotEmpty() && typed != settings.ollamaBaseUrl) {
            settings.update { ollamaBaseUrl = typed }
        }
    }

    private fun commitModelCombo() {
        val picked = (modelCombo.selectedItem as? String)?.trim().orEmpty()
        if (picked.isNotEmpty() && picked != settings.defaultModel) {
            settings.update { defaultModel = picked }
            onModelPicked(picked)
        }
    }

    private fun buildStatusPanel(): JPanel {
        val p = JPanel()
        p.layout = BoxLayout(p, BoxLayout.X_AXIS)
        p.add(statusDot)
        p.add(Box.createHorizontalStrut(6))
        p.add(statusText)
        p.add(Box.createHorizontalGlue())
        return p
    }

    private fun addRow(row: Int, label: String, field: java.awt.Component) {
        val g1 = GridBagConstraints().apply {
            gridx = 0; gridy = row; anchor = GridBagConstraints.LINE_START
            insets = Insets(4, 4, 4, 8)
        }
        val g2 = GridBagConstraints().apply {
            gridx = 1; gridy = row; weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        }
        add(JLabel(label), g1)
        add(field, g2)
    }

    private fun addButtonsRow(row: Int) {
        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(testButton)
            add(Box.createHorizontalStrut(8))
            add(refreshModelsButton)
        }
        val g = GridBagConstraints().apply {
            gridx = 1; gridy = row; weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(8, 4, 4, 4)
            anchor = GridBagConstraints.LINE_START
        }
        add(buttons, g)
    }

    private fun addFillerRow(row: Int) {
        val g = GridBagConstraints().apply {
            gridx = 0; gridy = row; gridwidth = 2; weighty = 1.0
            fill = GridBagConstraints.BOTH
        }
        add(Box.createGlue(), g)
    }

    private fun runHealthCheck() {
        statusDot.foreground = Color.ORANGE
        statusText.text = "checking…"
        testButton.isEnabled = false
        scope.launch {
            val result = healthService.ping()
            withContext(Dispatchers.Swing) {
                when (result) {
                    is HealthStatus.Ok -> {
                        statusDot.foreground = Color(0x2E, 0xA4, 0x2E)
                        statusText.text = "OK" + (result.versionInfo?.let { " — $it" } ?: "")
                    }
                    is HealthStatus.Unreachable -> {
                        statusDot.foreground = Color(0xCC, 0x33, 0x33)
                        statusText.text = "unreachable: ${result.reason}"
                    }
                    is HealthStatus.Misconfigured -> {
                        statusDot.foreground = Color(0xCC, 0x88, 0x00)
                        statusText.text = "misconfigured: ${result.reason}"
                    }
                }
                testButton.isEnabled = true
            }
        }
    }

    private fun refreshModels() {
        refreshModelsButton.isEnabled = false
        statusText.text = "loading models…"
        scope.launch {
            val result = healthService.listModels()
            withContext(Dispatchers.Swing) {
                refreshModelsButton.isEnabled = true
                result.onSuccess { models: List<ModelInfo> ->
                    val ids = models.map { it.id }
                    val keep = settings.defaultModel
                    modelCombo.model = DefaultComboBoxModel(ids.toTypedArray())
                    if (keep in ids) {
                        modelCombo.selectedItem = keep
                    } else if (ids.isNotEmpty()) {
                        modelCombo.selectedItem = ids.first()
                        settings.update { defaultModel = ids.first() }
                    }
                    statusText.text = "loaded ${ids.size} models"
                }.onFailure {
                    statusText.text = "model list failed: ${it.message}"
                }
            }
        }
    }

    override fun getPreferredSize(): Dimension = Dimension(640, 220)
}
