package org.fireground.dispatchbuddy;

import android.app.Dialog;
import android.content.Context;
import android.util.Log;
import android.widget.CheckedTextView;
import android.widget.TextView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

import static org.fireground.dispatchbuddy.DispatchBuddyBase.getTopPathRef;
import static org.fireground.dispatchbuddy.DispatchBuddyBase.getUser;

/**
 * Created by david on 2/13/18.
 */

public class AdapterDispatchStatus {
    private static final String TAG = "DSA";
    private Context context;

    public AdapterDispatchStatus(Context context){
        this.context = context;
    }

    private static Boolean isLongPressDispatchDialogShowing() {
        Dialog dref = ActivityDispatches.dispatchLongpressDialog;
        return dref != null;
    }

    public void updateModelFromDialog(Dialog d) {
        if (!isLongPressDispatchDialogShowing()) {
            return;
        }

        final TextView dispatchKey = (TextView) d.findViewById(R.id.dispatchKey);
        String key = dispatchKey.getText().toString();
        ModelDispatchStatus model=null;

        for (ModelDispatchStatus smodel: ActivityDispatches.dispatch_statuses) {
            if (smodel.getKey().equals(key)) {
                model=smodel;
                break;
            }
        }

        final CheckedTextView mResponding = (CheckedTextView) d.findViewById(R.id.responding_to_station);
        final CheckedTextView mEnroute = (CheckedTextView) d.findViewById(R.id.enroute);
        final CheckedTextView mOnScene = (CheckedTextView) d.findViewById(R.id.on_scene);
        final CheckedTextView mClearScene = (CheckedTextView) d.findViewById(R.id.clear_scene);
        final CheckedTextView mInQuarters = (CheckedTextView) d.findViewById(R.id.in_quarters);

        final String person = getUser();
        final DatabaseReference ref = getTopPathRef("/dispatch-status")
                .child(key)
                .child("responding_personnel");

        if (!mResponding.isChecked()) {
            Log.d(TAG, person+" no longer marked as responding");
            ref.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    for (DataSnapshot item : dataSnapshot.getChildren()) {
                        for (DataSnapshot e : item.getChildren()) {
                            if (e.getValue().equals(person)) {
                                // delete the entry
                                ref.child(item.getKey()).removeValue();
                            }
                        }
                    }
                }

                @Override
                public void onCancelled(DatabaseError databaseError) {
                }
            });
        } else {
            Log.d(TAG, person+" marked as responding");
            ref.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    Boolean found = false;

                    // find existing member
                    for (DataSnapshot item : dataSnapshot.getChildren()) {
                        for (DataSnapshot e : item.getChildren()) {
                            if (e.getValue().equals(person)) {
                                found=true;
                                break;
                            }
                        }
                        if (found) {
                            break;
                        }
                    }

                    if (!found) {
                        // add to responders
                        DatabaseReference newRef = ref.push();
                        String newKey = newRef.getKey();
                        Map<String, Object> u = new HashMap<>();
                        u.put("person", person);
                        newRef.updateChildren(u, new DatabaseReference.CompletionListener() {
                            @Override
                            public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
                                if (databaseError != null) {
                                    Log.e(TAG,person+" is responding, but could not be saved " + databaseError.getMessage());
                                }
                            }
                        });
                    }
                }

                @Override
                public void onCancelled(DatabaseError databaseError) {
                }
            });
        }

        if (model == null) {
            // no status exists yet, make it so
            Map<String, Object> newStatus = new HashMap<>();
            newStatus.put("en_route", mEnroute.isChecked());
            newStatus.put("on_scene", mOnScene.isChecked());
            newStatus.put("clear_scene", mClearScene.isChecked());
            newStatus.put("in_quarters", mInQuarters.isChecked());
            getTopPathRef("/dispatch-status").child((String) dispatchKey.getText()).updateChildren(newStatus, new DatabaseReference.CompletionListener() {
                @Override
                public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
                    if (databaseError != null) {
                        Log.e(TAG,"new model could not be saved " + databaseError.getMessage());
                    }
                }
            });
        } else {
            model.setEn_route(mEnroute.isChecked());
            model.setOn_scene(mOnScene.isChecked());
            model.setClear_scene(mClearScene.isChecked());
            model.setIn_quarters(mInQuarters.isChecked());
            getTopPathRef("/dispatch-status").child(key).setValue(model);
//            DBB.getTopPathRef("/dispatch-status").child(key).updateChildren(model, new DatabaseReference.CompletionListener() {
//                @Override
//                public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
//                    if (databaseError != null) {
//                        Log.e(TAG,"updated model could not be saved " + databaseError.getMessage());
//                    }
//                }
//            });
        }

        // update our dispatches view
        // TODO: put this string builder in the Model
        String status=null;
        if (mInQuarters.isChecked()) {
            status = "in_quarters";
        } else if (mClearScene.isChecked()) {
            status = "clear-scene";
        } else if (mOnScene.isChecked()) {
            status = "on-scene";
        } else if (mEnroute.isChecked()) {
            status = "en-route";
        }

        getTopPathRef("/dispatches").child(key).child("event_status").setValue(status);
    }

    public void updateDialogFromModel(String key) {
        if (!isLongPressDispatchDialogShowing()) {
            return;
        }

        for (ModelDispatchStatus model: ActivityDispatches.dispatch_statuses) {
            if (model.getKey().equals(key)) {
                updateDialogFromModel(model);
            }
        }
    }

    public void updateDialogFromModel(ModelDispatchStatus model) {
        if (!isLongPressDispatchDialogShowing()) {
            return;
        }

        if (model == null) {
            Log.e("uDFM(m)", "somehow we got a null model");
            return;
        }

        final Dialog dref = ActivityDispatches.dispatchLongpressDialog;
        if (dref.isShowing()) {
            final TextView dispatchKey = (TextView) dref.findViewById(R.id.dispatchKey);

            if (model.getKey().equals(dispatchKey.getText())) {
                // TODO: set checkbox for responding
                // TODO: clicking on alternate device with unmatched state, will react opposite as intended
                final CheckedTextView mRespondingToStation = (CheckedTextView) dref.findViewById(R.id.responding_to_station);
                final CheckedTextView mEnroute = (CheckedTextView) dref.findViewById(R.id.enroute);
                final CheckedTextView mOnScene = (CheckedTextView) dref.findViewById(R.id.on_scene);
                final CheckedTextView mClearScene = (CheckedTextView) dref.findViewById(R.id.clear_scene);
                final CheckedTextView mInQuarters = (CheckedTextView) dref.findViewById(R.id.in_quarters);

                Boolean found=false;
                if (model.getResponding_personnel() != null) {
                    for (RespondingPersonnel p : model.getResponding_personnel().values()) {
                        String pshit = p.getPerson();
                        if (p.getPerson().equals(getUser())) {
                            found = true;
                            break;
                        }
                    }
                }
                mRespondingToStation.setChecked(found);
                mEnroute.setChecked(model.getEn_route());
                mOnScene.setChecked(model.getOn_scene());
                mClearScene.setChecked(model.getClear_scene());
                mInQuarters.setChecked(model.getIn_quarters());


            } else {
//                Log.i("uDFM(m)", "keys don't match d: " + dispatchKey.getText());
//                Log.i("uDFM(m)", "keys don't match m: " + model.getKey());
            }
        }
    }
}

