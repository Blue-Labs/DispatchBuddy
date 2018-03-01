package org.fireground.dispatchbuddy;

import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.support.v7.widget.Toolbar;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by david on 2/9/18.
 *
 * TODO: ask user for priority permissions to emit alert sounds even when DND is on
 *       (done, but requires hitting return arrow)
 * TODO: make dispatch noise for notification alert
 *       (still playing default sound, not mp3)
 * TODO: make icons for notifications
 * TODO: make a general DispatchBuddy icon
 * TODO: refactor dispatches arraylist into sortedlist like personnel
 * TODO: make toolbar menu active on every activity
 * TODO: recolor "alarm fire sounding" icon for pale smoke
 * TODO: scale toolbar icon down a pinch, it's a bit big
 * TODO: toolbar version is cut off on thin phones
 * TODO: cache drive route directions for ~10 minutes
 *
 *
 * notes:
 *
 * play store issues, refer to https://stackoverflow.com/questions/23108684/android-app-published-but-not-found-in-google-play
 * resolved, alphas will NOT show up at the published URL, they show up only to a
 * specific testing url, which is only found AFTER you publish and IF you have a
 * list of testers defined.
 *
 */

public class ActivityMain extends AppCompatActivity {
    private static Context context;
    PowerManager powerManager;

    final private String TAG = "MAIN";
    private static final int ERROR_DIALOG_REQUEST=9001;

    private static Boolean activityVisible = false;

    private DispatchBuddyBase DBB;
    private DBVolley V;

    private Map<String, Boolean> permissions = new HashMap<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // do this before setting contentview .. still not working -__-
        windowAndPower.setWindowParameters(this);

        context = getApplicationContext();
        DBB = DispatchBuddyBase.getInstance();
        DBB.setAppContext(this.getApplicationContext());

        V = DBVolley.getInstance(this.getApplicationContext());

        // see https://stackoverflow.com/questions/6762671/how-to-lock-the-screen-of-an-android-device
//        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
//        PowerManager.WakeLock wl = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Your Tag");
//        wl.acquire();
//        wl.release();

        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.app_bar); // the filename, not the ID
        setSupportActionBar(toolbar);

        Log.i(TAG, "checking needed permissions");
        testServicesAndPermissions();

        if (iHaveNeededPermissions()) {
            Button doLogin = (Button) findViewById(R.id.doLogin);
            doLogin.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    startActivity(new Intent(ActivityMain.this, LoginActivity.class));
                }
            });

            Button doDispatches = (Button) findViewById(R.id.doDispatches);
            doDispatches.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    startActivity(new Intent(ActivityMain.this, ActivityDispatches.class));
                }
            });

            if (DBB.getUser()!=null) {
                Log.i(TAG, "startup with DBB user: "+DBB.getUser());
                // pushing reg will happen 2x on Login, just deal with it until the singleton is finished
                DBB.pushFirebaseClientRegistrationData(DBB.getRegToken());

                TextView mLoggedInUser = findViewById(R.id.loggedInUser);
                mLoggedInUser.setText(DBB.getUser());

                // fetch our list of user meta-data
                DBB.getAllPersonnel();

                Intent i = new Intent(this, ActivityDispatches.class);
                startActivity(i);
            } else {
                Intent i = new Intent(this, LoginActivity.class);
                startActivity(i);
            }
        } else {
            Toast.makeText(this, "Not all desired permissions were granted, some things won't work for you", Toast.LENGTH_SHORT).show();
        }
    }

    private Boolean iHaveNeededPermissions() {
        Boolean isGood = true;
        Log.d(TAG, "permissions map: "+permissions.toString());
        for (String key: permissions.keySet()) {
            if (!permissions.get(key)) {
                isGood=false;
                Toast.makeText(this, key+" needed", Toast.LENGTH_SHORT).show();
            }
        }
        return isGood;
    }

    private boolean hasPermissions(Context context, String... _permissions) {
        if (context != null && _permissions != null) {
            // mark all the perms first, then request
            for (String permission: _permissions) {
                permissions.put(permission, ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED);
            }

            for (String permission : _permissions) {
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

    private void testServicesAndPermissions(){
        permissions.put("googleApi", false);
        permissions.put("notificationPermissions", false);

        int available = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ActivityMain.this);
        Log.d(TAG, "Google play services version: "+GoogleApiAvailability.GOOGLE_PLAY_SERVICES_VERSION_CODE);
        if (available == ConnectionResult.SUCCESS) {
            Log.i(TAG, "Google Play Services is ok");
            permissions.put("googleApi", true);
        } else if (GoogleApiAvailability.getInstance().isUserResolvableError(available)) {
            Log.w(TAG, "Google Play Services error occurred but we can fix it");
            Dialog dialog = GoogleApiAvailability.getInstance().getErrorDialog(ActivityMain.this, available, ERROR_DIALOG_REQUEST);
            dialog.show();
            GoogleApiAvailability.getInstance().makeGooglePlayServicesAvailable(this);
            // hope that fixed it buddy!
            permissions.put("googleApi", true);
        } else {
            Log.w(TAG, "Google Play Services is unfixable, cannot make it go!");
            Toast.makeText(this, "Google API services not available, parts of DispatchBuddy won't work for you", Toast.LENGTH_SHORT).show();
            permissions.put("googleApi", false);
        }

        // special permission case
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                NotificationManager n = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
                if (n.isNotificationPolicyAccessGranted()) {
                    permissions.put("notificationPermissions", true);
                    AudioManager audioManager = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
                    //audioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                } else {
                    // Ask the user to grant access
                    permissions.put("notificationPermissions", false);
                    Toast.makeText(this, "DispatchBuddy needs Notification Policy Access", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                    startActivityForResult(intent, 99);
                }
            } catch (NoSuchMethodError e) {
                Log.w(TAG, "can't use isNotificationPolicyAccessGranted on this platform");
                // pretend it's ok
                permissions.put("notificationPermissions", true);
            }
        } else {
            // pretend it's ok
            permissions.put("notificationPermissions", true);
        }

        int PERMISSION_REQUEST_ID = 98;
        /*Manifest.permission.WRITE_EXTERNAL_STORAGE*/
        String[] PERMISSIONS = {
                Manifest.permission.ACCESS_FINE_LOCATION
                , Manifest.permission.ACCESS_COARSE_LOCATION
        };

        if(!hasPermissions(this, PERMISSIONS)){
            Toast.makeText(this, "Requesting for popup style permissions", Toast.LENGTH_SHORT).show();
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_REQUEST_ID);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityVisible = true;
        context = getApplicationContext();

        // https://stackoverflow.com/questions/40259780/wake-up-device-programmatically
//        KeyguardManager manager = (KeyguardManager) this.getSystemService(Context.KEYGUARD_SERVICE);
//        KeyguardManager.KeyguardLock lock = manager.newKeyguardLock("abc");
//        lock.disableKeyguard();

        windowAndPower.setWindowParameters(this);
        windowAndPower.unlockAndShowScreen(this);

        if (getIntent()!=null) {
            Bundle bundle = getIntent().getExtras();
            if (bundle!= null) {
                try {
                    JSONObject object = new JSONObject(bundle.getString("data"));
                    Log.i(TAG, "bundle data: "+object.toString());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            } else {
                Log.i(TAG, "extras is null");
            }
        } else {
            Log.i(TAG, "getIntet() is null");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityVisible = false;
        context = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        activityVisible = false;
        context = null;
    }

    public static Boolean isActivityVisible() {
        return activityVisible;
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
        } else if (requestCode == 99) {
            if (resultCode==1) {
                permissions.put("notificationsPermission", true);
            }
        }

        else {
            Log.i(TAG,"oAR other...");
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String _permissions[], int[] grantResults) {
        Log.w(TAG, "request code:"+requestCode);

//        requires API 24+
//        IntStream.range(0, permissions.length).forEach(
//                n-> {
//                    Log.w(TAG, "  request perm: "+permissions[n]+", grant result: "+grantResults[n]);
//                }
//        );

        for(int n=0; n<_permissions.length; n++) {
            Log.w(TAG, "  request perm: "+_permissions[n]+", grant result: "+grantResults[n]);
        }

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

            case 98: {
                for(int n=0; n<_permissions.length; n++) {
                    permissions.put(_permissions[n], grantResults[n]==PackageManager.PERMISSION_GRANTED);
                    Log.w(TAG, "  request perm: "+_permissions[n]+", grant result: "+grantResults[n]);
                }
            }

            case 10: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "oAR permission granted");
                    // awesome
                    finish();
                    startActivity(new Intent(this, ActivityMain.class));
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
        Log.e(TAG, "inflating menu");
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.appLogout:
                DBB.logOut();
                TextView mLoggedInUser = findViewById(R.id.loggedInUser);
                mLoggedInUser.setText("");

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
