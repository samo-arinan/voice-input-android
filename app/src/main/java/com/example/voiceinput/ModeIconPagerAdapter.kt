package com.example.voiceinput

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class ModeIconPagerAdapter : RecyclerView.Adapter<ModeIconPagerAdapter.IconViewHolder>() {

    companion object {
        const val PAGE_MIC = 0
        const val PAGE_BRAIN = 1
        const val PAGE_KEYBOARD = 2
        const val PAGE_COUNT = 3
    }

    var onPageBound: ((position: Int, view: View) -> Unit)? = null

    class IconViewHolder(val view: View) : RecyclerView.ViewHolder(view)

    override fun getItemCount(): Int = PAGE_COUNT

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconViewHolder {
        val layoutRes = when (viewType) {
            PAGE_MIC -> R.layout.icon_page_mic
            PAGE_BRAIN -> R.layout.icon_page_brain
            PAGE_KEYBOARD -> R.layout.icon_page_keyboard
            else -> throw IllegalArgumentException("Unknown page: $viewType")
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return IconViewHolder(view)
    }

    override fun onBindViewHolder(holder: IconViewHolder, position: Int) {
        onPageBound?.invoke(position, holder.view)
    }
}
