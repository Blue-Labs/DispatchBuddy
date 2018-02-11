package org.fireground.dispatchbuddy.dispatchbuddy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.support.v7.widget.Toolbar;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * Created by david on 2/9/18.
 */

public class MainActivity extends AppCompatActivity {
    private FirebaseAuth mAuth;
    public FirebaseUser user = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.app_bar);
        setSupportActionBar(toolbar);

        mAuth = FirebaseAuth.getInstance();
        startActivity(new Intent(this, LoginActivity.class));
        user = mAuth.getCurrentUser();
        if (user!=null) {
            Log.w("fucknut", "nutfucking delicious:> "+user.getEmail());
        }

        // force refresh
        FirebaseDatabase database = FirebaseDatabase.getInstance();

        //bubs = database.getReference("").child("dispatches").limitToLast(10);

        database.getReference("dispatches").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                // This method is called once with the initial value and again
                // whenever data at this location is updated.
                for (DataSnapshot foo: dataSnapshot.getChildren()) {
                    Log.w("wtf", "child foo: " + foo.getKey());
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                // Failed to read value
                Log.w("wtf", "Failed to read value.", error.toException());
            }
        });


    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.appLogout:
                user = mAuth.getCurrentUser();
                Log.w("wtf", "fb user is "+user.getEmail());
                mAuth.signOut();
                startActivity(new Intent(this, LoginActivity.class));
                break;
            case R.id.appSettings:
                //Intent intent = new Intent(this, SettingsActivity.class);
                //startActivity(intent);
                //break;
            case R.id.appSearch:
                //Intent intent = new Intent(this, appSearch.class);
                //startActivity(intent);
                //break;
            case R.id.appCheckUpdates:
                //Intent intent = new Intent(this, appCheckUpdates.class);
                //startActivity(intent);
                //break;
            case R.id.appFeedback:
                //Intent intent = new Intent(this, appFeedback.class);
                //startActivity(intent);
                //break;
            case R.id.changePassword:
                //Intent intent = new Intent(this, appChangePassword.class);
                //startActivity(intent);
                //break;
            default:
                Toast.makeText(this, "not implemented yet",
                        Toast.LENGTH_SHORT).show();
                Log.e("fuck", "wtf mate, unknown menu item");
        }
        return super.onOptionsItemSelected(item);
    }
}
