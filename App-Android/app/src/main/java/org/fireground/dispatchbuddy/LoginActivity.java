package org.fireground.dispatchbuddy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;

import java.lang.reflect.Method;

/*
 * Created by david on 2/9/18.
 */

public class LoginActivity extends Activity {
    private static final String TAG = "LoginActivity";
    private EditText mEmailField;
    private EditText mPasswordField;
    public DispatchBuddyBase DBB;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.login);

        DBB = DispatchBuddyBase.getInstance();

        // View elements
        mEmailField = findViewById(R.id.firegroundUsername);
        mPasswordField = findViewById(R.id.firegroundPassword);

        // Buttons
        final Button loginBtn = findViewById(R.id.loginBtn);
        loginBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int which = v.getId();
                ((InputMethodManager) getSystemService(Activity.INPUT_METHOD_SERVICE))
                        .toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
                if (which == R.id.loginBtn) {
                    signIn(mEmailField.getText().toString(), mPasswordField.getText().toString());
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Check if user is signed in (non-null) and update UI accordingly.
        updateUI(DBB.getUser());
    }

    private void signIn(String email, String password) {
        // when called from onClick(), we don't have access to parent fields so FBA is passed to us
        if (!validateForm()) {
            return;
        }

        // i don't know how to push the updateUI callback into this anonymous task
        // so this has to stay here for now
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(new OnCompleteListener<AuthResult>() {
                    String user;
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            user = DBB.getUser();
                            Log.e(TAG, "auth success");
                            DBB.pushFirebaseClientRegistrationData(DBB.getRegToken());
                        } else {
                            user = null;
                            Log.e(TAG, "auth failed");
                            Toast.makeText(LoginActivity.this, "Authentication failed.",
                                    Toast.LENGTH_LONG).show();
                        }
                        updateUI(user);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        if (e instanceof FirebaseAuthException) {
                            //((FirebaseAuthException) e).getErrorCode());
                            Toast.makeText(LoginActivity.this, e.getLocalizedMessage(),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(LoginActivity.this, e.getLocalizedMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    private void updateUI(String user) {
        Log.i(TAG, "updating UI with user: "+user);
        if (user != null) {
            Log.i(TAG, "clearing fields, starting Dispatches Activity");
            mEmailField.setText(null);
            mPasswordField.setText(null);
            finish();
            startActivity(new Intent(LoginActivity.this, DispatchesActivity.class));
        }
    }

    private boolean validateForm() {
        boolean valid = true;

        String email = mEmailField.getText().toString();
        if (TextUtils.isEmpty(email)) {
            mEmailField.setError("Required.");
            valid = false;
        } else {
            mEmailField.setError(null);
        }

        String password = mPasswordField.getText().toString();
        if (TextUtils.isEmpty(password)) {
            mPasswordField.setError("Required.");
            valid = false;
        } else {
            mPasswordField.setError(null);
        }

        return valid;
    }
}
