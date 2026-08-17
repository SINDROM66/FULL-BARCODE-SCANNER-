package com.ugid.scanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

import android.widget.Button

class RecordAdapter(private var records: List<ScannedRecord>, private val onDeleteClick: (ScannedRecord) -> Unit) :
    RecyclerView.Adapter<RecordAdapter.RecordViewHolder>() {

    class RecordViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_record_name)
        val tvNin: TextView = itemView.findViewById(R.id.tv_record_nin)
        val tvPhone: TextView = itemView.findViewById(R.id.tv_record_phone)
        val btnDelete: Button = itemView.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false)
        return RecordViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        val record = records[position]
        holder.tvName.text = record.response.full_name
        holder.tvNin.text = "NIN: ${record.response.nin}"
        holder.tvPhone.text = "Phone: ${record.phoneNumber}"
        holder.btnDelete.setOnClickListener {
            onDeleteClick(record)
        }
    }

    override fun getItemCount() = records.size

    fun updateRecords(newRecords: List<ScannedRecord>) {
        records = newRecords
        notifyDataSetChanged()
    }
}
