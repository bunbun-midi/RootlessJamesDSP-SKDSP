package me.timschneeberger.rootlessjamesdsp.preference

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ItemEffectOrderBinding
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.model.EffectChain
import me.timschneeberger.rootlessjamesdsp.model.EffectChain.Effect
import java.util.Collections

/**
 * Stores the effect processing order as a comma-separated list of effect IDs and lets the user
 * reorder it with drag & drop.
 */
class EffectOrderPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
    defStyleRes: Int = 0,
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    // Note: not named "order"; Preference already has an order property (position in the screen)
    private var effectOrder: List<Effect> = EffectChain.DEFAULT_ORDER

    // Strong references are required; SharedPreferences only keeps weak references to listeners
    private val enableListeners = mutableListOf<Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()

    init {
        layoutResource = R.layout.preference_icon
        isPersistent = true
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getString(index) ?: EffectChain.DEFAULT_ORDER_STRING
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        effectOrder = EffectChain.parse(getPersistedString(defaultValue as? String ?: EffectChain.DEFAULT_ORDER_STRING))
        updateSummary()
    }

    override fun onAttached() {
        super.onAttached()
        // Keep the summary (= active effects) up to date when effects are toggled in other cards
        Effect.entries.flatMap { it.namespaces }.distinct().forEach { namespace ->
            val prefs = PreferenceCache.getPreferences(context, namespace)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateSummary() }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            enableListeners.add(prefs to listener)
        }
    }

    override fun onDetached() {
        enableListeners.forEach { (prefs, listener) -> prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        enableListeners.clear()
        super.onDetached()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(android.R.id.summary) as? TextView)?.apply {
            isSingleLine = false
            maxLines = 4
        }
    }

    override fun onClick() {
        showDialog()
    }

    private fun applyEffectOrder(newOrder: List<Effect>) {
        val serialized = EffectChain.serialize(newOrder)
        if (!callChangeListener(serialized))
            return
        effectOrder = newOrder
        persistString(serialized)
        updateSummary()
    }

    private fun updateSummary() {
        val active = effectOrder.filter { it.isEnabled(context) }
        summary = if (active.isEmpty())
            context.getString(R.string.effect_order_summary_none)
        else
            active.joinToString("  →  ") { context.getString(it.title) }
    }

    private fun showDialog() {
        val working = effectOrder.toMutableList()
        val enabled = Effect.entries.associateWith { it.isEnabled(context) }

        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            val padding = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, padding, 0, 0)
            clipToPadding = false
        }

        lateinit var touchHelper: ItemTouchHelper
        val adapter = Adapter(working, enabled) { touchHelper.startDrag(it) }

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                adapter.refreshPositions()
            }
        })
        touchHelper.attachToRecyclerView(recyclerView)
        recyclerView.adapter = adapter

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.effect_order_title)
            .setView(recyclerView)
            .setPositiveButton(android.R.string.ok) { _, _ -> applyEffectOrder(working.toList()) }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.effect_order_reset) { _, _ -> applyEffectOrder(EffectChain.DEFAULT_ORDER) }
            .show()
    }

    private class Adapter(
        private val items: MutableList<Effect>,
        private val enabled: Map<Effect, Boolean>,
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {

        class ViewHolder(val binding: ItemEffectOrderBinding) : RecyclerView.ViewHolder(binding.root)

        fun move(from: Int, to: Int) {
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to)
                return
            Collections.swap(items, from, to)
            notifyItemMoved(from, to)
        }

        fun refreshPositions() = notifyItemRangeChanged(0, items.size)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemEffectOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val ctx = holder.itemView.context
            val effect = items[position]
            val isOn = enabled[effect] == true
            val binding = holder.binding

            binding.positionLabel.text = (position + 1).toString()
            binding.title.setText(effect.title)
            binding.title.alpha = if (isOn) 1f else 0.6f
            binding.state.setText(if (isOn) R.string.effect_order_state_on else R.string.effect_order_state_off)
            binding.state.setTextColor(MaterialColors.getColor(binding.state,
                if (isOn) androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurfaceVariant))

            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN)
                    onStartDrag(holder)
                false
            }

            // Drag & drop is not usable with screen readers; offer explicit move actions instead.
            // Remove the actions of the previous binding first, they would accumulate otherwise.
            val root = binding.root
            (root.tag as? IntArray)?.forEach { ViewCompat.removeAccessibilityAction(root, it) }
            val actionIds = mutableListOf<Int>()
            if (position > 0) {
                actionIds += ViewCompat.addAccessibilityAction(root, ctx.getString(R.string.effect_order_move_up)) { _, _ ->
                    val pos = holder.bindingAdapterPosition
                    move(pos, pos - 1)
                    refreshPositions()
                    true
                }
            }
            if (position < items.size - 1) {
                actionIds += ViewCompat.addAccessibilityAction(root, ctx.getString(R.string.effect_order_move_down)) { _, _ ->
                    val pos = holder.bindingAdapterPosition
                    move(pos, pos + 1)
                    refreshPositions()
                    true
                }
            }
            root.tag = actionIds.toIntArray()
        }
    }
}
