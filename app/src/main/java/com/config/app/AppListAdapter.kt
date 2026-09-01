package com.config.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppListAdapter(
    private val apps: List<AppInfo>,
    private val onToggle: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

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
        private val switch: Switch = itemView.findViewById(R.id.switchApp)

        fun bind(app: AppInfo) {
            tvName.text = app.appName
            tvPackage.text = app.packageName
            ivIcon.setImageDrawable(app.icon)

            switch.setOnCheckedChangeListener(null)
            switch.isChecked = app.isSelected
            switch.setOnCheckedChangeListener { _, isChecked ->
                app.isSelected = isChecked
                onToggle(app, isChecked)
            }

            itemView.setOnClickListener {
                switch.isChecked = !switch.isChecked
            }
        }
    }
}