package com.example.attendence

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.attendence.databinding.ActivityMainBinding
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        auth = Firebase.auth
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        goToRegistration()

        binding.mainActivityLogout.setOnClickListener {
            Firebase.auth.signOut()
            val intent = Intent(this, SignUpActivity::class.java)
            startActivity(intent)
            finish()
        }

        binding.verify.setOnClickListener {
            val intent = Intent(this, Scanner::class.java)
            intent.putExtra("verify", true)
//            val intent = Intent(this, RegisterSubjectsActivity::class.java)
            startActivity(intent)
//            finish()
        }
    }

    private fun goToRegistration() {
        val database = FirebaseDatabase.getInstance()
        val currentUser = FirebaseAuth.getInstance().currentUser

        if (currentUser != null) {
            val userId = currentUser.uid
            val registeredSubjectsRef = database.getReference("students").child(userId).child("registeredSubjects")
            val registrationFlagRef = database.getReference("isRegistrationUnderProcess")

            // Step 1: Check if registeredSubjects is empty
            registeredSubjectsRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.hasChildren()) {
                        // No subjects registered → stop here
                        Toast.makeText(this@MainActivity, "Subjects Registered", Toast.LENGTH_SHORT).show()
                        return
                    }

                    // Step 2: If not empty, check isRegistrationUnderProcess flag
                    registrationFlagRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(flagSnapshot: DataSnapshot) {
                            val isRegistrationUnderProcess = flagSnapshot.getValue(Boolean::class.java) ?: false
                            if (isRegistrationUnderProcess) {
                                val intent = Intent(this@MainActivity, RegisterSubjectsActivity::class.java)
                                startActivity(intent)
                                finish()
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Toast.makeText(this@MainActivity, "Error fetching registration status: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                    })
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@MainActivity, "Error fetching subjects: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
        } else {
            Toast.makeText(this, "Error: User not logged in", Toast.LENGTH_SHORT).show()
        }
    }
}