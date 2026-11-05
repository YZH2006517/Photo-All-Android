package org.photoall.film.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/** Plain scrolling list of every setting, grouped. Long-press a row to reset it. */
class SettingsPanel(
    context: Context,
    private val store: SettingsStore,
    onBench: () -> Unit,
    onClose: () -> Unit,
) : LinearLayout(context) {
    private val refreshers = mutableListOf<() -> Unit>()

    init {
        orientation = VERTICAL
        setBackgroundColor(0xff161616.toInt()) // opaque: nothing underneath to blend
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 16, 16, 16)
            addView(text("参数", 18f), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply { text = "测试台"; setOnClickListener { onBench() } })
            addView(Button(context).apply { text = "全部恢复默认"; setOnClickListener { store.resetAll(); refresh() } })
            addView(Button(context).apply { text = "完成"; setOnClickListener { onClose() } })
        }
        val list = LinearLayout(context).apply { orientation = VERTICAL; setPadding(32, 0, 32, 48) }
        var group = ""
        for (setting in Settings.all) {
            if (setting.group.isEmpty()) continue // set elsewhere (e.g. the camera's LUT picker)
            if (setting.group != group) {
                group = setting.group
                list.addView(text(group, 15f, 0xffe0b060.toInt()).apply { setPadding(0, 32, 0, 8) })
            }
            list.addView(row(setting))
        }
        addView(header)
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun refresh() = refreshers.forEach { it() }

    private fun row(setting: Setting): View = when (setting) {
        is Setting.Slider -> sliderRow(setting)
        is Setting.Toggle -> Switch(context).apply {
            text = setting.label
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 12)
            // Fixed colours: gold when on, grey when off, readable whatever the system theme.
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(ACCENT, 0xffd0d0d0.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0x99e0b060.toInt(), 0x66ffffff))
            val sync = { isChecked = store.flag(setting.key) }
            sync()
            refreshers += sync
            setOnCheckedChangeListener { _, on -> if (on != store.flag(setting.key)) store[setting.key] = if (on) 1f else 0f }
            resettable(setting)
        }
        is Setting.Choice -> LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(setting.label, 14f), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val spinner = Spinner(context).apply {
                // The platform item text is dark; on this dark panel the chosen value vanished.
                adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, setting.options) {
                    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                        (super.getView(position, convertView, parent) as TextView).apply {
                            setTextColor(Color.WHITE)
                            gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        }
                    // The popup list, too: light text on a dark sheet, chosen item in gold.
                    override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                        (super.getDropDownView(position, convertView, parent) as TextView).apply {
                            setTextColor(if (position == selectedItemPosition) ACCENT else Color.WHITE)
                            setBackgroundColor(0xff262626.toInt())
                        }
                }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xff262626.toInt()))
                backgroundTintList = ColorStateList.valueOf(0xffbbbbbb.toInt()) // the ▼ arrow
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
        val value = text("", 13f).apply { gravity = Gravity.END; minWidth = 150 }
        val seek = SeekBar(context).apply {
            max = steps
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0xff808080.toInt())
        }
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
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = toValue(progress)
                value.text = label(v)
                store[setting.key] = v
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, 8, 0, 8)
            addView(LinearLayout(context).apply {
                addView(text(setting.label, 14f), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
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

    private fun text(s: String, size: Float, colour: Int = Color.WHITE) = TextView(context).apply {
        text = s
        textSize = size
        setTextColor(colour)
    }

    private companion object {
        const val ACCENT = 0xffe0b060.toInt()
    }
}
