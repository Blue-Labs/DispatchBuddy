package org.fireground.dispatchbuddy.dispatchbuddy;

import android.app.Dialog;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.CheckedTextView;
import android.widget.TextView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by david on 2/11/18.
 *
 * will populate using the xml template dispatch_item.xml
 *
 */

public class DispatchesActivity extends AppCompatActivity {
    private static FirebaseDatabase fbDatabase;
    private FirebaseAuth mAuth;
    private DatabaseReference reference;
    Query ordered_dispatches_query;
    Query dispatch_status_query;

    private RecyclerView recyclerView;
    private List<DispatchModel> dispatches;
    public static List<DispatchStatusModel> dispatch_statuses;
    private DispatchAdapter adapter;

    private TextView emptyText;
    private NotificationUtils mNotificationUtils;
    public static Dialog dispatchLongpressDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // don't save dispatch data
        super.onCreate(null);
        setContentView(R.layout.activity_dispatches);
        Toolbar toolbar = (Toolbar) findViewById(R.id.app_bar);
        setSupportActionBar(toolbar);

        mNotificationUtils = new NotificationUtils(this);

        fbDatabase = FirebaseDatabase.getInstance();
        reference = fbDatabase.getReference();
        ordered_dispatches_query = reference.child("dispatches").orderByChild("isotimestamp");
        dispatch_status_query = reference.child("dispatch-status");

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
                //Log.d("fucknut", "clicked position:" + position);
                Log.d("fucknut", "clicked key:" + postId);
            }
            @Override
            public boolean onItemLongClick(View v, int position) {
                String postId = dispatches.get(position).getKey();
                Log.d("fucknut", "longclicked position:" + position);
                Log.d("fucknut", "longclicked key:" + postId);
                dispatchesLongpressDialog(dispatches.get(position));
                return true;
            }
        });

        recyclerView.setAdapter(adapter);

        setListeners();
        checkIfEmpty();
    }

    @Override
    public void onStart() {
        super.onStart();

        mAuth = FirebaseAuth.getInstance();
        if (mAuth == null) {
            Log.i("ruckfut", "no user, exit dispatches onStart please");
            DispatchesActivity.this.finish();
        }

        Log.i("ruckfut", "still in dispatches onStart");
    }

    @Override
    public void onResume() {
        super.onResume();

        mAuth = FirebaseAuth.getInstance();
        if (mAuth == null) {
            Log.i("ruckfut", "no user, exit dispatches onResume please");
            DispatchesActivity.this.finish();
        }

        Log.i("ruckfut", "still in dispatches onResume");
    }

    private void setListeners() {
        dispatch_status_query.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                DispatchStatusModel model = dataSnapshot.getValue(DispatchStatusModel.class);
                if (model != null) {
                    String key = dataSnapshot.getKey();
                    model.setKey(key);
                    dispatch_statuses.add(model);

                    // update the dialog
                    DispatchStatusAdapter.updateDialogFromModel(model);
                }
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                DispatchStatusModel model = dataSnapshot.getValue(DispatchStatusModel.class);
                if (model != null) {
                    int index = getItemIndex(model);
                    dispatch_statuses.set(index, model);
                    // update the dialog
                    DispatchStatusAdapter.updateDialogFromModel(model);
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

        ordered_dispatches_query.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                DispatchModel model = dataSnapshot.getValue(DispatchModel.class);
                if (model != null) {
                    model.setKey(dataSnapshot.getKey());
                    dispatches.add(model);
                    adapter.notifyDataSetChanged();
                    checkIfEmpty();

                    NotificationCompat.Builder nb = mNotificationUtils.
                            getNotificationBuilder(model.nature, model.address);

                    mNotificationUtils.getManager().notify(101, nb.build());
                }
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                DispatchModel model = dataSnapshot.getValue(DispatchModel.class);

                int index = getItemIndex(model);

                dispatches.set(index, model);
                adapter.notifyItemChanged(index);
            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {
                DispatchModel model = dataSnapshot.getValue(DispatchModel.class);

                int index = getItemIndex(model);

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

    private int getItemIndex(DispatchModel dispatch) {
        int index = -1;

        for (int i=0; i<dispatches.size(); i++) {
            if (dispatches.get(i).isotimestamp.equals(dispatch.isotimestamp)) {
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

    /*        public void onClick(RecyclerView parent, View viewClicked, int position, long id) {
                TextView textView = (TextView) viewClicked;
                String message = "You clicked # " + position
                                + ", which is string: " + textView.getText().toString();
                Toast.makeText(DispatchesActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });*/

    public void dispatchesLongpressDialog(final DispatchModel dispatch) {
        dispatchLongpressDialog = new Dialog(DispatchesActivity.this);
        dispatchLongpressDialog.setContentView(R.layout.dispatch_longpress_dialog);
        dispatchLongpressDialog.setTitle("Event activity");
        dispatchLongpressDialog.show();

        Log.i("", "build dialog for key:"+dispatch.getKey());

        // do onClick events
        final TextView dispatchKey = (TextView) dispatchLongpressDialog.findViewById(R.id.dispatchKey);
        dispatchKey.setText(dispatch.getKey());

        // set checkboxes when we build the dialog interface, must be done
        // after .show() obviously
        DispatchStatusAdapter.updateDialogFromModel(dispatch.getKey());

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
                DispatchStatusAdapter.updateModelFromDialog(dispatchLongpressDialog);
            }
        });

        mEnroute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mEnroute.isChecked()) { // swap states
                    mEnroute.setChecked(true);
                } else {
                    mEnroute.setChecked(false);
                }
                // update our model
                DispatchStatusAdapter.updateModelFromDialog(dispatchLongpressDialog);
            }
        });

        mOnScene.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mOnScene.isChecked()) { // swap states
                    mOnScene.setChecked(true);
                } else {
                    mOnScene.setChecked(false);
                }
                // update our model
                DispatchStatusAdapter.updateModelFromDialog(dispatchLongpressDialog);
            }
        });

        mClearScene.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mClearScene.isChecked()) { // swap states
                    mClearScene.setChecked(true);
                } else {
                    mClearScene.setChecked(false);
                }
                // update our model
                DispatchStatusAdapter.updateModelFromDialog(dispatchLongpressDialog);
            }
        });

        mInQuarters.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mInQuarters.isChecked()) { // swap states
                    mInQuarters.setChecked(true);
                } else {
                    mInQuarters.setChecked(false);
                }
                // TODO: need logic to go backwards if user un-marks event
                // update our model
                DispatchStatusAdapter.updateModelFromDialog(dispatchLongpressDialog);
            }
        });

    }
}
