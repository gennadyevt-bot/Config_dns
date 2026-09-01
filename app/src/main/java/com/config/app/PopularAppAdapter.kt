package com.config.app

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

data class PopularApp(
    val packageName: String,
    val appName: String,
    var icon: Drawable? = null,
    var isInstalled: Boolean = false
)

class PopularAppAdapter(
    private val apps: List<PopularApp>,
    private val selectedPackages: MutableSet<String>,
    private val onToggle: (PopularApp, Boolean) -> Unit
) : RecyclerView.Adapter<PopularAppAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivPopularIcon)
        val tvName: TextView = view.findViewById(R.id.tvPopularName)
        val root: View = view.findViewById(R.id.popularItemRoot)

        fun bind(app: PopularApp) {
            tvName.text = app.appName
            if (app.icon != null) {
                ivIcon.setImageDrawable(app.icon)
            } else {
                ivIcon.setImageDrawable(ContextCompat.getDrawable(itemView.context, android.R.drawable.sym_def_app_icon))
            }

            val isSelected = selectedPackages.contains(app.packageName)
            if (isSelected) {
                root.setBackgroundColor(Color.parseColor("#1a4d1a"))
                root.alpha = 1.0f
            } else {
                root.setBackgroundResource(R.drawable.domain_item_bg)
                root.alpha = 0.5f
            }

            itemView.setOnClickListener {
                if (!app.isInstalled) return@setOnClickListener
                val newState = !selectedPackages.contains(app.packageName)
                onToggle(app, newState)
                notifyItemChanged(bindingAdapterPosition)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_popular_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount() = apps.size
}