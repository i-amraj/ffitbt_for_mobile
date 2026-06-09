package com.fireflisinfotech.ffitbt

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.util.Calendar
import java.util.Locale

class PrintLogAdapter(
    private var logs: List<PrintLog>,
    private val onCancelClick: (PrintLog) -> Unit
) : RecyclerView.Adapter<PrintLogAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logStatusDot: View = view.findViewById(R.id.logStatusDot)
        val tvLogTitle: TextView = view.findViewById(R.id.tvLogTitle)
        val tvLogDetail: TextView = view.findViewById(R.id.tvLogDetail)
        val tvLogTime: TextView = view.findViewById(R.id.tvLogTime)
        val tvLogStatusText: TextView = view.findViewById(R.id.tvLogStatusText)
        val btnCancelLogJob: MaterialButton = view.findViewById(R.id.btnCancelLogJob)
        val tvLogErrorMsg: TextView = view.findViewById(R.id.tvLogErrorMsg)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_print_log, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val log = logs[position]
        holder.tvLogTitle.text = log.title
        
        val typeUpper = log.printerType.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        holder.tvLogDetail.text = "$typeUpper (${log.printerTarget})"
        
        // Format timestamp
        val cal = Calendar.getInstance(Locale.ENGLISH).apply {
            timeInMillis = log.timestamp
        }
        val timeStr = DateFormat.format("hh:mm a", cal).toString()
        val dateStr = DateFormat.format("dd MMM", cal).toString()
        holder.tvLogTime.text = "$dateStr, $timeStr"

        // Status styling
        holder.tvLogStatusText.text = log.status.uppercase(Locale.getDefault())
        
        when (log.status.lowercase(Locale.getDefault())) {
            "queued" -> {
                holder.logStatusDot.setBackgroundResource(R.drawable.dot_amber)
                holder.tvLogStatusText.setTextColor(0xFFF59E0B.toInt())
                holder.btnCancelLogJob.visibility = View.VISIBLE
                holder.btnCancelLogJob.setOnClickListener { onCancelClick(log) }
                holder.tvLogErrorMsg.visibility = View.GONE
            }
            "printing" -> {
                holder.logStatusDot.setBackgroundResource(R.drawable.dot_blue)
                holder.tvLogStatusText.setTextColor(0xFF38BDF8.toInt())
                holder.btnCancelLogJob.visibility = View.GONE
                holder.tvLogErrorMsg.visibility = View.GONE
            }
            "completed" -> {
                holder.logStatusDot.setBackgroundResource(R.drawable.dot_green)
                holder.tvLogStatusText.setTextColor(0xFF22C55E.toInt())
                holder.btnCancelLogJob.visibility = View.GONE
                holder.tvLogErrorMsg.visibility = View.GONE
            }
            "failed" -> {
                holder.logStatusDot.setBackgroundResource(R.drawable.dot_red)
                holder.tvLogStatusText.setTextColor(0xFFEF4444.toInt())
                holder.btnCancelLogJob.visibility = View.GONE
                if (!log.errorMsg.isNullOrEmpty()) {
                    holder.tvLogErrorMsg.text = log.errorMsg
                    holder.tvLogErrorMsg.visibility = View.VISIBLE
                } else {
                    holder.tvLogErrorMsg.visibility = View.GONE
                }
            }
            "cancelled" -> {
                holder.logStatusDot.setBackgroundResource(R.drawable.dot_grey)
                holder.tvLogStatusText.setTextColor(0xFF64748B.toInt())
                holder.btnCancelLogJob.visibility = View.GONE
                holder.tvLogErrorMsg.visibility = View.GONE
            }
            else -> {
                holder.logStatusDot.setBackgroundResource(R.drawable.dot_grey)
                holder.tvLogStatusText.setTextColor(0xFF64748B.toInt())
                holder.btnCancelLogJob.visibility = View.GONE
                holder.tvLogErrorMsg.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int = logs.size

    fun updateData(newLogs: List<PrintLog>) {
        logs = newLogs
        notifyDataSetChanged()
    }
}
