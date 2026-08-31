package com.config.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class ServerAdapter(
    private val servers: MutableList<ServerInfo>,
    private val onConnectClick: (ServerInfo) -> Unit,
    private val onStopClick: (ServerInfo) -> Unit,
    private val onAddClick: (ServerInfo, Int) -> Unit,
    private val onLongPress: (ServerInfo, Int) -> Unit
) : RecyclerView.Adapter<ServerAdapter.ServerViewHolder>() {

    private var selectedServerId: String? = null
    private var currentStatus: VpnStatus = VpnStatus.DISCONNECTED

    fun setSelectedServer(id: String?) {
        selectedServerId = id
        notifyDataSetChanged()
    }

    fun setStatus(status: VpnStatus) {
        currentStatus = status
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server, parent, false)
        return ServerViewHolder(view)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        val server = servers[position]
        val isEmpty = !hasValidConfig(server)
        holder.bind(server, isEmpty, selectedServerId == server.id, currentStatus)

        holder.ivAdd.setOnClickListener {
            onAddClick(server, position)
        }

        holder.btnToggle.setOnClickListener {
            when (currentStatus) {
                VpnStatus.CONNECTED -> {
                    if (selectedServerId == server.id) {
                        onStopClick(server)
                    } else {
                        onConnectClick(server)
                    }
                }
                VpnStatus.CONNECTING, VpnStatus.SWITCHING -> { }
                else -> onConnectClick(server)
            }
        }

        holder.itemView.setOnLongClickListener {
            onLongPress(server, position)
            true
        }
    }

    override fun getItemCount(): Int = servers.size

    private fun hasValidConfig(server: ServerInfo): Boolean {
        return server.interfacePrivateKey.isNotEmpty() &&
                server.peerPublicKey.isNotEmpty() &&
                server.peerEndpoint.isNotEmpty()
    }

    class ServerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val viewIndicator: View = itemView.findViewById(R.id.viewIndicator)
        val ivAdd: ImageView = itemView.findViewById(R.id.ivAdd)
        val btnToggle: MaterialButton = itemView.findViewById(R.id.btnToggle)

        fun bind(server: ServerInfo, isEmpty: Boolean, isSelected: Boolean, status: VpnStatus) {
            if (isEmpty) {
                tvName.text = "Empty Slot"
                tvStatus.text = "Tap + to add config"
                tvStatus.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.darker_gray))
                viewIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.darker_gray))
                ivAdd.isVisible = true
                btnToggle.isVisible = false
            } else {
                tvName.text = server.name
                when {
                    isSelected && status == VpnStatus.CONNECTED -> {
                        tvStatus.text = "● Connected"
                        tvStatus.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                        viewIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                        btnToggle.text = "STOP"
                        btnToggle.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                    }
                    isSelected && (status == VpnStatus.CONNECTING || status == VpnStatus.SWITCHING) -> {
                        tvStatus.text = if (status == VpnStatus.SWITCHING) "Switching..." else "Connecting..."
                        tvStatus.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_orange_dark))
                        viewIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_orange_dark))
                        btnToggle.text = "..."
                        btnToggle.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_orange_dark))
                    }
                    else -> {
                        tvStatus.text = "Tap CONNECT"
                        tvStatus.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.darker_gray))
                        viewIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.darker_gray))
                        btnToggle.text = "CONNECT"
                        btnToggle.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                    }
                }
                ivAdd.isVisible = false
                btnToggle.isVisible = true
            }
        }
    }
}
