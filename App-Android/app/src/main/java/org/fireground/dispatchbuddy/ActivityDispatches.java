package org.fireground.dispatchbuddy;

import android.*;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckedTextView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.crash.FirebaseCrash;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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

public class ActivityDispatches extends DispatchBuddyBase implements
        OnMapReadyCallback {
    final private String TAG = "DA";
    final private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    Query ordered_dispatches_query;
    Query dispatch_status_query;

    private RecyclerView recyclerView;
    private ArrayList<ModelDispatch> dispatches;
    public static List<ModelDispatchStatus> dispatch_statuses;
    private AdapterDispatch adapter;
    private AdapterDispatchStatus statusAdapter;
    private AdapterDispatchResponders respondersAdapter;

    private TextView emptyText;
    private NotificationUtils mNotificationUtils;
    public static Dialog dispatchLongpressDialog;
    public static Dialog dispatchOnClickDialog;

    private int eventCount = 0;
    private int startupCount = 50;

    protected LocationRequest locationRequest;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // don't save dispatch data, privacy requirement
        super.onCreate(null);
        setContentView(R.layout.activity_dispatches);
        Toolbar toolbar = (Toolbar) findViewById(R.id.app_bar);
        setSupportActionBar(toolbar);

        mNotificationUtils = new NotificationUtils(this);
        Log.d(TAG, "startup with user: " + getUser());
        TextView mLoggedInUser = findViewById(R.id.loggedInUser);
        mLoggedInUser.setText(getUser());

        // subscribe to data-notifications
        subscribeChannel("dispatches");

        ordered_dispatches_query = getTopPathRef("/dispatches").orderByChild("isotimestamp").limitToLast(startupCount);
        dispatch_status_query = getTopPathRef("/dispatch-status");

        emptyText = (TextView) findViewById(R.id.text_no_data);

        dispatches = new ArrayList<>();
        dispatch_statuses = new ArrayList<>();

        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setOrientation(LinearLayoutManager.VERTICAL);
        llm.setReverseLayout(true);
        llm.setStackFromEnd(true);

        recyclerView = (RecyclerView) findViewById(R.id.dispatch_list);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(llm);

        adapter = new AdapterDispatch(dispatches, new CustomItemClickListener() {
            @Override
            public void onItemClick(View v, int position) {
                dispatchesItemOnClick(dispatches.get(position));
            }

            @Override
            public boolean onItemLongClick(View v, int position) {
                dispatchesLongpressDialog(dispatches.get(position));
                return true;
            }
        });

        recyclerView.setAdapter(adapter);

        statusAdapter = new AdapterDispatchStatus(this);

        setListeners();
        checkIfEmpty();

        locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(15 * 60 * 1000); // when idle, every 15 minutes
        locationRequest.setFastestInterval(5 * 1000);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (getUser() == null) {
            ActivityDispatches.this.finish();
        }
    }

    private void setListeners() {
        /*
         * fire off the load for the dispatches.
         * todo: keep an eye out for a future feature of Firebase to support paginated reads
         */
        ordered_dispatches_query.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                ModelDispatch model = dataSnapshot.getValue(ModelDispatch.class);
                if (model != null) {
                    model.setKey(dataSnapshot.getKey());
                    dispatches.add(model);
                    adapter.mData.add(model);
                    Log.i(TAG, "added dispatch model for "+dataSnapshot.getKey());

                    // todo: notify for specific item
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

                        // todo: this shouldn't be necessary any more since we're pushing a Firebase notification
                        /*
                        String message = model.nature;
                        String status = model.event_status;
                        if (status != null) {
                            message += "\n[" + status + "]";
                            mNotificationUtils.sendNotification(message, model.address, priority, model.isotimestamp, false);
                        } else {
                            // create a parent notification as the group summary
                            mNotificationUtils.sendNotification(message, model.address, priority, model.isotimestamp, true);
                        }*/
                    } else {
                        eventCount++;
                    }
                }
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                ModelDispatch model = dataSnapshot.getValue(ModelDispatch.class);

                model.setKey(dataSnapshot.getKey());

                // replace the existing POJO with a new instance. Note, this new POJO doesn't
                // have any local fields :}
                Integer index = getDispatchItemIndex(model);
                ModelDispatch old = dispatches.get(index);

                model.setIcon_scenario_type(old.getIcon_scenario_type());
                model.setIcon_incident_state(old.getIcon_incident_state());
                model.setAdapterPosition(old.getAdapterPosition());
                model.setRespondingPersonnel(old.getRespondingPersonnel());

                Integer p;
                p = model.getAdapterPosition();
                Log.i(TAG, "model position1: "+p);
                dispatches.set(getDispatchItemIndex(model), model);
                adapter.mData.updateItemAt(getDispatchItemIndex(model), model);
                p = model.getAdapterPosition();
                Log.i(TAG, "model position2: "+p);
                adapter.notifyItemChanged(p);

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

                Boolean sendIt=true;
                try {
                    // todo: if timestamp on this event is more than 24hrs, don't make a notification
                    Date d = sdf.parse(model.isotimestamp.toString());
                    Calendar c = Calendar.getInstance();
                    c.add(Calendar.DAY_OF_MONTH, -1);
                    if (c.getTimeInMillis() > d.getTime()) {
                        sendIt = false;
                        Log.d(TAG, "don't send a notification");
                    } else {
                        Log.d(TAG, "send an update notification, priority "+priority);
                    }
                } catch (ParseException e) {
                }

                if (sendIt) {
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
            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {
                ModelDispatch model = dataSnapshot.getValue(ModelDispatch.class);

                // actually, i think this will break as i don't think it will have
                // the adapterPosition field..
                // todo: let's test :-D
                Log.d(TAG, "removing datasnapshot: "+dataSnapshot.toString());
                if (model != null) {
                    Log.d(TAG, "removing model: "+model.toString());

                    Integer pos = model.getAdapterPosition();
                    Log.d(TAG, "removing "+dataSnapshot.getKey()+" at position "+pos);
                    if (pos != null) {
                        adapter.notifyItemRemoved(pos);
                    }
                } else {
                    Log.w(TAG, "model is null");
                }

                dispatches.remove(getDispatchItemIndex(model));
                checkIfEmpty();
                adapter.mData.remove(model);

                // todo: notify for specific item
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
            }
        });

        /*
         * our Status data is intentionally put inside the SingleValueEvent as it is
         * guaranteed to synchronously occur AFTER all the initial ValueEvent data has
         * been read for the dispatches table. this guarantees all of our state regarding
         * dispatches will be correctly applied to the applicable *existing* POJO
         */
        ordered_dispatches_query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
//                Log.d(TAG, "initial data load of dispatches completed");
                adapter.mData.addAll(dispatches);

                dispatch_status_query.addChildEventListener(new ChildEventListener() {
                    @Override
                    public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                        ModelDispatchStatus model = dataSnapshot.getValue(ModelDispatchStatus.class);
                        if (model != null) {
                            final String key = dataSnapshot.getKey();
                            // todo: we shouldn't be doing this, use a better way of tracking the key..duh
                            model.setKey(key);
                            dispatch_statuses.add(model);

//                            Log.e(TAG, "responding personnel for "+key+", "+String.valueOf(model.getResponding_personnel()));
                            if (model.getResponding_personnel() != null) {
//                                Log.e(TAG, "rp size: "+model.getResponding_personnel().size());

                                Boolean found = false;
//                                Log.e(TAG, "dispatches size: "+dispatches.size());
                                for(ModelDispatch d: dispatches) {
//                                    Log.e(TAG, "dm:> "+d.getKey()+", mk:> "+model.getKey());
                                    if (d.getKey().equals(model.getKey())) {
                                        found=true;
                                        // update the responding personnel
//                                        Log.e(TAG, "setting RSPC of "+model.getResponding_personnel().size()+" to "+model.getResponding_personnel() + " for "+model.getKey());
                                        d.setRespondingPersonnel(model.getResponding_personnel());
                                        int index = getDispatchItemIndex(d);
                                        adapter.notifyItemChanged(index);
                                    }
                                }
                                if (found == false) {
                                    Log.e(TAG, "UNABLE TO SET RSP COUNT for "+model.getKey());
                                }
                            }

                            // update the recyclerView

                            // update the dialog
                            statusAdapter.updateDialogFromModel(model);
                        }
                    }

                    @Override
                    public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                        ModelDispatchStatus model = dataSnapshot.getValue(ModelDispatchStatus.class);
                        if (model != null) {
                            final String key = dataSnapshot.getKey();
                            model.setKey(key);

                            int index = getStatusItemIndex(model);
                            if (index < 0) { // our parent node changed, but this child is new
                                dispatch_statuses.add(model);
                            } else {
                                dispatch_statuses.set(index, model);
                            }

                            // todo: onremoved for when it goes to zero responders...
                            Integer msize;
                            String mkey;
                            Map<String, RespondingPersonnel> rp = model.getResponding_personnel();
                            if (model.getResponding_personnel() == null) {
                                msize=0;
                                mkey = null;
                            } else {
                                msize=model.getResponding_personnel().size();
                                mkey = model.getKey();
                            }

                            for(ModelDispatch d: dispatches) {
//                                Log.e(TAG, "dmZ:> "+d.getKey()+" ["+d.getAddress()+"] mk:> "+model.getKey());
                                if (d.getKey().equals(model.getKey())) {
                                    // update the responding personnel
                                    Integer di = d.getAdapterPosition();
                                    FirebaseCrash.log("setting index ["+di+"] RSP count("+msize+") to "+rp + " for "+mkey);
                                    try {
                                        d.setRespondingPersonnel(model.getResponding_personnel());
                                    } catch (NullPointerException e) {
                                        //
                                    }
                                    adapter.notifyItemChanged(di);
                                }
                            }

//                            Log.e(TAG, "(chgZ)responding personnel: "+String.valueOf(model.getResponding_personnel()));
                            if (model.getResponding_personnel() != null) {
//                                Log.e(TAG, "(chgZ)rp size: "+model.getResponding_personnel().size());

                            }

                            // update the recyclerView

                            // update the dialog

                            // update the dialog
                            statusAdapter.updateDialogFromModel(model);
                        }
                    }

                    @Override
                    public void onChildRemoved(DataSnapshot dataSnapshot) {
                        ModelDispatchStatus model = dataSnapshot.getValue(ModelDispatchStatus.class);
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
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
            }
        });



    }

    private int getDispatchItemIndex(ModelDispatch dispatch) {
        int index = -1;

        for (int i = 0; i < dispatches.size(); i++) {
            if (dispatches.get(i).isotimestamp.equals(dispatch.isotimestamp)) {
                index = i;
                break;
            }
        }

        return index;
    }

    private int getStatusItemIndex(ModelDispatchStatus model) {
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

    private ArrayList<Marker> markerList = new ArrayList<Marker>();
    LatLngBounds.Builder builder = new LatLngBounds.Builder();
    LatLngBounds bounds;

    public void createGmap(Dialog dialog) {
        mapView = (MapView) dialog.findViewById(R.id.mapView);
        mapView.onCreate(dialog.onSaveInstanceState());
        mapView.onResume();
        mapView.getMapAsync(this);
    }

    public void addGmapMarker(String inAddress, @Nullable final Map<String, Object> extra) {
        final String address = prepareAddress(inAddress);
        DatabaseReference ref = getTopPathRef("/geocodedAddresses");

        ref.orderByChild("address")
                .equalTo(address)
                .addListenerForSingleValueEvent(
                new ValueEventListener() {

            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                Log.d(TAG, address+" stored: "+dataSnapshot.exists());
                if (!dataSnapshot.exists()) {
                    getLatLng(
                           address,
                            new JsonObjectCallbackInterface() {

                                @Override
                                public void onSuccess(JSONObject response) {
                                    Log.d(TAG, "JSON addr data: "+response);
                                    addGmapMarker(response, extra);
                                }
                            });
                } else {
                    DataSnapshot ds1 = dataSnapshot.getChildren().iterator().next();
                    String key = ds1.getKey();

                    Object ds2 = dataSnapshot.child(key).child("geocoded").getValue();

                    JSONObject jo = null;
                    try {
                        jo = new JSONObject((String) ds2.toString());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    addGmapMarker(jo, extra);
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
            }
        });
    }

    public void addGmapMarker(JSONObject object, @Nullable Map<String, Object> extra) {
        JSONObject loc = null;
        String status = null;
        double lat=0L;
        double lng=0L;

//        Log.d(TAG, "responses: "+object.toString());

        try {
            status = object.getString("status");
            if (!status.equals("OK")) {
                Log.e(TAG, "JSON object has undesired results, status is: "+status);
                Toast.makeText(this, "Failed to fetch Marker LatLng",
                        Toast.LENGTH_LONG).show();
                return;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        try {
            loc = object.getJSONArray("results")
                    .getJSONObject(0)
                    .getJSONObject("geometry")
                    .getJSONObject("location");

            lat = loc.getDouble("lat");
            lng = loc.getDouble("lng");
        } catch (JSONException e) {
            e.printStackTrace();
        }

        LatLng marker_ll = new LatLng(lat,lng);
        MarkerOptions options = new MarkerOptions();
        options.position(marker_ll);

        if (extra.get("type") == "primary station") {
            options.icon(BitmapDescriptorFactory.fromResource(R.drawable.gmap_icon_firestation_40x30));
        }
        if (extra.get("title")!=null) {
            options.title((String) extra.get("title"));
        } else {
            try {
                options.title(object
                        .getJSONArray("results")
                        .getJSONObject(0)
                        .getString("formatted_address"));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        Marker marker = gmap.addMarker(options);

        // store our markers, add the lat/lng data, and rebuild the map geofence
        markerList.add(marker);

        // if both markers have been added, fetch the directions. #0 should be the station
        // todo: there'll be a lot more markers eventually...
        if (markerList.size() == 2) {
            Log.d(TAG, "getting directions");
            LatLng origin = (LatLng) ((Marker) markerList.get(1)).getPosition();
            LatLng destination = (LatLng) ((Marker) markerList.get(0)).getPosition();
            getGmapDirections(origin, destination);
        }

        builder.include(marker.getPosition());
        bounds = builder.build();

        Log.i(TAG, "bounds are: "+bounds);
        CameraUpdate cu = CameraUpdateFactory.newLatLngBounds(bounds, 100);
        gmap.moveCamera(cu);

        try {
            Log.d(TAG, "marker added for: "+object
                    .getJSONArray("results")
                    .getJSONObject(0)
                    .getString("formatted_address")
                    +" lat/lng: "+lat+", "+lng);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void getGmapDirections(LatLng origin, LatLng destination) {
        Log.d(TAG, "fetching Gmap drive directions");
        getGmapDirectionsJson(
                origin,
                destination,
                new JsonObjectCallbackInterface() {
                    List<List<HashMap<String, String>>> routes = null;

                    @Override
                    public void onSuccess(JSONObject response) {
//                        Log.d(TAG, "gmap driving directions: "+response);
                        Log.d(TAG, "gmap driving directions obtained");
                        extractGmapAPIDriveRoutes zpop = new extractGmapAPIDriveRoutes();
                        routes = zpop.parse(response);
//                        Log.d(TAG, "drive routes: "+routes);

                        ArrayList<LatLng> points = null;
                        PolylineOptions lineOptions = null;
                        MarkerOptions markerOptions = new MarkerOptions();

                        // Traversing through all the routes
                        for(int i=0;i<routes.size();i++){
                            points = new ArrayList<LatLng>();
                            lineOptions = new PolylineOptions();

                            // Fetching i-th route
                            List<HashMap<String, String>> path = routes.get(i);

                            // Fetching all the points in i-th route
                            for(int j=0;j<path.size();j++){
                                HashMap<String,String> point = path.get(j);

                                double lat = Double.parseDouble(point.get("lat"));
                                double lng = Double.parseDouble(point.get("lng"));
                                LatLng position = new LatLng(lat, lng);

                                points.add(position);
                                builder.include(position);
                            }

                            // Adding all the points in the route to LineOptions
                            lineOptions.addAll(points);
                            lineOptions.width(8);
                            lineOptions.color(Color.RED);
                        }

                        // Drawing polyline in the Google Map for the i-th route
                        if (lineOptions != null) {
                            gmap.addPolyline(lineOptions);
                            Log.d(TAG, "polylines added to map");
                        } else {
                            Log.e(TAG, "failed to extract polyline directions");
                        }

                    }
                });
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onMapReady(GoogleMap googleMap) {
        gmap = googleMap;

        // todo: need to account for relocation and staging for units on the move

        Map<String, Object> extra = new HashMap<>();
        extra.put("type", "primary station");
        extra.put("title", "SMVFD");
        addGmapMarker("31 Camp St Meriden CT 06451", extra);

//        LatLng smvfd_station = new LatLng(41.5173067,-72.8293687);
//
//        Marker marker1 = googleMap.addMarker(new MarkerOptions().position(smvfd_station)
//                .title("Station"));
//        markerList.add(marker1);

        // todo: directions plot: https://maps.googleapis.com/maps/api/directions/json?origin=41.5233122,-72.8444629&destination=41.5172841,-72.8292187&key=AIzaSyAXexsV69LPaoBxE5gMPpnHS_VY7Ydm7lA


        // this map drawing needs to go into it's own method and cope with async callbacks to update
        // and draw the map. this block needs an onChange type of thing to redraw itself when
        // new things are added to the map
        // when we have the current phone's location, redraw the map and place a driving
        // route from current location to the station
        // IF THE USER selects [enroute] instead of [i'm responding to station], then
        // redraw from current location to the dispatch address instead

        if (!hasPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            Toast.makeText(this, "Location functions not available, Location permission was turned off", Toast.LENGTH_LONG).show();
        } else {
            mFusedLocationClient = new FusedLocationProviderClient(ActivityDispatches.this);
//                mFusedLocationClient = LocationServices.getFusedLocationProviderClient();

            mFusedLocationClient.getLastLocation()
                    .addOnSuccessListener(ActivityDispatches.this, new OnSuccessListener<Location>() {
                        @Override
                        public void onSuccess(Location location) {
                            // Got last known location. In some rare situations this can be null.
                            if (location != null) {
                                Log.e(TAG, "new location is:"+location);
                            } else {
                                Log.e(TAG, "nope, still no location");
                            }
                        }
                    })
                    .addOnFailureListener(this, new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            Log.e(TAG, "failed to get location data: "
                                    +e.getLocalizedMessage());
                        }
                    });
        }

        gmap = googleMap;
        gmap.setMinZoomPreference(5);
//      gmap.moveCamera(CameraUpdateFactory.newLatLng(smvfd_station));
        gmap.getUiSettings().setCompassEnabled(true);
        gmap.getUiSettings().setZoomControlsEnabled(true);

//                            gmap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0));
//
//                            Point mappoint = gmap.getProjection().toScreenLocation(bounds.getCenter());
//                            gmap.moveCamera(CameraUpdateFactory.newLatLng(gmap.getProjection().fromScreenLocation(mappoint)));

        if (bounds != null) {
            // todo: need a map listener to do camera updates when markers and routes are added
            gmap.moveCamera(CameraUpdateFactory.newLatLngZoom(bounds.getCenter(), 20));

            CameraUpdate cu = CameraUpdateFactory.newLatLngBounds(bounds, 100);
            gmap.moveCamera(cu);
        }
//                gmap.animateCamera(CameraUpdateFactory.zoomTo(14), 500, null);
                  // todo: wrap this in location permission
//                gmap.setOnMyLocationChangeListener(new GoogleMap.OnMyLocationChangeListener() {
//                    @Override
//                    public void onMyLocationChange(Location arg0) {
//                        // TODO Auto-generated method stub
//                        gmap.addMarker(new MarkerOptions().position(new LatLng(arg0.getLatitude(), arg0.getLongitude())).title("Me"));
//                    }
//                });
    }

    /*
     * Show a simple dialog with a listview that has all the responders listed
     * todo: add a <locate> icon on each item that lets others see their current
     * position on the map, possibly a distance-from-station
     * perhaps a progress bar indicating distance from station
     *
     * todo: need a table that has k:v for firefighters and not use their login email, e.g.:
     *   Proper Name
     *   Certified EMS
     *   Certified Fires
     *
     * todo: learn this Map.Entry.. entrySet, see https://stackoverflow.com/questions/5826384/java-iteration-through-a-hashmap-which-is-more-efficient
     */
    public void dispatchesItemOnClick(final ModelDispatch dispatch) {
        if (dispatch.getRespondingPersonnel().size() == 0) {
            Log.d(TAG, "no responders for "+dispatch.getAddress());
            Toast.makeText(this, "no responders for: "+dispatch.getAddress(), Toast.LENGTH_SHORT).show();
            return;
        }

        dispatchOnClickDialog = new Dialog(this);
        dispatchOnClickDialog.setContentView(R.layout.dispatch_responders_dialog);
        dispatchOnClickDialog.setTitle("Responders");
        dispatchOnClickDialog.show();
        dispatchOnClickDialog.setCanceledOnTouchOutside(true);
        dispatchOnClickDialog.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));


        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setOrientation(LinearLayoutManager.VERTICAL);

        // private view, only valid for life of this dialog
        RecyclerView rv = (RecyclerView) dispatchOnClickDialog.findViewById(R.id.respondingPersonnelListView);
        rv.setHasFixedSize(true);
        rv.setLayoutManager(llm);

        // stop using this. this is a list of email addresses. we want to connect to our live
        // firebase personnel table, and the dispatch model's Responders. this way live
        // updates will happen
        //
        // this --v  is a static list, built everytime a click is observed. :-(
        final ArrayList<String> list = new ArrayList<>();
        for(Map.Entry<String, RespondingPersonnel> entry: dispatch.getRespondingPersonnel().entrySet()) {
            Log.e(TAG, dispatch.getAddress()+" responder: "+String.valueOf(entry.getValue()));
            list.add(String.valueOf(entry.getValue()));
        }

        respondersAdapter = new AdapterDispatchResponders(
                (ArrayList<String>) list/*,
                new CustomItemClickListener() {
            @Override
            public void onItemClick(View v, int position) {
//                dispatchResponderOnClick(list.get(position));
            }

            @Override
            public boolean onItemLongClick(View v, int position) {
//                dispatchesLongpressDialog(dispatches.get(position));
                return true;
            }
        }*/);

        rv.setAdapter(respondersAdapter);
    }

    public void dispatchResponderOnClick(final ModelPersonnel model) {

    }

    // todo: some phones show this dialog with very dimmed for the map, the "fix" will take a lot
    // of work, basically making a new activity with a transparent background to emulate a dialog
    // https://github.com/mapbox/mapbox-gl-native/issues/6101
    public void dispatchesLongpressDialog(final ModelDispatch dispatch) {
        dispatchLongpressDialog = new Dialog(this);
        dispatchLongpressDialog.setContentView(R.layout.dispatch_longpress_dialog);
        dispatchLongpressDialog.setTitle("Event activity");
        dispatchLongpressDialog.show();

        // some phones have a really broken mapview implementation that puts the map _below_ the dimmed surfaceview
        if (android.os.Build.VERSION.RELEASE.startsWith("6.0")) {
            Log.w(TAG, "Broken MapView Surface artifacts, undoing DIM");
            dispatchLongpressDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        FirebaseCrash.logcat(Log.INFO, TAG, "dialog created");

        markerList.clear();

        if (!isGoogleApiServicesGood(this)) {
            Toast.makeText(this, "Google API services not available", Toast.LENGTH_SHORT).show();
        } else {
            createGmap(dispatchLongpressDialog);

            // hardwire for testing

            Map<String, Object> extra = new HashMap<>();
            extra.put("type", "incident location");

            // todo: we need the zip code, this can probably go in a Firebase Config object
            String city = dispatch.getOwning_city();

            if (city == null) { city = dispatch.getCity(); }
            if (city == null) { city = "Meriden CT 06451"; }
            if (!city.endsWith(" [\\d-]{5,10}")) {
                city += " 06451";
            }

            addGmapMarker(dispatch.getAddress()+", "+city, extra);
        }

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
}
