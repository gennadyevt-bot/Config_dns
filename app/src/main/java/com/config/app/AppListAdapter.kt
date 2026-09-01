package com.config.app

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppListAdapter(
    private val apps: List<AppInfo>,
    private val pm: PackageManager,
    private val onCheckedChange: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    private val iconCache = mutableMapOf<String, android.graphics.drawable.Drawable?>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount(): Int = apps.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvPackage: TextView = itemView.findViewById(R.id.tvAppPackage)
        private val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    cbSelect.isChecked = !cbSelect.isChecked
                }
            }
        }

        fun bind(app: AppInfo) {
            tvName.text = app.appName
            tvPackage.text = app.packageName

            // Ленивая загрузка иконки с кешированием
            val cached = iconCache[app.packageName]
            if (cached != null) {
                ivIcon.setImageDrawable(cached)
            } else if (app.icon != null) {
                ivIcon.setImageDrawable(app.icon)
                iconCache[app.packageName] = app.icon
            } else {
                try {
                    val icon = pm.getApplicationIcon(app.packageName)
                    ivIcon.setImageDrawable(icon)
                    iconCache[app.packageName] = icon
                } catch (e: Exception) {
                    ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                    iconCache[app.packageName] = null
                }
            }

            cbSelect.setOnCheckedChangeListener(null)
            cbSelect.isChecked = app.isSelected
            cbSelect.setOnCheckedChangeListener { _, isChecked ->
                app.isSelected = isChecked
                onCheckedChange(app, isChecked)
            }
        }
    }
}