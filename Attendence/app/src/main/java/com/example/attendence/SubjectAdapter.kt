package com.example.attendence

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SubjectAdapter(
    private val subjectList: MutableList<Subject>
) : RecyclerView.Adapter<SubjectAdapter.SubjectViewHolder>() {

    inner class SubjectViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textName: TextView = itemView.findViewById(R.id.textSubjectName)
        val btnChoose: Button = itemView.findViewById(R.id.btnChoose)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubjectViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_subject, parent, false)
        return SubjectViewHolder(view)
    }

    override fun onBindViewHolder(holder: SubjectViewHolder, position: Int) {
        val subject = subjectList[position]
        holder.textName.text = subject.name

        holder.btnChoose.text = if (subject.isSelected) "Chosen" else "Choose"
        holder.btnChoose.setBackgroundTintList(
            holder.itemView.context.getColorStateList(
                if (subject.isSelected) R.color.green else R.color.blue
            )
        )

        holder.btnChoose.setOnClickListener {
            subject.isSelected = !subject.isSelected
            notifyItemChanged(position)
        }
    }

    override fun getItemCount(): Int = subjectList.size

    fun getSelectedSubjects(): List<String> {
        return subjectList.filter { it.isSelected }.map { it.name }
    }
}
