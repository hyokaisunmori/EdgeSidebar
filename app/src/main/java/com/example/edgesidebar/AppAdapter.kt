package com.example.edgesidebar

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppInfo(val label: String, val pkg: String, val icon: Drawable)

class AppAdapter(
    private val selected: MutableSet<String>,
    private val onChanged: (Set<String>) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var all: List<AppInfo> = emptyList()
    private var shown: List<AppInfo> = emptyList()
    private var query: String = ""

    fun submit(list: List<AppInfo>) {
        all = list
        applyFilter()
    }

    fun filter(q: String) {
        query = q.trim()
        applyFilter()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyFilter() {
        shown = if (query.isEmpty()) all else all.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.pkg.contains(query, ignoreCase = true)
        }
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.ivIcon)
        val label: TextView = v.findViewById(R.id.tvLabel)
        val pkg: TextView = v.findViewById(R.id.tvPkg)
        val check: CheckBox = v.findViewById(R.id.cbSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_select, parent, false)
        val vh = VH(v)
        v.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val app = shown[pos]
            if (!selected.add(app.pkg)) selected.remove(app.pkg)
            notifyItemChanged(pos)
            onChanged(selected.toSet())
        }
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val app = shown[position]
        h.icon.setImageDrawable(app.icon)
        h.label.text = app.label
        h.pkg.text = app.pkg
        h.check.isChecked = app.pkg in selected
    }

    override fun getItemCount(): Int = shown.size
}
