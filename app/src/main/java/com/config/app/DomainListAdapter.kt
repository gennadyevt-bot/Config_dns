package com.config.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DomainListAdapter(
    private val domains: MutableList<String>,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<DomainListAdapter.DomainViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DomainViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_domain, parent, false)
        return DomainViewHolder(view)
    }

    override fun onBindViewHolder(holder: DomainViewHolder, position: Int) {
        holder.bind(domains[position])
    }

    override fun getItemCount(): Int = domains.size

    inner class DomainViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDomain: TextView = itemView.findViewById(R.id.tvDomain)
        private val ivDelete: ImageView = itemView.findViewById(R.id.ivDelete)

        fun bind(domain: String) {
            tvDomain.text = domain
            ivDelete.setOnClickListener {
                onDelete(domain)
            }
        }
    }
}
