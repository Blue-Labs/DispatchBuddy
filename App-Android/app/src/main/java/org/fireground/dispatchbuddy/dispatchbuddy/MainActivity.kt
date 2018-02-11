/*
package org.fireground.dispatchbuddy.dispatchbuddy

import android.app.Activity
import android.app.ProgressDialog
import android.os.Bundle
import android.util.Log
import android.view.View;
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlinx.android.synthetic.main.login.*
import kotlinx.android.synthetic.main.activity_main.*
import org.fireground.dispatchbuddy.dispatchbuddy.R.layout.login
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

class MainActivity : Activity() {

    private var mAuth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.login)

        initialise()
        }

    /*fun firegroundLogin(view: View) {
        val username = firegroundUsername.text.toString()
        val password = firegroundPassword.text.toString()
        Log.i("data:", username +'/'+ password)
        if (currentUser != null) {
            Log.i("fuck", "user exists")
            setContentView(R.layout.activity_main)
        } else {
            Log.w("fuck", "user does not exist")
        }
    }

    private fun initialise() {
        val fUsername = findViewById<View>(R.id.firegroundUsername) as EditText
        val fPassword = findViewById<View>(R.id.firegroundPassword) as EditText
        mAuth = FirebaseAuth.getInstance()
    }

    override fun onStart() {
        super.onStart()
        val currentUser = mAuth.getCurrentUser()
        if (currentUser != null) {
            Log.i("fuck", "user exists")
            setContentView(R.layout.activity_main)
        } else {
            Log.w("fuck", "user does not exist")
        }
    }
}
*/