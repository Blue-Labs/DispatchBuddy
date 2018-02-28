package org.fireground.dispatchbuddy;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.Image;
import android.support.annotation.NonNull;
import android.util.Log;
import android.provider.Settings.Secure;
import android.widget.ImageView;

import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.firebase.ui.storage.images.FirebaseImageLoader;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Logger;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.storage.FileDownloadTask;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.ref.Reference;
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
    private static FirebaseStorage fbStorage = null;
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
    public Context getAppContext() { return this.context; }

    public static DispatchBuddyBase getInstance() {
        if (ourInstance == null) {
            ourInstance = new DispatchBuddyBase();
        }
        return ourInstance;
    }

    private DispatchBuddyBase() {
        // wtf Firebase, local cache is readable even without authentication, ugg!??
        if (fbDatabase == null) {
            // this is set at the first DBB instancing because it can only happen once regardless of
            // how many instances are created
            fbDatabase = FirebaseDatabase.getInstance();
            fbDatabase.setPersistenceEnabled(false);

            FirebaseDatabase.getInstance().setLogLevel(Logger.Level.DEBUG);
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
        Log.d(TAG, "FBmsging token for this device is: " + FirebaseMessagingRegToken);
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
            // todo: some form of race codition exists, sometimes user is null, sometimes user!=null, but fields are
            if (user == null) {
                Log.d(TAG, "user is null");
            } else {
                Log.e(TAG, "user.DisplayName: "+user.getDisplayName());
                Log.e(TAG, "user.email: "+user.getEmail());
                Log.e(TAG, "user.Uid: "+user.getUid());
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
        Log.d(TAG, "getUser returning: "+this.user);
        return this.user;
    }

    public void pushFirebaseClientRegistrationData(String registrationToken) {
        this.FirebaseMessagingRegToken = registrationToken;

        // update device registration for user
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        androidID = Secure.getString(context.getContentResolver(), Secure.ANDROID_ID);

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
     * todo: add in instance state saving so if our activity gets booted for some reason,
     *       our downloads can resume and be referred to next time we're alive
     *       https://firebase.google.com/docs/storage/android/download-files#handle_activity_lifecycle_changes
     */
    public void getProfileIcon(Context context, final ImageView view, String email) {
        DatabaseReference ref = getTopPathRef("/personnel");

        ref.orderByChild("email")
                .equalTo(email)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        if (!dataSnapshot.exists()) {
                            // no such person exists?
                            Log.e(TAG, "no personnel records in firebase for: "+email);
                        } else {
                            Log.i(TAG, "getting profileIcon for: "+email);
                            if (dataSnapshot.getValue() == null) {
                                Log.w(TAG, "no personnel records found for: "+email);
                            } else {
                                DataSnapshot ds1 = dataSnapshot.getChildren().iterator().next();
                                String key = ds1.getKey();

                                String imageUrl = (String) dataSnapshot.child(key).child("profileIcon").getValue();
                                String filename = imageUrl.substring(imageUrl.lastIndexOf('/')+1);

                                String path = buildPathPrefix("/personnel");
                                Log.i(TAG, "building image path: "+path+"profileIcons/"+filename);
                                // gs://dispatchbuddy-ca126.appspot.com/debug/personnel/smvfd_info

                                StorageReference image = fbStorage
                                        .getReference()
                                        .child(path)
                                        .child("/profileIcons")
                                        .child(filename);

                                try {
                                    Glide.with(context)
                                            .using(new FirebaseImageLoader())
                                            .load(image)
                                            .diskCacheStrategy(DiskCacheStrategy.NONE) // turn these off after testing=good
                                            .skipMemoryCache(true)
                                            .into(view);
                                    // .error(R.drawable.defaultuserimage)
                                    Glide.get(context).clearMemory();
                                    Glide.get(context).clearDiskCache();
                                } catch (Exception e) {
                                    Log.e(TAG, e.getLocalizedMessage());
                                }

                            }

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
                + "&bounds=41.507946089754675,-72.86632062769684|41.54187465697938,-72.80623914576324"
                + "&key="
                + this.context.getResources().getString(R.string.google_ip_address_map_api_key);

        Log.d(TAG, "geocoding url: "+url);

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
                        Log.d(TAG, "JSON drive directions content fetched");
                        callback.onSuccess(response);
                    }
                }, new Response.ErrorListener() {

                    @Override
                    public void onErrorResponse(VolleyError error) {
                        // TODO Auto-generated method stub
                        Log.e(TAG, "failed to fetch JSON:"+error.getLocalizedMessage());
                    }
                });

        DBVolley.getInstance(context)
                .addToRequestQueue(jsObjRequest);
    }
}
