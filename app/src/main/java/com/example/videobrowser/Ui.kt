package com.example.videobrowser

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.ImageButton
import android.widget.ImageView

fun Context.rounded(color: Int, r: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
    cornerRadius = dp(r).toFloat()
    setColor(color)
    if (stroke != 0) setStroke(dp(1), stroke)
}

fun Context.circle(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun Context.ripple(): Int {
    val tv = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
    return tv.resourceId
}

fun Context.iconBtn(res: Int, tint: Int, pad: Int = 10): ImageButton = ImageButton(this).apply {
    setImageResource(res)
    setColorFilter(tint)
    setBackgroundResource(ripple())
    scaleType = ImageView.ScaleType.FIT_CENTER
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}
