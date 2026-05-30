package com.fireflisinfotech.ffitbt

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Shows all paired BT devices.
 * Printers are fully clickable; non-printers (AirBuds, phones, etc.) are
 * shown greyed-out with a "Not a printer" tag so users don't get confused.
 */
class PrinterAdapter(
    private val items: List<Triple<String, String, Boolean>>,  // (name, mac, isPrinter)
    private val onClick: (Triple<String, String, Boolean>) -> Unit
) : RecyclerView.Adapter<PrinterAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvPrinterName)
        val tvMac: TextView  = view.findViewById(R.id.tvPrinterMac)
        val tvTag: TextView  = view.findViewById(R.id.tvPrinterTag)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_printer, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val (name, mac, isPrinter) = item

        holder.tvName.text = name
        holder.tvMac.text  = mac

        if (isPrinter) {
            // Fully clickable printer
            holder.tvTag.text = "🖨️ Printer"
            holder.tvTag.setTextColor(0xFF38BDF8.toInt())
            holder.tvName.alpha = 1f
            holder.tvMac.alpha  = 1f
            holder.itemView.alpha = 1f
            holder.itemView.isEnabled = true
            holder.itemView.setOnClickListener { onClick(item) }
        } else {
            // Non-printer device — show greyed, still tappable with warning
            holder.tvTag.text = "Not a printer"
            holder.tvTag.setTextColor(0xFFEF4444.toInt())
            holder.tvName.alpha = 0.5f
            holder.tvMac.alpha  = 0.5f
            holder.itemView.alpha = 0.7f
            holder.itemView.isEnabled = true
            // Allow selecting but warn
            holder.itemView.setOnClickListener { onClick(item) }
        }
    }

    override fun getItemCount() = items.size
}
