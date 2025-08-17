package com.example.attendence

import android.R
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.attendence.databinding.ActivityRegisterSubjectsBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class RegisterSubjectsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterSubjectsBinding
    private lateinit var adapter: SubjectAdapter
    private val subjectList = mutableListOf<Subject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityRegisterSubjectsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupRecyclerView()
        setupSemesterSpinner()
        setupSubmitButton()
    }

    private fun setupRecyclerView() {
        adapter = SubjectAdapter(subjectList)
        binding.recyclerViewSubjects.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewSubjects.adapter = adapter

        // Show submit button only when scrolled to bottom
        binding.recyclerViewSubjects.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                if (layoutManager.findLastCompletelyVisibleItemPosition() == subjectList.size - 1) {
                    binding.btnSubmit.visibility = View.VISIBLE
                }
            }
        })

        binding.btnSubmit.visibility = View.GONE
    }

    private fun setupSemesterSpinner() {
        val semesters = listOf("Select Semester", "sem1", "sem2", "sem3", "sem4", "sem5", "sem6", "sem7", "sem8")
        binding.spinnerSemester.adapter =
            ArrayAdapter(this, R.layout.simple_spinner_dropdown_item, semesters)

        binding.spinnerSemester.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position != 0) {
                    loadSubjectsFromFirebase(semesters[position])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupSubmitButton() {
        binding.btnSubmit.setOnClickListener {
            val selectedSubjects = adapter.getSelectedSubjects()
            val studentId = FirebaseAuth.getInstance().currentUser?.uid ?: return@setOnClickListener

            FirebaseDatabase.getInstance().getReference("students/$studentId/registeredSubjects")
                .setValue(selectedSubjects)
                .addOnSuccessListener {
                    Toast.makeText(this, "Subjects Registered!", Toast.LENGTH_SHORT).show()
                    finish()
                }
        }
    }

    private fun loadSubjectsFromFirebase(semester: String) {
        val dbRef = FirebaseDatabase.getInstance().getReference("cse_subjects/$semester")
        dbRef.get().addOnSuccessListener { snapshot ->
            subjectList.clear()
            snapshot.children.forEach { subjectSnap ->
                subjectSnap.getValue(String::class.java)?.let {
                    subjectList.add(Subject(it))
                }
            }
            adapter.notifyDataSetChanged()
            binding.btnSubmit.visibility = View.GONE
        }
    }
}
