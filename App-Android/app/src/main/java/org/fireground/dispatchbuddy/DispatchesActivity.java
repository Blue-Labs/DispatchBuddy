package org.fireground.dispatchbuddy;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.location.Geocoder;
import android.location.Location;
import android.location.Address;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.CheckedTextView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.LocationSettingsResult;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.Query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Created by david on 2/11/18.
 *
 * will inflate using the xml template dispatch_item.xml
 *
 * refer to http://www.androidrey.com/android-location-settings-dialog-tutorial/ for gmaps things
 *
 * todo: go over https://www.toptal.com/android/android-developers-guide-to-google-location-services-api
 *
 */

public class DispatchesActivity extends AppCompatActivity implements OnMapReadyCallback, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, ResultCallback<LocationSettingsResult> {
    final private String TAG = "DA";
    private DispatchBuddyBase DBB;

    Query ordered_dispatches_query;
    Query dispatch_status_query;

    private RecyclerView recyclerView;
    private List<DispatchModel> dispatches;
    public static List<DispatchStatusModel> dispatch_statuses;
    private DispatchAdapter adapter;
    private DispatchStatusAdapter statusAdapter;

    private TextView emptyText;
    private NotificationUtils mNotificationUtils;
    public static Dialog dispatchLongpressDialog;

    private int eventCount = 0;
    private int startupCount = 10;

    protected GoogleApiClient mGoogleApiClient;
    protected LocationRequest locationRequest;
    int REQUEST_CHECK_SETTINGS = 100;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // don't save dispatch data, privacy requirement
        super.onCreate(null);
        setContentView(R.layout.activity_dispatches);
        Toolbar toolbar = (Toolbar) findViewById(R.id.app_bar);
        setSupportActionBar(toolbar);

        mNotificationUtils = new NotificationUtils(this);
        DBB = DispatchBuddyBase.getInstance();
        Log.i(TAG, "startup with DBB user: " + DBB.getUser());

        // subscribe to data-notifications
        DBB.subscribeChannel("dispatches");

        ordered_dispatches_query = DBB.getTopPathRef("/dispatches").orderByChild("isotimestamp").limitToLast(startupCount);
        dispatch_status_query = DBB.getTopPathRef("/dispatch-status");

        emptyText = (TextView) findViewById(R.id.text_no_data);

        dispatches = new ArrayList<>();
        dispatch_statuses = new ArrayList<>();

        recyclerView = (RecyclerView) findViewById(R.id.dispatch_list);
        recyclerView.setHasFixedSize(true);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setOrientation(LinearLayoutManager.VERTICAL);
        llm.setReverseLayout(true);
        llm.setStackFromEnd(true);

        recyclerView.setLayoutManager(llm);

        adapter = new DispatchAdapter(dispatches, new CustomItemClickListener() {
            @Override
            public void onItemClick(View v, int position) {
                String postId = dispatches.get(position).getKey();
                //Log.d(TAG, "clicked position:" + position);
                //Log.d(TAG, "clicked key:" + postId);
            }

            @Override
            public boolean onItemLongClick(View v, int position) {
                //Log.i(TAG, "dispatches:"+dispatches.toString());
                String postId = dispatches.get(position).getKey();
                //Log.d(TAG, "longclicked position:" + position);
                //Log.d(TAG, "longclicked key:" + postId);
                dispatchesLongpressDialog(dispatches.get(position));
                return true;
            }
        });

        statusAdapter = new DispatchStatusAdapter(this);

        recyclerView.setAdapter(adapter);

        setListeners();
        checkIfEmpty();

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this).build();
        mGoogleApiClient.connect();

        locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(15 * 60 * 1000); // when idle, every 15 minutes
        locationRequest.setFastestInterval(5 * 1000);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (DBB.getUser() == null) {
            DispatchesActivity.this.finish();
        }
    }

    private void setListeners() {
        dispatch_status_query.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
//                if (s != null) {
//                    Log.i(TAG, "model s:" + s);
//                }
//
//                Log.i(TAG, "snapshot:" + dataSnapshot.toString());

                DispatchStatusModel model = dataSnapshot.getValue(DispatchStatusModel.class);
                if (model != null) {
                    //Log.i(TAG, "model:" + model.toString());

                    String key = dataSnapshot.getKey();
                    // todo: we shouldn't be doing this, use a better way of tracking the key..duh
                    model.setKey(key);
                    dispatch_statuses.add(model);

                    // update the dialog
                    statusAdapter.updateDialogFromModel(model);
                }
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {
//                if (s != null) {
//                    Log.i(TAG, "model s:" + s);
//                }

                DispatchStatusModel model = dataSnapshot.getValue(DispatchStatusModel.class);
                if (model != null) {
                    int index = getStatusItemIndex(model);
//                    Log.i(TAG, "model:" + model.toString());
//                    Log.i(TAG, "index:" + index);

                    dispatch_statuses.set(index, model);


                    // update the dialog
                    statusAdapter.updateDialogFromModel(model);
                }
            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {
                DispatchStatusModel model = dataSnapshot.getValue(DispatchStatusModel.class);
                if (model != null) {
                    int index = getStatusItemIndex(model);
//                    dispatch_statuses.set(index, model);

//                    Log.i(TAG, "model:" + model.toString());
                }
            }

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
            }
        });

        ordered_dispatches_query.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                DispatchModel model = dataSnapshot.getValue(DispatchModel.class);
                if (model != null) {
                    model.setKey(dataSnapshot.getKey());
                    dispatches.add(model);
                    adapter.notifyDataSetChanged();
                    checkIfEmpty();

                    // TODO this mini builder should become its own method
                    if (eventCount > startupCount) {
                        Integer priority = 0;
                        String msgType = model.getMsgtype();
                        switch (msgType) {
                            case "dispatch":
                                priority = NotificationCompat.PRIORITY_MAX;
                                break;
                            case "standby":
                                priority = NotificationCompat.PRIORITY_HIGH;
                                break;
                            default:
                                priority = NotificationCompat.PRIORITY_DEFAULT;
                        }

                        String message = model.nature;
                        String status = model.event_status;
                        if (status != null) {
                            message += "\n[" + status + "]";
                            mNotificationUtils.sendNotification(message, model.address, priority, model.isotimestamp, false);
                        } else {
                            // create a parent notification as the group summary
                            mNotificationUtils.sendNotification(message, model.address, priority, model.isotimestamp, true);
                        }
                    } else {
                        eventCount++;
                    }
                }
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                DispatchModel model = dataSnapshot.getValue(DispatchModel.class);

                int index = getDispatchItemIndex(model);
                model.setKey(dataSnapshot.getKey());

                dispatches.set(index, model);
                adapter.notifyItemChanged(index);

                Integer priority = 0;
                String msgType = model.getMsgtype();
                switch (msgType) {
                    case "dispatch":
                        priority = NotificationCompat.PRIORITY_MAX;
                        break;
                    case "standby":
                        priority = NotificationCompat.PRIORITY_HIGH;
                        break;
                    default:
                        priority = NotificationCompat.PRIORITY_DEFAULT;
                }

                String message = model.nature;
                String status = model.event_status;
                if (status != null) {
                    message += "\n[" + status + "]";
                    mNotificationUtils.sendNotification(message, model.address, priority, model.isotimestamp, false);
                } else {
                    // create a parent notification as the group summary
                    mNotificationUtils.sendNotification(message, model.address, priority, model.isotimestamp, true);
                }
            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {
                DispatchModel model = dataSnapshot.getValue(DispatchModel.class);

                int index = getDispatchItemIndex(model);

                dispatches.remove(index);
                adapter.notifyItemRemoved(index);
                checkIfEmpty();
            }

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {

            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    private int getDispatchItemIndex(DispatchModel dispatch) {
        int index = -1;

        for (int i = 0; i < dispatches.size(); i++) {
            if (dispatches.get(i).isotimestamp.equals(dispatch.isotimestamp)) {
                index = i;
                break;
            }
        }

        return index;
    }

    private int getStatusItemIndex(DispatchStatusModel model) {
        int index = -1;

        for (int i = 0; i < dispatch_statuses.size(); i++) {
            if (dispatch_statuses.get(i).getKey().equals(model.key)) {
                index = i;
                break;
            }
        }

        return index;
    }

    private void checkIfEmpty() {
        if (dispatches.size() == 0) {
            recyclerView.setVisibility(View.INVISIBLE);
            emptyText.setVisibility(View.INVISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.VISIBLE);
        }
    }

    private MapView mapView;
    private GoogleMap gmap;
    private FusedLocationProviderClient mFusedLocationClient;
    protected Location mLastLocation;
    private AddressResultReceiver mResultReceiver;

    class AddressResultReceiver extends ResultReceiver {
        public AddressResultReceiver(Handler handler) {
            super(handler);
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {

            // Display the address string
            // or an error message sent from the intent service.
            String mAddressOutput = resultData.getString(DispatchBuddyFetchAddressIntentService.Constants.RESULT_DATA_KEY);
            //displayAddressOutput();
            Log.i(TAG, "address: "+mAddressOutput);

            // Show a toast message if an address was found.
//            if (resultCode == DispatchBuddyFetchAddressIntentService.Constants.SUCCESS_RESULT) {
//                showToast(getString(R.string.address_found));
//            }
        }
    }

    /*
     * latlng -> address list
     */
    protected void createLocationRequest() {
        LocationRequest mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(10000);
        mLocationRequest.setFastestInterval(5000);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(mLocationRequest);

        SettingsClient client = LocationServices.getSettingsClient(this);
        Task<LocationSettingsResponse> task = client.checkLocationSettings(builder.build());

        task.addOnSuccessListener(this, new OnSuccessListener<LocationSettingsResponse>() {
            @Override
            public void onSuccess(LocationSettingsResponse locationSettingsResponse) {
                // All location settings are satisfied. The client can initialize
                // location requests here.
                // ...
            }
        });

        task.addOnFailureListener(this, new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                if (e instanceof ResolvableApiException) {
                    // Location settings are not satisfied, but this can be fixed
                    // by showing the user a dialog.
                    try {
                        // Show the dialog by calling startResolutionForResult(),
                        // and check the result in onActivityResult().
                        ResolvableApiException resolvable = (ResolvableApiException) e;
                        resolvable.startResolutionForResult(DispatchesActivity.this,
                                REQUEST_CHECK_SETTINGS);
                    } catch (IntentSender.SendIntentException sendEx) {
                        // Ignore the error.
                    }
                }
            }
        });
    }


    public LatLng getLocationFromAddress(Context context, String strAddress) {

//        GeoApiContext context = new GeoApiContext.Builder()
//                .apiKey("AIzaSyAXexsV69LPaoBxE5gMPpnHS_VY7Ydm7lA")
//                .build();
//        GeocodingResult[] results =  GeocodingApi.geocode(context,
//                "1600 Amphitheatre Parkway Mountain View, CA 94043").await();
//        Gson gson = new GsonBuilder().setPrettyPrinting().create();
//        System.out.println(gson.toJson(results[0].addressComponents));

        /* apparently Geocoder requires a component that isn't available in the
         * core Android framework. if it's missing, Geocoder() just returns an
         * empty list #$^%@!#$%
         * https://stackoverflow.com/questions/15182853/android-geocoder-getfromlocationname-always-returns-null#comment43953176_15236615
         * even worse, most of the time, Geocoder will return an empty list and
         * you have to retry this multiple times. todo: build volley based http url
         * to fetch the address latlng which is 100% reliable
         */
        Geocoder coder = new Geocoder(context, Locale.getDefault());
        List<Address> address;
        LatLng p1 = null;

        try {
            // May throw an IOException
            address = coder.getFromLocationName(strAddress, 5);
            if (address == null || address.size() == 0) {
                Log.e(TAG, "no address found!");
                return null;
            }

            Log.e(TAG, "decoded address length: "+address.size());
            Log.e(TAG, "decoded address: "+address);
            Log.e(TAG, "decoded address: "+address.toString());

            Address location = address.get(0);
            location.getLatitude();
            location.getLongitude();

            p1 = new LatLng(location.getLatitude(), location.getLongitude() );

        } catch (IOException ex) {

            ex.printStackTrace();
        }

        return p1;
    }

    private List<Marker> markerList;

    public void dispatchesLongpressDialog(final DispatchModel dispatch) {
        dispatchLongpressDialog = new Dialog(DispatchesActivity.this);
        dispatchLongpressDialog.setContentView(R.layout.dispatch_longpress_dialog);
        dispatchLongpressDialog.setTitle("Event activity");
        dispatchLongpressDialog.show();

        mapView = (MapView) dispatchLongpressDialog.findViewById(R.id.mapView);
        mapView.onCreate(dispatchLongpressDialog.onSaveInstanceState());
        mapView.onResume();
        mapView.getMapAsync(new OnMapReadyCallback() {
            @SuppressLint("MissingPermission")
            @Override
            public void onMapReady(GoogleMap googleMap) {
                Log.e(TAG, "map is ready");

                markerList = new ArrayList<>();
                LatLng smvfd_station = new LatLng(41.5173067,-72.8293687);
                LatLng river_road = new LatLng(41.520244, -72.834292);

                Marker marker1 = googleMap.addMarker(new MarkerOptions().position(smvfd_station)
                        .title("Station"));
                markerList.add(marker1);

                Marker marker2 = googleMap.addMarker(new MarkerOptions().position(river_road)
                        .title("Lt Freshman"));
                markerList.add(marker2);

                // todo: review and rewrite for this answer: https://stackoverflow.com/a/36282639/1083054
                // this map drawing needs to go into it's own method and cope with async callbacks to update
                // and draw the map. this block needs an onChange type of thing to redraw itself when
                // new things are added to the map
                // when we have the current phone's location, redraw the map and place a driving
                // route from current location to the station
                // IF THE USER selects [enroute] instead of [i'm responding to station], then
                // redraw from current location to the dispatch address instead

                LatLngBounds.Builder builder = new LatLngBounds.Builder();
                builder.include(smvfd_station);
                builder.include(river_road);
                LatLngBounds bounds = builder.build();

                mFusedLocationClient = new FusedLocationProviderClient(DispatchesActivity.this);
//                mFusedLocationClient.setMockMode(true);
//                mFusedLocationClient.setMockLocation(river_road);

//                mFusedLocationClient = LocationServices.getFusedLocationProviderClient();

                mFusedLocationClient.getLastLocation()
                        .addOnSuccessListener(DispatchesActivity.this, new OnSuccessListener<Location>() {
                            @Override
                            public void onSuccess(Location location) {
                                // Got last known location. In some rare situations this can be null.
                                if (location != null) {
                                    Log.e(TAG, "new location is:"+location);
                                } else {
                                    Log.e(TAG, "nope, still no location");
                                }
                            }
                        });


                gmap = googleMap;
                gmap.setMinZoomPreference(12);
//                gmap.moveCamera(CameraUpdateFactory.newLatLng(smvfd_station));
                gmap.getUiSettings().setCompassEnabled(true);
                gmap.getUiSettings().setZoomControlsEnabled(true);

                CameraUpdate cu = CameraUpdateFactory.newLatLngBounds(bounds, 100);
                gmap.moveCamera(cu);

//                gmap.animateCamera(CameraUpdateFactory.zoomTo(14), 500, null);




//                gmap.setOnMyLocationChangeListener(new GoogleMap.OnMyLocationChangeListener() {
//                    @Override
//                    public void onMyLocationChange(Location arg0) {
//                        // TODO Auto-generated method stub
//                        gmap.addMarker(new MarkerOptions().position(new LatLng(arg0.getLatitude(), arg0.getLongitude())).title("It's Me!"));
//                    }
//                });
            }
        });


        // Log.i(TAG, "build dialog for key:"+dispatch.getKey());

        // do onClick events
        final TextView dispatchKey = (TextView) dispatchLongpressDialog.findViewById(R.id.dispatchKey);
        dispatchKey.setText(dispatch.getKey());

        // set checkboxes when we build the dialog interface, must be done
        // after .show() obviously, we simply exit the dialog updater if
        // it isn't showing; aka, on another phone
        statusAdapter.updateDialogFromModel(dispatch.getKey());

        final CheckedTextView mRespondingToStation = (CheckedTextView) dispatchLongpressDialog.findViewById(R.id.responding_to_station);
        final CheckedTextView mEnroute = (CheckedTextView) dispatchLongpressDialog.findViewById(R.id.enroute);
        final CheckedTextView mOnScene = (CheckedTextView) dispatchLongpressDialog.findViewById(R.id.on_scene);
        final CheckedTextView mClearScene = (CheckedTextView) dispatchLongpressDialog.findViewById(R.id.clear_scene);
        final CheckedTextView mInQuarters = (CheckedTextView) dispatchLongpressDialog.findViewById(R.id.in_quarters);

        mRespondingToStation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mRespondingToStation.isChecked()) { // swap states
                    mRespondingToStation.setChecked(true);
                } else {
                    mRespondingToStation.setChecked(false);
                }
                // update our model
                statusAdapter.updateModelFromDialog(dispatchLongpressDialog);
            }
        });

        mEnroute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mEnroute.isChecked()) {
                    mEnroute.setChecked(true);
                } else {
                    mEnroute.setChecked(false);
                }
                // update our model
                statusAdapter.updateModelFromDialog(dispatchLongpressDialog);
            }
        });

        mOnScene.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mOnScene.isChecked()) {
                    mOnScene.setChecked(true);
                } else {
                    mOnScene.setChecked(false);
                }
                // update our model
                statusAdapter.updateModelFromDialog(dispatchLongpressDialog);
            }
        });

        mClearScene.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mClearScene.isChecked()) {
                    mClearScene.setChecked(true);
                } else {
                    mClearScene.setChecked(false);
                }
                // update our model
                statusAdapter.updateModelFromDialog(dispatchLongpressDialog);
            }
        });

        mInQuarters.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mInQuarters.isChecked()) {
                    mInQuarters.setChecked(true);
                } else {
                    mInQuarters.setChecked(false);
                }
                // TODO: need logic to go backwards if user un-marks event
                // update our model
                statusAdapter.updateModelFromDialog(dispatchLongpressDialog);

                //dispatchLongpressDialog.hide();
            }
        });

    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onConnected(@Nullable Bundle bundle) {

        mLastLocation = LocationServices.FusedLocationApi.getLastLocation(
                mGoogleApiClient);
        if (mLastLocation != null) {
            Toast.makeText(this, "Latitude:" + mLastLocation.getLatitude()+", Longitude:"+mLastLocation.getLongitude(),Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "location is null",Toast.LENGTH_LONG).show();
        }

    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    @Override
    public void onResult(@NonNull LocationSettingsResult locationSettingsResult) {
        final Status status = locationSettingsResult.getStatus();
        switch (status.getStatusCode()) {
            case LocationSettingsStatusCodes.SUCCESS:

                // NO need to show the dialog;

                break;

            case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
                //  Location settings are not satisfied. Show the user a dialog

                try {
                    // Show the dialog by calling startResolutionForResult(), and check the result
                    // in onActivityResult().

                    status.startResolutionForResult(DispatchesActivity.this, REQUEST_CHECK_SETTINGS);

                } catch (IntentSender.SendIntentException e) {

                    //failed to show
                }
                break;

            case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
                // Location settings are unavailable so not possible to show any dialog now
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CHECK_SETTINGS) {

            if (resultCode == RESULT_OK) {

                Toast.makeText(getApplicationContext(), "GPS enabled", Toast.LENGTH_LONG).show();
            } else {

                Toast.makeText(getApplicationContext(), "GPS is not enabled", Toast.LENGTH_LONG).show();
            }

        }
    }
}
