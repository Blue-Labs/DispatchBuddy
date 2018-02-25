package org.fireground.dispatchbuddy;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.support.annotation.NonNull;
import android.util.Log;
import android.provider.Settings.Secure;

import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.google.android.gms.maps.model.LatLng;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by david on 2/20/18.
 */

class DispatchBuddyBase {
    private static DispatchBuddyBase ourInstance;
    private String TAG = "DBB";

    // track Firebase here so we don't try to init it twice
    private static FirebaseDatabase fbDatabase = null;
    private FirebaseAuth.AuthStateListener authListener;

    private String FirebaseMessagingRegToken = null;
    private String androidID = null;
    private String user = null;
    private String domain = null;
    private Boolean debug;

    // used to get AndroidID and to check if this is a debug or release build
    private Context context;

    public void setAppContext(Context context) {
        this.context = context;
    }

    public static DispatchBuddyBase getInstance() {
        if (ourInstance == null) {
            ourInstance = new DispatchBuddyBase();
        }
        return ourInstance;
    }

    private DispatchBuddyBase() {
        // wtf Firebase, local cache is readable even without authentication, ugg!??
        if (fbDatabase == null) {
            // this is locked inside here because it can only happen once regardless of
            // how many instances are created
            fbDatabase = FirebaseDatabase.getInstance();
            fbDatabase.setPersistenceEnabled(false);
        }

        // fbDatabase.getReference("dispatches").keepSynced(false); // todo: HIPAA and general privacy concerns
        authListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser _user = firebaseAuth.getCurrentUser();
                if (_user != null) {
                    user = _user.getEmail();
                    Log.i(TAG, "detected user authentication: "+user);

                } else {
                    Log.i(TAG, "detected user signout");
                }
                // ...
            }
        };
        FirebaseAuth.getInstance().addAuthStateListener(authListener);

        FirebaseMessagingRegToken = FirebaseInstanceId.getInstance().getToken();
        Log.d(TAG, "FBmsging token for this device is: " + FirebaseMessagingRegToken);
    }

    public void logjam() {
        Log.e("BRO!", "***************************** singleton test");
    }

    public void logOut() {
        FirebaseAuth.getInstance().signOut();
        this.domain = null;
        this.user = null;
    }

    public String getUser() {
        FirebaseUser user;
        try {
            user = FirebaseAuth.getInstance().getCurrentUser();
            // todo: some form of race codition existed prior to making this singleton, user!=null, but fields were
//            Log.e(TAG, "user.DisplayName: "+user.getDisplayName());
//            Log.e(TAG, "user.email: "+user.getEmail());
//            Log.e(TAG, "user.Uid: "+user.getUid());
            if (user == null) {
                Log.e(TAG, "user shouldn't be null!");
            }
        } catch (NullPointerException e) {
            Log.w(TAG, e.getLocalizedMessage());
            Log.w(TAG, "getUser caused an exception");
            return null;
        }

        if (user != null) {
            this.user = user.getEmail();
            this.domain = this.user.split("@")[1].replaceAll("\\.", "_");
        } else {
            this.user = null;
            this.domain = null;
        }
        Log.i(TAG, "getUser returning: "+this.user);
        return this.user;
    }

    public void pushFirebaseClientRegistrationData(String registrationToken) {
        this.FirebaseMessagingRegToken = registrationToken;

        // update device registration for user
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        androidID = Secure.getString(context.getContentResolver(), Secure.ANDROID_ID);

        Log.d("targ", "user is: "+user);
        Log.d("targ", "aid: "+androidID);
        Log.d("targ", "reg token: "+FirebaseMessagingRegToken);

        String domain = user.getEmail().split("@")[1].replaceAll("\\.", "_");
        DatabaseReference ref = getTopPathRef("/deviceRegistrations");

        DatabaseReference newRef = ref.child(androidID);
        String newKey = newRef.getKey();
        Date date = new Date();
        SimpleDateFormat f = new SimpleDateFormat("yyyy.M.d h:mm:ss a zzz");

        Map<String, Object> u = new HashMap<>();
        u.put("registeredUser", user.getEmail());
        u.put("firebaseMessagingRegToken", FirebaseMessagingRegToken);
        u.put("lastUpdated", f.format(date));
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

    /*
     * The assumption is made that the caller knows if "/" is prefixed at root level tables
     */
    private String buildPathPrefix(String path) {
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

    public DatabaseReference getTopPathRef(String path) {
        path = buildPathPrefix(path);
//        Log.d(TAG, "obtaining ref for: "+path);
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference(path);

        return ref;
    }

    public String getRegToken() {
        return FirebaseInstanceId.getInstance().getToken();
    }

    /*
     * GMAPs section
     */

    public String prepareAddress(String address) {
        address = address.replaceAll(" ", "+")
                .replaceAll(",", "")
                .replaceAll("\\.", " ")
                .replaceAll("  ", " ")
                .toUpperCase();
        return address;
    }

    public void getLatLng(final String address,
                          final JsonObjectCallbackInterface callback) {

        String url = "https://maps.googleapis.com/maps/api/geocode/json?address="
                + address;

        Log.d(TAG, "geocoding url: "+url);

        JsonObjectRequest jsObjRequest = new JsonObjectRequest
                (Request.Method.GET, url, null, new Response.Listener<JSONObject>() {

                    @Override
                    public void onResponse(JSONObject response) {
//                        Log.e(TAG,"Response: " + response.toString());
                        // store address in firebase as our API is request limited
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
                    }
                }, new Response.ErrorListener() {

                    @Override
                    public void onErrorResponse(VolleyError error) {
                        // TODO Auto-generated method stub
                    }
                });

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
                + this.context.getResources().getString(R.string.google_ip_address_map_api_key);
                ; // probably need to put our api key in here..? we need an android method for this
                  // so we can use the android key without IP restrictions

        Log.d(TAG, "directions url: "+url);

        JsonObjectRequest jsObjRequest = new JsonObjectRequest
                (Request.Method.GET, url, null, new Response.Listener<JSONObject>() {

                    @Override
                    public void onResponse(JSONObject response) {
//                        Log.e(TAG,"Response: " + response.toString());
                        // don't store these, the time and warnings change constantly
                        callback.onSuccess(response);
                    }
                }, new Response.ErrorListener() {

                    @Override
                    public void onErrorResponse(VolleyError error) {
                        // TODO Auto-generated method stub
                    }
                });

        DBVolley.getInstance(context)
                .addToRequestQueue(jsObjRequest);
    }
}
