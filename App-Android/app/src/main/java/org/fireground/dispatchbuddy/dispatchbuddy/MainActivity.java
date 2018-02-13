package org.fireground.dispatchbuddy.dispatchbuddy;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.support.v7.widget.Toolbar;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;

/**
 * Created by david on 2/9/18.
 *
 * TODO: ask user for priority permissions to emit alert sounds even when DND is on
 * TODO: make dispatch noise for notification alert
 * TODO: make icons for notifications
 * TODO: make a general DispatchBuddy icon
 * TODO: on authentication, did the DB app just go away?
 * TODO: hitting the return arrow from the Dispatches activity takes us back to a blank main activity
 */

public class MainActivity extends AppCompatActivity {
    private FirebaseAuth mAuth;
    public FirebaseUser user = null;

    // track Firebase here so we don't try to init it twice
    private static FirebaseDatabase fbDatabase;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.app_bar);
        setSupportActionBar(toolbar);

        // wtf Firebase, local cache is readable even without authentication??
        if (fbDatabase == null) {
            fbDatabase = FirebaseDatabase.getInstance();
            fbDatabase.setPersistenceEnabled(false);
            fbDatabase.getReference("dispatches").keepSynced(false);
        }

        Button doLogin = (Button) findViewById(R.id.doLogin);
        doLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, LoginActivity.class));
            }
        });

        Button doDispatches = (Button) findViewById(R.id.doDispatches);
        doDispatches.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, DispatchesActivity.class));
            }
        });

        mAuth = FirebaseAuth.getInstance();
        Log.w("fucknut", "pre-login user: "+mAuth.getCurrentUser());
        startActivity(new Intent(this, LoginActivity.class));
        user = mAuth.getCurrentUser();
        if (user!=null) {
            Log.w("fucknut", "nutfucking delicious:> "+user.getEmail());
            startActivity(new Intent(this, DispatchesActivity.class));
        } else {
            finish();
            Log.w("fucknut", "no user involved yet");
            return;
        }


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
                //Intent intent = new Intent(this, xSettingsActivity.class);
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
