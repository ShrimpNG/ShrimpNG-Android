package com.v2ray.ang.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Animatable
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.databinding.ItemHomeServerBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.FavoritesManager
import com.v2ray.ang.handler.MmkvManager
import kotlin.math.abs

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

        /** Matches the measure button's width in the layout, plus a gap to the card. */
        const val REVEAL_WIDTH_DP = 64f

        /** One of the buttons behind the end of the card, as laid out. */
        const val ACTION_WIDTH_DP = 48f
        const val REVEAL_GAP_DP = 8f

        /** How far the card must be dragged before letting go leaves the button showing. */
        const val REVEAL_SETTLE_FRACTION = 0.4f
        const val REVEAL_ANIM_MS = 180L
    }

    private var data: List<ServersCache> = emptyList()

    /** Guids currently queued for or undergoing a measurement; they show a spinner, not a number. */
    private var testing: Set<String> = emptySet()

    /**
     * At most one row shows its measure button at a time. Opening another closes this one, the way
     * a swipe-to-reveal list is expected to behave — two open rows read as one of them being stuck.
     */
    private var openHolder: ViewHolder? = null
    private val attachedHolders = mutableSetOf<ViewHolder>()

    fun clearTransientUi() {
        attachedHolders.forEach { it.resetTransientUi() }
        openHolder?.resetTransientUi()
        openHolder = null
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        attachedHolders += holder
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        attachedHolders -= holder
        holder.resetTransientUi()
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.resetTransientUi()
        super.onViewRecycled(holder)
    }

    /**
     * Only the rows whose state actually changed are redrawn.
     *
     * A measurement run reports one server at a time, and redrawing the whole list on each result
     * rebinds every other row that is still spinning — dozens of times over a run.
     */
    fun setTestingGuids(guids: Set<String>) {
        if (guids == testing) return
        val previous = testing
        testing = guids
        data.forEachIndexed { index, item ->
            if ((item.guid in previous) != (item.guid in guids)) {
                notifyItemChanged(index)
            }
        }
    }

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
        private var holdTick: Runnable? = null

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

        /** What the ping chip last showed, so it animates in only when a result arrives. */
        private var pingShownFor: String? = null
        private var pingShown = 0L

        /**
         * The ping as a tonal chip: hidden until there is a result, then in the colour of how good
         * it is — the text in that colour, the chip a light wash of it. Formatted here rather than
         * on the DTO, which has no Context and so could only emit an untranslated "ms".
         */
        private fun bindPing(guid: String, delay: Long) {
            val chip = binding.tvServerPing
            val context = chip.context
            val arrived = pingShownFor == guid && pingShown == 0L && delay != 0L
            pingShownFor = guid
            pingShown = delay
            if (delay == 0L) {
                chip.animate().cancel()
                chip.visibility = View.GONE
                return
            }
            chip.text = if (delay > 0L) context.getString(R.string.shrimp_ping_ms, delay)
            else context.getString(R.string.shrimp_ping_unavailable)
            val color = ContextCompat.getColor(
                context,
                when {
                    delay < 0L -> R.color.colorPingRed
                    delay < 300L -> R.color.colorPing
                    delay < 800L -> R.color.colorPingFair
                    else -> R.color.colorPingSlow
                }
            )
            chip.setTextColor(color)
            chip.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x2E))
            chip.visibility = View.VISIBLE
            if (arrived) {
                chip.alpha = 0f
                chip.scaleX = 0.7f
                chip.scaleY = 0.7f
                chip.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(OvershootInterpolator(1.6f)).start()
            } else {
                chip.animate().cancel()
                chip.alpha = 1f
                chip.scaleX = 1f
                chip.scaleY = 1f
            }
        }

        private val revealWidth =
            REVEAL_WIDTH_DP * binding.root.resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(binding.root.context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragOrigin = 0f
        private var dragging = false

        /** How far the card slides left; depends on how many buttons this row has behind it. */
        private var revealEndWidth = 0f

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: ServersCache, position: Int) {
            val context = binding.root.context
            val guid = item.guid
            val profile = item.profile

            binding.tvServerName.text = profile.remarks
            val protocol = getProtocolLabel(profile)
            binding.tvServerProtocol.text = protocol
            binding.tvServerProtocol.visibility = if (protocol.isEmpty()) View.GONE else View.VISIBLE
            binding.tvServerProtocol.setOnClickListener { showProtocolInfo(profile) }

            val address = getAddress(profile)
            binding.tvServerDetail.text = address
            binding.tvServerDetail.visibility = if (address.isEmpty()) View.GONE else View.VISIBLE

            // Formatted here rather than on the DTO, which has no Context and so could only ever
            // emit an untranslated "ms" — and "-1ms" for a failure, which reads as a measurement
            // rather than the absence of one.
            // While a measurement is pending the row says so itself, which is why the progress
            // toast that used to count them from on top of the list is gone.
            val isTesting = guid in testing
            bindTestingIndicator(isTesting)
            val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
            bindPing(guid, if (isTesting) 0L else delay)

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

            // A share link exists only for a profile the app itself can express as one. A raw
            // config, a balancer or a chain has no single line it could be turned into.
            val canCopyLink = !profile.configType.isComplexType()
            binding.btnCopyLink.visibility = if (canCopyLink) View.VISIBLE else View.GONE
            val density = binding.root.resources.displayMetrics.density
            revealEndWidth = ((if (canCopyLink) 3 else 2) * ACTION_WIDTH_DP + REVEAL_GAP_DP) * density

            closeReveal(animate = false)

            binding.layoutServerRow.setOnTouchListener { row, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        dragOrigin = binding.cardRoot.translationX
                        dragging = false
                        startHold(row, guid, profile, position)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                            // Committed to a horizontal drag: the hold-to-edit ring would otherwise
                            // keep filling underneath and fire in the middle of the gesture.
                            dragging = true
                            releaseHold()
                            // Hand the row a cancel rather than just clearing isPressed. Inside a
                            // scrolling container a View does not light up immediately — it posts
                            // the pressed state behind the tap timeout — so clearing a flag that
                            // is not set yet does nothing, and the pending callback then lights the
                            // row with no ACTION_UP ever coming to put it out. A cancel drops that
                            // callback as well as the state, which is why the grey used to linger
                            // until something else redrew the row.
                            cancelRowTouch(row, event)
                            row.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (dragging) {
                            binding.cardRoot.translationX = (dragOrigin + dx).coerceIn(-revealEndWidth, revealWidth)
                            showSides()
                        } else {
                            val strayed = event.x < 0 || event.y < 0 || event.x > row.width || event.y > row.height
                            if (strayed) releaseHold()
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        if (dragging) settleReveal() else releaseHold()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        // A canceled gesture (scroll interception or tab change) never opens
                        // actions. Only an actual release can settle a swipe open.
                        closeReveal(animate = false)
                        releaseHold()
                    }
                }
                // Consumed only while dragging, so the drag does not also land as a tap. Otherwise
                // the row keeps its ripple and its own click handling.
                dragging.also { if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) dragging = false }
            }

            binding.layoutServerRow.setOnClickListener {
                when {
                    holdTriggered -> holdTriggered = false
                    // A tap on an open row puts it away rather than switching server: the button is
                    // showing because the user asked for it, and the tap most likely means "never
                    // mind" — selecting a server behind a control they just opened would surprise.
                    binding.cardRoot.translationX != 0f -> closeReveal(animate = true)
                    else -> listener.onSelectServer(guid)
                }
            }

            binding.btnPingReveal.setOnClickListener {
                closeReveal(animate = true)
                listener.onPingServer(guid)
            }
            binding.btnCopyJson.setOnClickListener {
                closeReveal(animate = true)
                listener.onCopyJson(guid)
            }
            binding.btnCopyLink.setOnClickListener {
                closeReveal(animate = true)
                listener.onCopyLink(guid)
            }
            binding.btnDelete.setOnClickListener {
                closeReveal(animate = true)
                listener.onRemove(guid, position)
            }
            val isFavorite = FavoritesManager.isFavorite(profile)
            binding.btnFavorite.setIconResource(
                if (isFavorite) R.drawable.ic_star_filled_24dp else R.drawable.ic_star_outline_24dp
            )
            binding.btnFavorite.setOnClickListener {
                FavoritesManager.toggle(profile)
                listener.onToggleFavorite(profile)
            }
        }

        /**
         * Tell the row the touch is over, so it winds down its own pressed state and drops any
         * callback it had posted to start one.
         */
        private fun cancelRowTouch(row: View, event: MotionEvent) {
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            row.onTouchEvent(cancel)
            cancel.recycle()
        }

        /**
         * Show or hide the spinner without restarting it.
         *
         * The drawable is set once and left alone: this adapter rebinds the whole list on every
         * refresh, and re-setting an animated drawable would send it back to frame one each time —
         * which reads as a stutter rather than a spin.
         */
        private fun bindTestingIndicator(isTesting: Boolean) {
            val view = binding.loadingPing
            if (!isTesting) {
                (view.drawable as? Animatable)?.stop()
                view.visibility = View.GONE
                return
            }
            if (view.drawable == null) {
                // mutate(), or every row shares one animation state through the drawable cache:
                // the row that finishes first calls stop() and takes the rest down with it, which
                // is what made a run of measurements look like a series of jolts.
                view.setImageDrawable(
                    AppCompatResources.getDrawable(view.context, R.drawable.avd_ping_progress)?.mutate()
                )
            }
            view.visibility = View.VISIBLE
            (view.drawable as? Animatable)?.let { if (!it.isRunning) it.start() }
        }

        /**
         * The hold is timed by the clock, frame by frame, not by a ValueAnimator: with animations
         * turned off in the system, Android scales an animator's delay and duration to zero, so it
         * ended on the very press — edit opened before the list could tell a scroll was starting.
         */
        private fun startHold(row: View, guid: String, profile: ProfileItem, position: Int) {
            stopHoldTick()
            holdAnimator?.cancel()
            holdTriggered = false

            val ring = binding.viewHoldProgress
            ring.progress = 0f
            val pressedAt = SystemClock.uptimeMillis()
            var shown = false
            val tick = object : Runnable {
                override fun run() {
                    val elapsed = SystemClock.uptimeMillis() - pressedAt - HOLD_START_DELAY_MS
                    if (elapsed < 0) {
                        ring.postOnAnimation(this)
                        return
                    }
                    if (!shown) {
                        shown = true
                        // Only once the grace period is over. Dropping the selected-server
                        // outline any earlier would make a plain tap blink its own border off:
                        // it only needs to go while the ring is actually drawn, since the two
                        // sit a hairline apart and otherwise read as a doubled border.
                        binding.cardRoot.strokeWidth = 0
                    }
                    val progress = (elapsed.toFloat() / HOLD_DURATION_MS).coerceAtMost(1f)
                    ring.progress = progress
                    if (progress < 1f) {
                        ring.postOnAnimation(this)
                        return
                    }
                    holdTick = null
                    holdTriggered = true
                    ring.progress = 0f
                    binding.cardRoot.strokeWidth = restingStrokeWidth
                    row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    listener.onEdit(guid, position, profile)
                }
            }
            holdTick = tick
            ring.postOnAnimation(tick)
        }

        private fun stopHoldTick() {
            holdTick?.let { binding.viewHoldProgress.removeCallbacks(it) }
            holdTick = null
        }

        /** Let the ring retract instead of snapping away when the hold is abandoned. */
        private fun releaseHold() {
            stopHoldTick()
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

        /** Let go mid-drag: either the buttons on that side stay out or the card goes back. */
        private fun settleReveal() {
            val x = binding.cardRoot.translationX
            when {
                x > revealWidth * REVEAL_SETTLE_FRACTION -> openReveal(revealWidth)
                x < -revealEndWidth * REVEAL_SETTLE_FRACTION -> openReveal(-revealEndWidth)
                else -> closeReveal(animate = true)
            }
        }

        /** @param target where the card comes to rest: positive uncovers its start, negative its end. */
        private fun openReveal(target: Float) {
            openHolder?.takeIf { it !== this }?.closeReveal(animate = true)
            openHolder = this
            showSides()
            binding.cardRoot.animate().translationX(target).setDuration(REVEAL_ANIM_MS).start()
        }

        fun closeReveal(animate: Boolean) {
            if (openHolder === this) openHolder = null
            if (animate) {
                binding.cardRoot.animate().translationX(0f).setDuration(REVEAL_ANIM_MS)
                    .withEndAction { hideSides() }.start()
            } else {
                binding.cardRoot.animate().cancel()
                binding.cardRoot.translationX = 0f
                hideSides()
            }
        }

        /**
         * The buttons exist only while the card is off centre. Left in place behind a closed card
         * they show through at its corners: two rounded edges of the same radius, drawn on top of
         * each other, still leave a coloured hairline where their anti-aliasing overlaps.
         */
        private fun showSides() {
            binding.btnPingReveal.visibility = View.VISIBLE
            binding.actionsReveal.visibility = View.VISIBLE
        }

        private fun hideSides() {
            if (binding.cardRoot.translationX != 0f) return
            binding.btnPingReveal.visibility = View.INVISIBLE
            binding.actionsReveal.visibility = View.INVISIBLE
        }

        fun resetTransientUi() {
            dragging = false
            resetHold()
            closeReveal(animate = false)
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            binding.layoutServerRow.onTouchEvent(cancel)
            cancel.recycle()
        }

        private fun resetHold() {
            stopHoldTick()
            holdAnimator?.cancel()
            holdAnimator = null
            holdTriggered = false
            binding.viewHoldProgress.progress = 0f
            binding.cardRoot.strokeWidth = restingStrokeWidth
        }

        /**
         * Address line. Empty for a balancer, which has no single address worth showing, and empty
         * when the user has turned addresses off — the protocol chip beside it stays either way.
         */
        private fun getAddress(profile: ProfileItem): String {
            if (isBalancer(profile)) return ""
            if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_SERVER_ADDRESS_HOME)) return ""
            return profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile)
        }

        /**
         * The chip's label: the protocol alone, never "VLESS / ws / reality".
         *
         * The transport and encryption used to be appended, which made rows disagree for no reason
         * the user could see — a subscription entry is a raw config that only ever knew its
         * protocol, so it read "VLESS" where an identical local server read "VLESS / reality".
         * That detail moved into the dialog behind the chip rather than being dropped.
         */
        private fun getProtocolLabel(profile: ProfileItem): String {
            if (isBalancer(profile)) return ""
            // The resolved type, not profile.configType: a subscription entry is stored as CUSTOM
            // and carries its real protocol in customProtocol, so reading configType here labelled
            // most of the list "CUSTOM" while the dialog behind it correctly described VLESS.
            return resolveProtocolType(profile)?.name
                ?: profile.customProtocol?.uppercase()
                ?: profile.configType.name
        }

        /**
         * The dialog's title: the protocol's full name, properly cased.
         */
        private fun getProtocolTitle(context: Context, profile: ProfileItem): String {
            return when (val type = resolveProtocolType(profile)) {
                EConfigType.VMESS -> "VMess"
                EConfigType.TROJAN -> "Trojan"
                EConfigType.SHADOWSOCKS -> "Shadowsocks"
                EConfigType.WIREGUARD -> "WireGuard"
                EConfigType.HYSTERIA2 -> "Hysteria2"
                EConfigType.HYSTERIA -> "Hysteria"
                EConfigType.POLICYGROUP -> context.getString(R.string.shrimp_protocol_name_policygroup)
                EConfigType.PROXYCHAIN -> context.getString(R.string.shrimp_protocol_name_proxychain)
                null -> profile.customProtocol.orEmpty().ifEmpty { profile.configType.name }
                else -> type.name
            }
        }

        /**
         * A raw CUSTOM profile stores its protocol as free text, so it is matched back to the enum
         * by name — that way a subscription's VLESS entry is labelled and explained exactly like a
         * locally added one. Null means the text matched nothing known.
         */
        private fun resolveProtocolType(profile: ProfileItem): EConfigType? {
            if (profile.configType != EConfigType.CUSTOM) return profile.configType
            return EConfigType.entries.firstOrNull { it.name.equals(profile.customProtocol, true) }
        }

        /** A balancer is named by its remarks alone — it has no one protocol or address. */
        private fun isBalancer(profile: ProfileItem) = (profile.balancerMemberCount ?: 0) >= 2

        /**
         * Explain the protocol behind the chip, plus the transport details the row no longer
         * spells out. Protocol names are jargon, and this is the one place a user meets them.
         */
        private fun showProtocolInfo(profile: ProfileItem) {
            val context = binding.root.context
            val details = buildList {
                profile.network?.takeIf { it.isNotBlank() }
                    ?.let { add(context.getString(R.string.shrimp_protocol_detail_transport, it)) }
                profile.security?.takeIf { it.isNotBlank() }
                    ?.let { add(context.getString(R.string.shrimp_protocol_detail_security, it)) }
            }
            val body = context.getString(protocolDescriptionRes(profile)) +
                    if (details.isEmpty()) "" else "\n\n" + details.joinToString("\n")

            MaterialAlertDialogBuilder(context)
                .setTitle(getProtocolTitle(context, profile))
                .setMessage(body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        private fun protocolDescriptionRes(profile: ProfileItem): Int {
            return when (resolveProtocolType(profile)) {
                EConfigType.VLESS -> R.string.shrimp_protocol_desc_vless
                EConfigType.VMESS -> R.string.shrimp_protocol_desc_vmess
                EConfigType.TROJAN -> R.string.shrimp_protocol_desc_trojan
                EConfigType.SHADOWSOCKS -> R.string.shrimp_protocol_desc_shadowsocks
                EConfigType.SOCKS -> R.string.shrimp_protocol_desc_socks
                EConfigType.HTTP -> R.string.shrimp_protocol_desc_http
                EConfigType.WIREGUARD -> R.string.shrimp_protocol_desc_wireguard
                EConfigType.HYSTERIA2 -> R.string.shrimp_protocol_desc_hysteria2
                EConfigType.HYSTERIA -> R.string.shrimp_protocol_desc_hysteria
                EConfigType.POLICYGROUP -> R.string.shrimp_protocol_desc_policygroup
                EConfigType.PROXYCHAIN -> R.string.shrimp_protocol_desc_proxychain
                else -> R.string.shrimp_protocol_desc_unknown
            }
        }
    }
}
