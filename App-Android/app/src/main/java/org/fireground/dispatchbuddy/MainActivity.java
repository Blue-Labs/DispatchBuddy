package org.fireground.dispatchbuddy;

import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.support.v7.widget.Toolbar;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
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
    final private String TAG = "MAIN";
    private static final int ERROR_DIALOG_REQUEST=9001;

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

        Log.i(TAG, "checking needed permissions");
        if (isServicesOK()) {
            startDispatchBuddy();
        }
    }

    public void startDispatchBuddy() {
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
        user = mAuth.getCurrentUser();

        startActivity(new Intent(this, LoginActivity.class));
        user = mAuth.getCurrentUser();
        if (user!=null) {
//            Log.w(TAG, "logged in user: "+user.getEmail());
        } else {
//            Log.w(TAG, "no user involved yet");
            return;
        }
    }

    private boolean hasPermissions(Context context, String... permissions) {
        if (context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                    Toast.makeText(this, permission+" permission previously denied", Toast.LENGTH_SHORT).show();
                }
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    private Boolean isServicesOK(){
        Boolean notificationPermission=false;
        Boolean otherPerms=false;

        int available = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(MainActivity.this);
        if (available == ConnectionResult.SUCCESS) {
//            Log.w(TAG, "Google Play Services is ok");
        } else if (GoogleApiAvailability.getInstance().isUserResolvableError(available)) {
//            Log.w(TAG, "GPS, error occurred but we can fix it");
            Dialog dialog = GoogleApiAvailability.getInstance().getErrorDialog(MainActivity.this, available, ERROR_DIALOG_REQUEST);
            dialog.show();
        } else {
            Toast.makeText(this, "Google API services not available, parts of DispatchBuddy won't work for you", Toast.LENGTH_SHORT).show();
        }

        int PERMISSION_REQUEST_ID = 10;
        /*Manifest.permission.WRITE_EXTERNAL_STORAGE*/
        String[] PERMISSIONS = {
                Manifest.permission.WRITE_EXTERNAL_STORAGE
                , Manifest.permission.ACCESS_FINE_LOCATION
                , Manifest.permission.ACCESS_COARSE_LOCATION
        };

        if(!hasPermissions(this, PERMISSIONS)){
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_REQUEST_ID);
        } else {
            otherPerms=true;
        }

        // special permission case
        try {
            NotificationManager n = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (n.isNotificationPolicyAccessGranted()) {
                notificationPermission = true;
                AudioManager audioManager = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
                audioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            } else {
                // Ask the user to grant access
                Intent intent = new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                startActivityForResult(intent, 99);
            }

            if (!notificationPermission || !otherPerms) {
                return false;
            }
        } catch (NoSuchMethodError e) {
            Log.w(TAG, "can't use isNotificationPolicyAccessGranted on this platform");
        }

        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        Log.i(TAG, "oAR activityRequest:"+requestCode);
        Log.i(TAG, "oAR activityResult:"+resultCode);
        Log.i(TAG, "oAR activityData:"+data);

        if (requestCode == 1 && data != null)
        {
            Log.v("TAG", data.getStringExtra("Note"));
            if(resultCode == RESULT_OK)
            {
//                listItems.add(data.getStringExtra("Note"));
//                Log.v("TAG", data.getStringExtra("Note"));
//                adapter.notifyDataSetChanged();
//                listView.invalidateViews();
                Log.i(TAG, "oAR RESULT OK");
            }
            if (resultCode == RESULT_CANCELED)
            {
                Log.i(TAG,"oAR RESULT CANCELED");

            }
        } else {
            Log.i(TAG,"oAR other...");
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        Log.w(TAG, "request code:"+requestCode);
        Log.w(TAG, "request perms:"+permissions[0].toString());
        Log.w(TAG, "request granted:"+grantResults[0]);

        switch (requestCode) {
//            case MY_PERMISSIONS_REQUEST_READ_CONTACTS: {
//                // If request is cancelled, the result arrays are empty.
//                if (grantResults.length > 0
//                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//
//                    // permission was granted, yay! Do the
//                    // contacts-related task you need to do.
//
//                } else {
//
//                    // permission denied, boo! Disable the
//                    // functionality that depends on this permission.
//                }
//                return;
//            }

            case 10: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "oAR permission granted");
                    // awesome
                    finish();
                    startActivity(new Intent(this, MainActivity.class));
                } else {
                    Toast.makeText(this, "Without priority notification access, DispatchBuddy cannot override Do Not Disturb mode", Toast.LENGTH_LONG).show();
                }
            }

            // other 'case' lines to check for other
            // permissions this app might request.
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
//                Log.w(TAG, "fb user is "+user.getEmail());
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
//                Log.e(TAG, "wtf mate, unknown menu item");
        }
        return super.onOptionsItemSelected(item);
    }
}
