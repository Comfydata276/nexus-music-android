package org.medianexus.standin

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * FIXTURE. A screen with a thousand rows, so scripts/measure has a long list to scroll
 * and a start-up to time before the real app has either.
 */
class ListActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = ListView(this)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = 1000
            override fun getItem(position: Int) = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@ListActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val pad = dp(16)
                    setPadding(pad, 0, pad, 0)
                    minimumHeight = dp(56)
                    addView(View(context), LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(TextView(context).apply { textSize = 15f; setPadding(pad, 0, 0, 0) })
                }
                row.getChildAt(0).background = ColorDrawable(Color.HSVToColor(floatArrayOf((position * 37 % 360).toFloat(), 0.5f, 0.8f)))
                (row.getChildAt(1) as TextView).text = "Row ${position + 1} of 1000"
                return row
            }
        }
        setContentView(list)
        reportFullyDrawn()
    }

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
