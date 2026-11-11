package org.photoall.film.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import org.photoall.film.ui.Slider
import org.photoall.film.ui.Style

/**
 * Scrolling list of the settings, grouped, separated by hairlines. Long-press a row to reset it.
 * By default every setting of the device; [only] narrows it (an import's engine parameters,
 * kept in [store] of its own), [onBench] null leaves the test bench out.
 */
class SettingsPanel(
    context: Context,
    private val store: SettingValues,
    onBench: (() -> Unit)?,
    onClose: () -> Unit,
    title: String = "参数",
    only: (Setting) -> Boolean = { true },
    private val open: MutableSet<String> = openOnDevice,
    /** The guide, as the list's first entry (the device's settings only). */
    onGuide: (() -> Unit)? = null,
    /** Diagnostics to copy when something does not work (the device's settings only). */
    onDiagnostics: (() -> Unit)? = null,
) : LinearLayout(context) {
    private val refreshers = mutableListOf<() -> Unit>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Style.BG) // opaque: nothing underneath to blend
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(8), dp(10))
            addView(text(title, 20f).apply { typeface = Style.medium }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            if (onBench != null) addView(action("测试台", Style.TEXT_2) { onBench() })
            addView(action("恢复默认", Style.TEXT_2) { store.resetAll(); refresh() })
            addView(action("完成", Style.ACCENT) { onClose() })
        }
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(40))
            // sections open and close smoothly
            layoutTransition = android.animation.LayoutTransition().apply {
                enableTransitionType(android.animation.LayoutTransition.CHANGING)
                setDuration(200)
            }
        }
        list.addView(text("点分类展开 · 长按某一项恢复默认", 12f).apply {
            setTextColor(Style.TEXT_3)
            setPadding(dp(4), dp(4), 0, dp(8))
        })
        if (onGuide != null) list.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = Style.surface(context, fill = Style.SURFACE, radiusDp = 14f, stroke = Style.ACCENT)
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(text("使用指南", 16f).apply { typeface = Style.medium; setTextColor(Style.ACCENT) })
                addView(text("每个按键与手势的完整说明", 12f).apply { setTextColor(Style.TEXT_2) })
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(android.widget.ImageView(context).apply {
                setImageDrawable(org.photoall.film.ui.Icon(org.photoall.film.ui.Icon.Kind.CHEVRON, Style.ACCENT, 1.8f))
            }, LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
            Style.pressable(this)
            setOnClickListener { onGuide() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        if (onDiagnostics != null) list.addView(text("复制诊断信息（反馈问题时用）", 13f).apply {
            setTextColor(Style.TEXT_2)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            Style.pressable(this)
            setOnClickListener { onDiagnostics() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        // One section per topic, folded until opened (the list was long and mixed). A topic's
        // settings are together even where the list interleaves them.
        val groups = LinkedHashMap<String, MutableList<Setting>>()
        for (setting in Settings.all) {
            if (setting.group.isEmpty()) continue // set elsewhere (e.g. the camera's LUT picker)
            if (!only(setting)) continue
            groups.getOrPut(setting.group) { mutableListOf() } += setting
        }
        for ((name, items) in groups) {
            val body = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(14), 0, dp(14), dp(6))
                visibility = if (name in open) VISIBLE else GONE
                items.forEachIndexed { i, setting ->
                    if (i > 0) addView(Style.hairline(context), LayoutParams(LayoutParams.MATCH_PARENT, 1))
                    addView(row(setting))
                }
            }
            val chevron = android.widget.ImageView(context).apply {
                setImageDrawable(org.photoall.film.ui.Icon(org.photoall.film.ui.Icon.Kind.CHEVRON, Style.TEXT_2, 1.8f))
                rotation = if (name in open) 90f else 0f
            }
            val head = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(54)
                setPadding(dp(14), 0, dp(10), 0)
                addView(text(name, 16f).apply { typeface = Style.medium }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(text("${items.size} 项", 12f).apply { setTextColor(Style.TEXT_3) })
                addView(chevron, LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
                setOnClickListener {
                    val opening = body.visibility != VISIBLE
                    body.visibility = if (opening) VISIBLE else GONE
                    if (opening) open += name else open -= name
                    chevron.animate().rotation(if (opening) 90f else 0f).setDuration(180).start()
                }
            }
            list.addView(LinearLayout(context).apply {
                orientation = VERTICAL
                background = Style.surface(context, fill = Style.SURFACE, radiusDp = 14f)
                addView(head)
                addView(body)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        addView(header)
        addView(Style.hairline(context), LayoutParams(LayoutParams.MATCH_PARENT, 1))
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun refresh() = refreshers.forEach { it() }

    private companion object {
        /** Sections open, kept while the app runs (the screen is rebuilt on a theme change). */
        val openOnDevice = mutableSetOf<String>()
    }

    private fun row(setting: Setting): View = when (setting) {
        is Setting.Slider -> sliderRow(setting)
        is Setting.Toggle -> Switch(context).apply {
            text = setting.label
            textSize = 15f
            setTextColor(Style.TEXT)
            minHeight = dp(52)
            setPadding(0, dp(6), 0, dp(6))
            Style.switch(this)
            val sync = { isChecked = store.flag(setting.key) }
            sync()
            refreshers += sync
            setOnCheckedChangeListener { _, on -> if (on != store.flag(setting.key)) store[setting.key] = if (on) 1f else 0f }
            resettable(setting)
        }
        is Setting.Choice -> LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            addView(text(setting.label, 15f), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val spinner = Spinner(context).apply {
                // The chosen value in secondary grey, right aligned; the popup a dark sheet
                // with the chosen item in the accent.
                adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, setting.options) {
                    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                        (super.getView(position, convertView, parent) as TextView).apply {
                            setTextColor(Style.TEXT_2)
                            textSize = 14f
                            gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        }
                    override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                        (super.getDropDownView(position, convertView, parent) as TextView).apply {
                            setTextColor(if (position == selectedItemPosition) Style.ACCENT else Style.TEXT)
                            textSize = 15f
                            setBackgroundColor(Style.SURFACE_2)
                        }
                }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                setPopupBackgroundDrawable(ColorDrawable(Style.SURFACE_2))
                backgroundTintList = ColorStateList.valueOf(Style.TEXT_3) // the ▼ arrow
            }
            val sync = { spinner.setSelection(store[setting.key].toInt().coerceIn(0, setting.options.lastIndex)) }
            sync()
            refreshers += sync
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position != store[setting.key].toInt()) store[setting.key] = position.toFloat()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            addView(spinner, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.4f))
            resettable(setting)
        }
    }

    private fun sliderRow(setting: Setting.Slider): View {
        val steps = if (setting.integer) (setting.max - setting.min).toInt() else 1000
        val value = text("", 14f).apply {
            gravity = Gravity.END
            minWidth = dp(64)
            setTextColor(Style.TEXT_2)
            Style.numeric(this)
        }
        // Not a SeekBar: see Slider (dozens of them scrolling flooded the main thread).
        val seek = Slider(context).apply { max = steps }
        fun label(v: Float) = when (setting.key) {
            Settings.SHUTTER -> Settings.shutterLabel(v)
            Settings.ISO -> Settings.iso(v).toString()
            else -> setting.format.format(v)
        }
        fun toValue(progress: Int) = setting.min + (setting.max - setting.min) * progress / steps
        val sync = {
            val v = store[setting.key]
            seek.progress = Math.round((v - setting.min) / (setting.max - setting.min) * steps)
            value.text = label(v)
        }
        sync()
        refreshers += sync
        seek.onChange = { progress ->
            val v = toValue(progress)
            value.text = label(v)
            store[setting.key] = v
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(12), 0, dp(8))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(setting.label, 15f), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(value)
            })
            addView(seek)
            resettable(setting)
        }
    }

    private fun View.resettable(setting: Setting) = setOnLongClickListener {
        store[setting.key] = setting.default
        refresh()
        true
    }

    private fun action(label: String, colour: Int, onClick: () -> Unit) = Button(context).apply {
        text = label
        Style.textButton(this)
        setTextColor(colour)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    private fun text(s: String, size: Float) = TextView(context).apply {
        text = s
        textSize = size
        setTextColor(Style.TEXT)
    }

    private fun dp(v: Int) = Style.dp(context, v.toFloat())
}
