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

/** Scrolling list of every setting, grouped, separated by hairlines. Long-press a row to reset it. */
class SettingsPanel(
    context: Context,
    private val store: SettingsStore,
    onBench: () -> Unit,
    onClose: () -> Unit,
) : LinearLayout(context) {
    private val refreshers = mutableListOf<() -> Unit>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Style.BG) // opaque: nothing underneath to blend
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(8), dp(10))
            addView(text("参数", 20f).apply { typeface = Style.medium }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(action("测试台", Style.TEXT_2) { onBench() })
            addView(action("恢复默认", Style.TEXT_2) { store.resetAll(); refresh() })
            addView(action("完成", Style.ACCENT) { onClose() })
        }
        val list = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), 0, dp(20), dp(40)) }
        var group = ""
        for (setting in Settings.all) {
            if (setting.group.isEmpty()) continue // set elsewhere (e.g. the camera's LUT picker)
            if (setting.group != group) {
                group = setting.group
                list.addView(text(group, 11f).apply {
                    Style.sectionTitle(this)
                    setPadding(0, dp(28), 0, dp(8))
                })
            } else list.addView(Style.hairline(context), LayoutParams(LayoutParams.MATCH_PARENT, 1))
            list.addView(row(setting))
        }
        addView(header)
        addView(Style.hairline(context), LayoutParams(LayoutParams.MATCH_PARENT, 1))
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun refresh() = refreshers.forEach { it() }

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
