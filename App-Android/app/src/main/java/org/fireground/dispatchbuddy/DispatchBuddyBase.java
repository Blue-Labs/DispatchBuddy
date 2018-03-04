package org.fireground.dispatchbuddy;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.provider.Settings.Secure;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.firebase.ui.storage.images.FirebaseImageLoader;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.maps.model.LatLng;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by david on 2/20/18.
 */

abstract class DispatchBuddyBase extends AppCompatActivity {
    private static String TAG = "DBB";

    // track Firebase here so we don't try to init it twice
    private static FirebaseDatabase fbDatabase = null;
    private static FirebaseStorage fbStorage = null;
    private FirebaseAuth.AuthStateListener authListener;

    private DBVolley V;

    private static String FirebaseMessagingRegToken = null;
    private static String user = null;
    private static String domain = null;
    private static String androidID = null;

    public static Context context;

    public Context getAppContext() { return this.context; }

    public DispatchBuddyBase getInstance() {
        return this;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // do this before setting contentview .. still not working -__-
        windowAndPower.setWindowParameters(this);

        context = getApplicationContext();

        V = DBVolley.getInstance(this.getApplicationContext());

        // see https://stackoverflow.com/questions/6762671/how-to-lock-the-screen-of-an-android-device
//        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
//        PowerManager.WakeLock wl = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Your Tag");
//        wl.acquire();
//        wl.release();

        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.app_bar); // the filename, not the ID
        setSupportActionBar(toolbar);

        // wtf Firebase, local cache is readable even without authentication, ugg!??
        if (fbDatabase == null) {
            // this is set at the first DBB instancing because it can only happen once regardless of
            // how many instances are created
            fbDatabase = FirebaseDatabase.getInstance();
            fbDatabase.setPersistenceEnabled(false);

//            FirebaseDatabase.getInstance().setLogLevel(Logger.Level.DEBUG);
        }

        // currently for profile icons, probably will use to hold fireground image data
        // like floorplans
        if (fbStorage == null) {
            fbStorage = FirebaseStorage.getInstance();
        }

        // fbDatabase.getReference("dispatches").keepSynced(false); // todo: HIPAA and general privacy concerns

        // it'd be nice to know _what_ happened for this to fire
        authListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser _user = firebaseAuth.getCurrentUser();
                if (_user != null) {
                    user = _user.getEmail();
                    Log.i(TAG, "detected authenticated user: "+user);

                } else {
                    Log.i(TAG, "no user is logged in");
                }
            }
        };
        FirebaseAuth.getInstance().addAuthStateListener(authListener);

        FirebaseMessagingRegToken = FirebaseInstanceId.getInstance().getToken();
//        Log.d(TAG, "FBmsging token for this device is: " + FirebaseMessagingRegToken);
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
                logOut();
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

    public boolean hasPermission(Context context, String permission) {
        return ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static final int ERROR_DIALOG_REQUEST=9001;
    public Boolean isGoogleApiServicesGood(Activity activity){
        int available = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity);
        Log.d(TAG, "Google play services version: "+GoogleApiAvailability.GOOGLE_PLAY_SERVICES_VERSION_CODE);
        if (available == ConnectionResult.SUCCESS) {
            Log.i(TAG, "Google Play Services is ok");
            return true;
        } else if (GoogleApiAvailability.getInstance().isUserResolvableError(available)) {
            Log.w(TAG, "Google Play Services error occurred but we can fix it");
            Dialog dialog = GoogleApiAvailability.getInstance().getErrorDialog(activity, available, ERROR_DIALOG_REQUEST);
            dialog.show();
            GoogleApiAvailability.getInstance().makeGooglePlayServicesAvailable(this);
            // hope that fixed it buddy!
            return true;
        } else {
            Log.w(TAG, "Google Play Services is unfixable, cannot make it go!");
            Toast.makeText(this, "Google API services not available, parts of DispatchBuddy won't work for you", Toast.LENGTH_SHORT).show();
        }
        return false;
    }

    public void logOut() {
        FirebaseAuth.getInstance().signOut();
        this.domain = null;
        this.user = null;
    }

    public static String getUser() {
        FirebaseUser user;
        try {
            user = FirebaseAuth.getInstance().getCurrentUser();
            // todo: some form of race codition exists, sometimes user is null, sometimes user!=null, but fields are
            if (user == null) {
                Log.d(TAG, "user is null");
            } else {
//                Log.e(TAG, "user.DisplayName: "+user.getDisplayName());
//                Log.e(TAG, "user.email: "+user.getEmail());
//                Log.e(TAG, "user.Uid: "+user.getUid());
            }
        } catch (NullPointerException e) {
            Log.w(TAG, e.getLocalizedMessage());
            Log.w(TAG, "getUser caused an exception");
            return null;
        }

        if (user != null) {
            DispatchBuddyBase.user = user.getEmail();
            DispatchBuddyBase.domain = DispatchBuddyBase.user.split("@")[1].replaceAll("\\.", "_");
        } else {
            DispatchBuddyBase.user = null;
            DispatchBuddyBase.domain = null;
        }
//        Log.d(TAG, "getUser returning: "+this.user);
        return DispatchBuddyBase.user;
    }

    public static void pushFirebaseClientRegistrationData(String registrationToken) {
        FirebaseMessagingRegToken = registrationToken;

        // update device registration for user
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        androidID = Secure.getString(context.getContentResolver(), Secure.ANDROID_ID);

        String domain = user.getEmail().split("@")[1].replaceAll("\\.", "_");
        DatabaseReference ref = getTopPathRef("/deviceRegistrations");

        DatabaseReference newRef = ref.child(androidID);
        String newKey = newRef.getKey();
        Date date = new Date();
        SimpleDateFormat f = new SimpleDateFormat("yyyy.M.d h:mm:ss a zzz");

        String phoneMeta="";
        phoneMeta += "OS Version: " + System.getProperty("os.version") + "(" + android.os.Build.VERSION.INCREMENTAL + ") ";
        phoneMeta += "OS API Level: " + android.os.Build.VERSION.RELEASE + "(" + android.os.Build.VERSION.SDK_INT + ") ";
        phoneMeta += "Device: " + android.os.Build.DEVICE + " ";
        phoneMeta += "Model (and Product): " + android.os.Build.MODEL + " ("+ android.os.Build.PRODUCT + ")";

        Map<String, Object> u = new HashMap<>();
        u.put("registeredUser", user.getEmail());
        u.put("firebaseMessagingRegToken", FirebaseMessagingRegToken);
        u.put("lastUpdated", f.format(date));
        u.put("phoneMeta", phoneMeta);

        newRef.updateChildren(u, new DatabaseReference.CompletionListener() {
            @Override
            public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
                if (databaseError != null) {
                    Log.e("targ","Data could not be saved " + databaseError.getMessage());
                } else {
                    Log.e("targ","Data saved successfully.");
                }
            }
        });
    }

    public static ArrayList<ModelPersonnel> personnel = new ArrayList<>();
    public void getAllPersonnel() {
        DatabaseReference ref = getTopPathRef("/personnel");
        ref.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                ModelPersonnel model = dataSnapshot.getValue(ModelPersonnel.class);
                Log.d(TAG, "personnel: "+dataSnapshot.toString());
                model.setKey(dataSnapshot.getKey());
                personnel.add(model);
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                ModelPersonnel model = dataSnapshot.getValue(ModelPersonnel.class);
                String key = dataSnapshot.getKey();
                model.setKey(key);
                Integer index = getPersonnelIndex(key);
                if (index < 0) { // our parent node changed, but this child is new
                    personnel.add(model);
                } else {
                    personnel.set(index, model);
                }
            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {

            }

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {

            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    private int getPersonnelIndex(String key) {
        int index = -1;

        for (int i = 0; i < personnel.size(); i++) {
            if (personnel.get(i).getKey().equals(key)) {
                index = i;
                break;
            }
        }

        return index;
    }
    public static ModelPersonnel getPerson(String email) {
        for (int i = 0; i < personnel.size(); i++) {
            if (personnel.get(i).getEmail().equals(email)) {
                return personnel.get(i);
            }
        }

        return null;
    }

    public static ModelPersonnel addNullPerson(String email) {
        // used to create a nearly blank object due to missing records in FB
        ModelPersonnel person = new ModelPersonnel();
        person.setEmail(email);
        personnel.add(person);
        Log.w("DBB", "personnel: "+personnel.toString());
        return person;
    }


    /*
     * The assumption is made that the caller knows if "/" is prefixed at root level tables
     */
    private static String buildPathPrefix(String path) {
        boolean isDebuggable =  ( 0 != ( context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE ) );
        if (isDebuggable) {
            path = "/debug" + path;
        }

        // then postfix the domain to the path
        path += "/" + domain + "/";

        return path;
    }

    private String buildChannelName(String channel) {
        boolean isDebuggable =  ( 0 != ( context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE ) );
        if (isDebuggable) {
            channel = "debug-"+channel;
        }

        // then postfix the domain to the channel
        channel += "-" + domain;

        return channel;
    }

    public void subscribeChannel(String channel) {
        // channels will use the same buildable form for channel names
        channel = buildChannelName(channel);

        Log.d(TAG, "subscribing to channel: "+channel);
        FirebaseMessaging.getInstance().subscribeToTopic(channel);
    }

    public static DatabaseReference getTopPathRef(String path) {
        path = buildPathPrefix(path);
//        Log.d(TAG, "obtaining ref for: "+path);
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference(path);

        return ref;
    }

    public static String getRegToken() {
        return FirebaseInstanceId.getInstance().getToken();
    }

    /*
     * todo: add in instance state saving so if our activity gets booted for some reason,
     *       our downloads can resume and be referred to next time we're alive
     *       https://firebase.google.com/docs/storage/android/download-files#handle_activity_lifecycle_changes
     */
    public static void getProfileIcon(Context context, final ImageView view, String email) {
        DatabaseReference ref = getTopPathRef("/personnel");

        ref.orderByChild("email")
                .equalTo(email)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        String filename;

                        if (!dataSnapshot.exists()) {
                            // no such person exists?
                            Log.e(TAG, "cannot get profileIcon, no personnel records in firebase for: "+email);
                            // apply a blank image
                            filename = "fire_axes_and_shield_64x64.png";

                        } else {
                            Log.i(TAG, "getting profileIcon for: "+email);
                            if (dataSnapshot.getValue() == null) {
                                Log.w(TAG, "no personnel records found for: "+email);
                                filename = "fire_axes_and_shield_64x64.png";

                            } else {
                                DataSnapshot ds1 = dataSnapshot.getChildren().iterator().next();
                                String key = ds1.getKey();

                                String imageUrl = (String) dataSnapshot.child(key).child("profileIcon").getValue();
                                filename = imageUrl.substring(imageUrl.lastIndexOf('/')+1);
                            }
                        }

                        String path = buildPathPrefix("/personnel");
                        Log.i(TAG, "building image path: "+path+"profileIcons/"+filename);

                        // future ref: https://www.journaldev.com/13759/android-picasso-tutorial

                        StorageReference image = fbStorage
                                .getReference()
                                .child(path)
                                .child("/profileIcons")
                                .child(filename);
                        try {
                            Glide.with(context)
                                    .using(new FirebaseImageLoader())
                                    .load(image)
                                    .override(48,48)
//                                    .diskCacheStrategy(DiskCacheStrategy.NONE) // turn these off after testing=good
//                                    .skipMemoryCache(true)
                                    .into(view);
                            // .error(R.drawable.defaultuserimage)
//                            Glide.get(context).clearMemory();
//                            Glide.get(context).clearDiskCache();
                        } catch (Exception e) {
                            Log.e(TAG, e.getLocalizedMessage());
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                    }
                });


    }

    /*
     * GMAPs section
     */

    public String prepareAddress(String address) {
        address = address
                .replaceAll("\\.", " ")
                .replaceAll(",", " ")
                .replaceAll("  +", " ")
                .replaceAll("&", "and")
                .replaceAll(" ", "+")
                .toUpperCase();
        return address;
    }

    public void getLatLng(final String address,
                          final JsonObjectCallbackInterface callback) {

        // the bounds key will bias (prefer) addresses in South Meriden district
        // http://www.automatingosint.com/blog/geographic-bounding-box-drawing-tool/
        // sw 41.507946089754675,-72.86632062769684
        // ne 41.54187465697938,-72.80623914576324
        String url = "https://maps.googleapis.com/maps/api/geocode/json?address="
                + address
                + "&bounds=41.509927,-72.858410|41.53740,-72.807598"
                + "&key="
                + this.context.getResources().getString(R.string.google_android_web_referer_api_key);

        Log.d(TAG, "geocoding ((( ANDROID KEY ))) url: "+url);

        JsonObjectRequest jsObjRequest = new JsonObjectRequest
                (Request.Method.GET, url, null, new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
//                        Log.e(TAG,"Response: " + response.toString());
                        // store address in firebase as our API is request limited

                        // todo: handle error responses!
                        /*
                         * "{\"error_message\":\"You have exceeded your daily request quota for this API. We recommend registering for a key at the Google Developers Console: https:\\/\\/console.developers.google.com\\/apis\\/credentials?project=_\",\"results\":[],\"status\":\"OVER_QUERY_LIMIT\"}"
                         */
                        if (response.toString().contains("status\":\"OK")) {
                            DatabaseReference ref = getTopPathRef("/geocodedAddresses").push();

                            Map<String, Object> u = new HashMap<>();
                            u.put("address", address);
                            u.put("geocoded", response.toString());

                            ref.updateChildren(u, new DatabaseReference.CompletionListener() {
                                @Override
                                public void onComplete(DatabaseError databaseError,
                                                       DatabaseReference databaseReference) {
                                    if (databaseError != null) {
                                        Log.e(TAG,"Geocoded address could not be saved "
                                                + databaseError.getMessage());
                                    } else {
                                        Log.e(TAG,"Geocoded address saved successfully.");
                                    }
                                }
                            });
                            callback.onSuccess(response);
                        } else {
                            Integer index1 = response.toString().indexOf("status")+3;
                            Integer index2 = response.toString().indexOf('"', index1);
                            String status = response.toString().substring(index1, index2);
                            Log.e(TAG, "JSON Response not acceptable: "+status);
                            // todo: notify david of failed parsing
                        }

                    }
                }, new Response.ErrorListener() {

                    @Override
                    public void onErrorResponse(VolleyError error) {
                        // TODO Auto-generated method stub
                    }
                }) {

            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                        /*
                         * there's no way to legit secure this key with restrictions in the
                         * console. we can't restrict by ios/android type, most phones will
                         * have wildly varying IPs per carrier proxies, ...
                         *
                         * actually, the googleapi doesn't permit the use of a referer for
                         * key restrictions either! wtf!!
                         */
                headers.put("Referer", "https://smvfd.info/");
                headers.putAll(super.getHeaders());
                return headers;
            }
        };

        DBVolley.getInstance(context)
                .addToRequestQueue(jsObjRequest);
    }

    public void getGmapDirectionsJson(final LatLng origin, final LatLng destination,
                                      final JsonObjectCallbackInterface callback) {

        String url = "https://maps.googleapis.com/maps/api/directions/json?origin="
                + origin.latitude
                + ","
                + origin.longitude
                + "&destination="
                + destination.latitude
                + ","
                + destination.longitude
                +"&key="
                + this.context.getResources().getString(R.string.google_android_web_referer_api_key);
                ; // probably need to put our api key in here..? we need an android method for this
                  // so we can use the android key without IP restrictions

        Log.d(TAG, "directions url: "+url);

        JsonObjectRequest jsObjRequest = new JsonObjectRequest
                (Request.Method.GET, url, null, new Response.Listener<JSONObject>() {

                    @Override
                    public void onResponse(JSONObject response) {
//                        Log.e(TAG,"Response: " + response.toString());
                        // don't store these, the time and warnings change constantly
                        Log.d(TAG, "JSON drive directions content fetched");
                        callback.onSuccess(response);
                    }
                }, new Response.ErrorListener() {

                    @Override
                    public void onErrorResponse(VolleyError error) {
                        // TODO Auto-generated method stub
                        Log.e(TAG, "failed to fetch JSON:"+error.getLocalizedMessage());
                    }
                }) {

            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                        /*
                         * there's no way to legit secure this key with restrictions in the
                         * console. we can't restrict by ios/android type, most phones will
                         * have wildly varying IPs per carrier proxies, ...
                         *
                         * actually, the googleapi doesn't permit the use of a referer for
                         * key restrictions either! wtf!!
                         */
                headers.put("Referer", "https://smvfd.info/");
                headers.putAll(super.getHeaders());
                return headers;
            }
        };

        DBVolley.getInstance(context)
                .addToRequestQueue(jsObjRequest);
    }
}
