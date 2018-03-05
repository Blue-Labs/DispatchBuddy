package org.fireground.dispatchbuddy;

import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.PermissionChecker;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by david on 2/9/18.
 *
 * TODO: ask user for priority permissions to emit alert sounds even when DND is on
 *       (done, but requires hitting return arrow)
 * TODO: make dispatch noise for notification alert
 *       (still playing default sound, not smvfd tones mp3)
 * TODO: make [better] icons for notifications
 * TODO: make a [better] general DispatchBuddy icon
 * TODO: refactor dispatches arraylist into sortedlist like personnel
 * TODO: make toolbar menu active on every activity
 * TODO: recolor "alarm fire sounding" icon for pale smoke
 * TODO: scale toolbar icon down a pinch, it's a bit big
 * TODO: cache drive route directions for ~10 minutes
 * TODO: redo the onClick for dispatches so users can tap on the personnel icon for personnel list, and get detailed incident data if tapping elsewhere
 * TODO: the name set in NotificationCategory is a bit wrong, needs to get fixed
 * TODO: unsubscribe from firebase messaging channels on logout
 *
 *
 * notes:
 *
 * play store issues, refer to https://stackoverflow.com/questions/23108684/android-app-published-but-not-found-in-google-play
 * resolved, alphas will NOT show up at the published URL, they show up only to a
 * specific testing url, which is only found AFTER you publish and IF you have a
 * list of testers defined.
 *
 * swipe/fling: https://stackoverflow.com/questions/32966069/how-implement-left-right-swipe-fling-on-layout-in-android
 * tab views w/ swipe: https://www.youtube.com/watch?v=zcnT-3F-9JA
 *
 */

public class ActivityMain extends DispatchBuddyBase {
    final private String TAG = "MAIN";

    private static Boolean activityVisible = false;

    private Map<String, Boolean> permissions = new HashMap<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "checking needed permissions");
        testServicesAndPermissions();

        Button doLogin = (Button) findViewById(R.id.doLogin);
        doLogin.setClickable(getUser() == null);
        doLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (getUser() != null) {
                    Toast.makeText(context, "Already logged in", Toast.LENGTH_SHORT).show();
                }
                startActivity(new Intent(ActivityMain.this, LoginActivity.class));
            }
        });

        Button doDispatches = (Button) findViewById(R.id.doDispatches);
        doDispatches.setClickable(getUser() != null);
        doDispatches.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (getUser() != null) {
                    startActivity(new Intent(ActivityMain.this, ActivityDispatches.class));
                } else {
                    Toast.makeText(context, "Login please", Toast.LENGTH_SHORT).show();
                }
            }
        });

        if (getUser()!=null) {
            Log.i(TAG, "startup with user: "+getUser());
            // pushing reg will happen 2x on Login, just deal with it until the singleton is finished
            pushFirebaseClientRegistrationData(getRegToken());

            TextView mLoggedInUser = findViewById(R.id.loggedInUser);
            mLoggedInUser.setText(getUser());

            if (!iHaveNeededPermissions()) {
                Toast.makeText(context, "Probably want to enable permissions...", Toast.LENGTH_LONG).show();
            }

            // fetch our list of user meta-data
            getAllPersonnel();

            Intent i = new Intent(this, ActivityDispatches.class);
            startActivity(i);
        } else {
            Intent i = new Intent(this, LoginActivity.class);
            startActivity(i);
        }
    }

    private Boolean iHaveNeededPermissions() {
        Boolean isGood = true;
        for (String key: permissions.keySet()) {
            if (!permissions.get(key)) {
                isGood=false;
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
                if (!hasPermission(this, permission)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void testServicesAndPermissions(){
        // not really a permission.. a very necessary service, todo it
        Boolean google = isGoogleApiServicesGood(ActivityMain.this);
        permissions.put("googleApi", google);
        CheckedTextView gbox = findViewById(R.id.googlePlayServicesCheckbox);
        Log.i(TAG, "setting gApi checkbox: "+google);
        gbox.setChecked(google);

        // this svg drawable isn't working todo fix it and put original back into xml file
//        if (google) {
//            gbox.setCheckMarkDrawable(R.drawable.ic_green_check_box);
//        } else {
//            gbox.setCheckMarkDrawable(R.drawable.ic_red_check_box);
//        }


        int PERMISSION_REQUEST_ID = 98;
        /*Manifest.permission.WRITE_EXTERNAL_STORAGE*/
        String[] PERMISSIONS = {
                Manifest.permission.ACCESS_NOTIFICATION_POLICY
                , Manifest.permission.ACCESS_COARSE_LOCATION
                , Manifest.permission.ACCESS_FINE_LOCATION
                , Manifest.permission.CAMERA
        };

        for (String permission: PERMISSIONS) {
            Switch sw = getPermissionSwitchViewID(permission);
            sw.setOnCheckedChangeListener(switchListener);
            sw.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_UP: {
                            Switch _sw = findViewById(v.getId());
                            String _perm = getResources().getResourceName(v.getId());

                            _perm = _perm.substring(_perm.lastIndexOf('/') + 7);
                            ArrayList<Character> _perm_ex = new ArrayList<>();

                            for (int x = 0; x < _perm.length() - 1; x++) {
                                _perm_ex.add(_perm.charAt(x));
                                if (Character.isUpperCase(_perm.charAt(x + 1))) {
                                    _perm_ex.add('_');
                                }
                            }
                            _perm_ex.add(_perm.charAt(_perm.length() - 1));

                            StringBuilder sb = new StringBuilder();
                            for (Character c : _perm_ex) {
                                sb.append(c);
                            }

                            // todo: gotta be a better way to do this
                            _perm =  "android.permission." +sb.toString().toUpperCase();

                            // check if we need to call Settings
                            Boolean state = null;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                Boolean doSettings = (ActivityCompat.checkSelfPermission(context, _perm) == PackageManager.PERMISSION_GRANTED) == _sw.isChecked();
                                if (doSettings) {
                                    ActivityCompat.requestPermissions(ActivityMain.this, new String[]{_perm}, PERMISSION_REQUEST_ID);
                                }
                            }
                        }
                    }
                    return false;
                }
            });

            Boolean state = null;
            // https://stackoverflow.com/questions/37428464/how-can-i-check-permission-under-api-level-23
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Integer foo = ActivityCompat.checkSelfPermission(context, permission);
                state = ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
            } else {
                // below API 23, permission is always granted based on the manifest. this... isn't needed, but it's here
                // for reference
                state = PermissionChecker.checkSelfPermission(context, permission) == PermissionChecker.PERMISSION_GRANTED;
            }
            sw.setChecked(state);

            permissions.put(permission, state);
        }
    }

    private Switch getPermissionSwitchViewID(String opermission) {

//        Log.w(TAG, "operating from: "+opermission);
        String permission = opermission.substring(opermission.lastIndexOf('.') + 1).replace("_", " ");
        permission = toTitleCase(permission);
        permission = permission.replace(" ", "");

        Integer id = getResources().getIdentifier("switch"+permission, "id", getPackageName());
        Switch sw = (Switch) findViewById(id);
        return sw;
    }

    public static String toTitleCase(String givenString) {
        String[] arr = givenString.toLowerCase().split(" ");
        StringBuffer sb = new StringBuffer();

        for (int i = 0; i < arr.length; i++) {
            sb.append(Character.toUpperCase(arr[i].charAt(0)))
                    .append(arr[i].substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    CompoundButton.OnCheckedChangeListener switchListener = new CompoundButton.OnCheckedChangeListener() {
        // not used, here for reference
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (isChecked) {
                Log.i(TAG, "SWITCH ON");
            } else {
                Log.i(TAG, "SWITCH OFF");
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        activityVisible = true;

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
                } catch (NullPointerException e) {
                    //
                }
            } else {
                Log.i(TAG, "extras is null");
            }
        } else {
            Log.i(TAG, "getIntet() is null");
        }

        testServicesAndPermissions();
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityVisible = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        activityVisible = false;
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
            case 98: {
                for(int n=0; n<_permissions.length; n++) {
                    permissions.put(_permissions[n], grantResults[n]==PackageManager.PERMISSION_GRANTED);
                    Log.d(TAG, "  request perm: "+_permissions[n]+", grant result: "+grantResults[n]);
                }
            }
        }
    }

}
