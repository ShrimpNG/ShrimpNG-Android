package com.v2ray.ang.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ItemHomeServerBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager

/**
 * Plain (non-DiffUtil) adapter: ping results and the selected server are read live from
 * MmkvManager at bind time and aren't part of [ServersCache]'s own equality, so a diff would
 * miss those changes. Callers re-submit the full list on every refresh.
 */
class HomeServerAdapter(
    private val listener: MainAdapterListener,
) : RecyclerView.Adapter<HomeServerAdapter.ViewHolder>() {

    private companion object {
        /**
         * Grace period before the ring appears at all. A tap-to-select lifts well inside this
         * window, so selecting a server never flashes a stub of the edit ring.
         */
        const val HOLD_START_DELAY_MS = 180L

        /** How long the row must be held, past the grace period, before edit opens. */
        const val HOLD_DURATION_MS = 620L
        const val HOLD_RELEASE_MS = 140L
    }

    private var data: List<ServersCache> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newData: List<ServersCache>) {
        data = newData
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): ServersCache? = data.getOrNull(position)

    override fun getItemCount() = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHomeServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(data[position], position)
    }

    inner class ViewHolder(private val binding: ItemHomeServerBinding) : RecyclerView.ViewHolder(binding.root) {

        private var holdAnimator: ValueAnimator? = null

        /**
         * Set the moment the ring closes, so the ACTION_UP that follows doesn't also register as
         * a tap and switch the selected server behind the edit screen that just opened.
         */
        private var holdTriggered = false

        /**
         * The card's own outline width when nothing is being held — 0, or the selection outline
         * on the active server. Stashed so the hold ring can take the card's outline over for
         * the duration of the hold and hand it back afterwards.
         */
        private var restingStrokeWidth = 0

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: ServersCache, position: Int) {
            val context = binding.root.context
            val guid = item.guid
            val profile = item.profile

            binding.tvServerName.text = profile.remarks
            val detail = getAddressAndProtocol(profile)
            binding.tvServerDetail.text = detail
            binding.tvServerDetail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE

            val affiliation = MmkvManager.decodeServerAffiliationInfo(guid)
            binding.tvServerPing.text = affiliation?.getTestDelayString().orEmpty()
            val pingColor = if ((affiliation?.testDelayMillis ?: 0L) < 0L) R.color.colorPingRed else R.color.colorPing
            binding.tvServerPing.setTextColor(ContextCompat.getColor(context, pingColor))

            val isSelected = guid == MmkvManager.getSelectServer()
            restingStrokeWidth = if (isSelected) {
                context.resources.getDimensionPixelSize(R.dimen.subscription_card_active_stroke)
            } else {
                0
            }
            binding.cardRoot.strokeWidth = restingStrokeWidth
            if (isSelected) {
                // Resolve the theme attribute at runtime (not the static @color resource) so
                // this respects Dynamic Color, matching SubscriptionRowAdapter's active outline.
                binding.cardRoot.strokeColor = com.google.android.material.color.MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
            }

            binding.viewHoldProgress.apply {
                cornerRadiusPx = binding.cardRoot.radius
                setStrokeColor(com.google.android.material.color.MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary))
            }
            resetHold()

            binding.layoutServerRow.setOnTouchListener { row, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> startHold(row, guid, profile, position)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseHold()
                    MotionEvent.ACTION_MOVE -> {
                        val strayed = event.x < 0 || event.y < 0 || event.x > row.width || event.y > row.height
                        if (strayed) releaseHold()
                    }
                }
                // Never consume: the row keeps its ripple and its own click handling.
                false
            }

            binding.layoutServerRow.setOnClickListener {
                if (holdTriggered) {
                    holdTriggered = false
                } else {
                    listener.onSelectServer(guid)
                }
            }
            // more=true: full option list (QR/clipboard/full-config export + Edit + Delete) —
            // there's only one overflow icon on this row, no separate edit/remove buttons.
            binding.btnMore.setOnClickListener { listener.onShare(guid, profile, position, true) }
        }

        private fun startHold(row: View, guid: String, profile: ProfileItem, position: Int) {
            holdAnimator?.cancel()
            holdTriggered = false

            val ring = binding.viewHoldProgress
            ring.progress = 0f
            var cancelled = false
            holdAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = HOLD_START_DELAY_MS
                duration = HOLD_DURATION_MS
                interpolator = LinearInterpolator()
                addUpdateListener { ring.progress = it.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        // Runs once the grace period is over. Dropping the selected-server
                        // outline any earlier would make a plain tap blink its own border off:
                        // it only needs to go while the ring is actually drawn, since the two
                        // sit a hairline apart and otherwise read as a doubled border.
                        binding.cardRoot.strokeWidth = 0
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        holdTriggered = true
                        ring.progress = 0f
                        binding.cardRoot.strokeWidth = restingStrokeWidth
                        row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        listener.onEdit(guid, position, profile)
                    }
                })
                start()
            }
        }

        /** Let the ring retract instead of snapping away when the hold is abandoned. */
        private fun releaseHold() {
            holdAnimator?.cancel()
            val ring = binding.viewHoldProgress
            if (ring.progress <= 0f) {
                holdAnimator = null
                binding.cardRoot.strokeWidth = restingStrokeWidth
                return
            }
            holdAnimator = ValueAnimator.ofFloat(ring.progress, 0f).apply {
                duration = HOLD_RELEASE_MS
                addUpdateListener { ring.progress = it.animatedValue as Float }
                // Hand the outline back only once the ring is fully gone, so the two are never
                // on screen together.
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        binding.cardRoot.strokeWidth = restingStrokeWidth
                    }
                })
                start()
            }
        }

        private fun resetHold() {
            holdAnimator?.cancel()
            holdAnimator = null
            holdTriggered = false
            binding.viewHoldProgress.progress = 0f
            binding.cardRoot.strokeWidth = restingStrokeWidth
        }

        private fun getAddressAndProtocol(profile: ProfileItem): String {
            // A balancer has no single address worth showing, so the row is just its name. Checked
            // here rather than relying on the stored description, which still holds the old
            // strategy/member text on profiles imported before this changed.
            if ((profile.balancerMemberCount ?: 0) >= 2) return ""

            val protocol = getProtocolDescription(profile)
            val showAddress = MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_SERVER_ADDRESS_HOME)
            if (!showAddress) return protocol

            val address = profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile)
            return if (protocol.isEmpty()) address else "$address · $protocol"
        }

        private fun getProtocolDescription(profile: ProfileItem): String {
            if (profile.configType.isComplexType()) {
                return profile.configType.name
            }

            val parts = mutableListOf(profile.configType.name)
            profile.network?.let { net ->
                if (net.isNotBlank() && !net.equals("tcp", ignoreCase = true)) {
                    parts.add(net)
                }
            }
            profile.security?.let { sec ->
                if (sec.isNotBlank()) {
                    parts.add(if (profile.insecure == true && sec.equals("tls", ignoreCase = true)) "$sec insecure" else sec)
                }
            }
            return parts.joinToString(" / ")
        }
    }
}
