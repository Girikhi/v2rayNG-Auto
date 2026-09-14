package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.enums.ManualConfigMode
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.ManualConfigModes
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.Collections

class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?
) : RecyclerView.Adapter<MainRecyclerAdapter.MainViewHolder>(), ItemTouchHelperAdapter {

    private var data: MutableList<ServersCache> = mutableListOf()

    @SuppressLint("NotifyDataSetChanged")
    fun setData(newData: MutableList<ServersCache>?, position: Int = -1) {
        if (newData != null &&
            position in newData.indices &&
            position in data.indices &&
            data[position].guid == newData[position].guid
        ) {
            data[position] = newData[position]
            notifyItemChanged(position)
            return
        }

        data = newData?.toMutableList() ?: mutableListOf()
        notifyDataSetChanged()
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: MainViewHolder, position: Int) {
        val context = holder.binding.root.context
        val server = data[position]
        val selected = server.guid == MmkvManager.getSelectServer()
        val affiliation = MmkvManager.decodeServerAffiliationInfo(server.guid)
        val delayMillis = affiliation?.testDelayMillis ?: 0L

        val manual = ManualConfigModes.isManual(server.profile)
        val hasMode = ManualConfigModes.hasMode(server.profile)
        val fragmentUnavailable = hasMode && ManualConfigModes.isFragmentMode(server.profile) && (
            if (ManualConfigModes.usesFineFragment(server.profile)) {
                !ManualConfigModes.supportsFineFragment(server.profile)
            } else {
                !ManualConfigModes.supportsFragment(server.profile)
            }
        )
        holder.binding.tvName.text = if (manual) server.profile.remarks
            else context.getString(R.string.simple_server_number, position + 1)
        // RecyclerView reuses holders across accounts: reset both constraints for subscriptions.
        holder.binding.tvName.maxLines = if (manual) Int.MAX_VALUE else 1
        holder.binding.tvName.ellipsize = if (manual) null else TextUtils.TruncateAt.END
        holder.binding.tvName.textDirection = if (manual) View.TEXT_DIRECTION_FIRST_STRONG else View.TEXT_DIRECTION_LOCALE
        // Show the actual configured endpoint, not the masked description, SNI or DNS resolver.
        // Binding remains local-only: resolving hostnames here would stall scrolling and pings.
        val serverAddress = server.profile.server.orEmpty().trim()
        holder.binding.tvAddress.isVisible = serverAddress.isNotEmpty()
        holder.binding.tvAddress.text = serverAddress
        holder.binding.tvAddress.contentDescription = context.getString(R.string.simple_server_address_description, serverAddress)
        holder.binding.buttonEdit.isVisible = true
        holder.binding.buttonEdit.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            val current = data.getOrNull(currentPosition)
            if (current != null) {
                adapterListener?.onEdit(current.guid, currentPosition, current.profile)
            }
        }
        holder.binding.tvMode.isVisible = hasMode
        holder.binding.tvMode.setText(when (server.profile.manualMode) {
            ManualConfigMode.FRAGMENT -> if (fragmentUnavailable) R.string.simple_mode_fragment_unavailable
                else R.string.simple_mode_fragment
            ManualConfigMode.FINE_FRAGMENT -> if (fragmentUnavailable) R.string.simple_mode_fine_fragment_unavailable
                else R.string.simple_mode_fine_fragment
            ManualConfigMode.GOOGLE_DOH -> R.string.simple_mode_google_doh
            else -> R.string.simple_mode_original
        })
        holder.binding.tvTestResult.text = when {
            fragmentUnavailable -> context.getString(R.string.simple_mode_unavailable)
            delayMillis > 0L -> context.getString(R.string.server_test_delay_value, delayMillis)
            delayMillis < 0L -> context.getString(R.string.simple_ping_failed)
            else -> context.getString(R.string.simple_not_tested)
        }
        holder.binding.tvTestResult.setTextColor(
            ContextCompat.getColor(
                context,
                when {
                    fragmentUnavailable -> R.color.colorPingRed
                    delayMillis > 0L -> R.color.colorPing
                    delayMillis < 0L -> R.color.colorPingRed
                    else -> R.color.md_theme_onSurfaceVariant
                }
            )
        )
        holder.binding.itemBg.setCardBackgroundColor(
            ContextCompat.getColor(
                context,
                if (selected) R.color.md_theme_primaryContainer else R.color.simple_surface
            )
        )
        holder.binding.itemBg.strokeColor = ContextCompat.getColor(
            context,
            if (selected) R.color.md_theme_primary else R.color.md_theme_outlineVariant
        )
        holder.binding.layoutIndicator.setBackgroundResource(
            if (selected) R.color.md_theme_primary else android.R.color.transparent
        )
        holder.binding.infoContainer.setOnClickListener {
            if (fragmentUnavailable) context.toast(
                if (ManualConfigModes.usesFineFragment(server.profile)) {
                    R.string.simple_fine_fragment_requires_tls
                } else {
                    R.string.simple_fragment_requires_tcp
                }
            )
            else adapterListener?.onSelectServer(server.guid)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainViewHolder =
        MainViewHolder(
            ItemRecyclerMainBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )

    fun removeServerSub(guid: String, position: Int) {
        val index = data.indexOfFirst { it.guid == guid }
        if (index >= 0) {
            data.removeAt(index)
            notifyItemRemoved(index)
            notifyItemRangeChanged(index, data.size - index)
        }
    }

    fun setSelectServer(fromPosition: Int, toPosition: Int) {
        if (fromPosition in data.indices) notifyItemChanged(fromPosition)
        if (toPosition in data.indices) notifyItemChanged(toPosition)
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        mainViewModel.swapServer(fromPosition, toPosition)
        if (fromPosition in data.indices && toPosition in data.indices) {
            Collections.swap(data, fromPosition, toPosition)
            notifyItemMoved(fromPosition, toPosition)
        }
        return true
    }

    override fun onItemMoveCompleted() = Unit

    override fun onItemDismiss(position: Int) = Unit

    class MainViewHolder(val binding: ItemRecyclerMainBinding) :
        RecyclerView.ViewHolder(binding.root), ItemTouchHelperViewHolder {

        override fun onItemSelected() {
            itemView.alpha = 0.72f
        }

        override fun onItemClear() {
            itemView.alpha = 1f
        }
    }
}
