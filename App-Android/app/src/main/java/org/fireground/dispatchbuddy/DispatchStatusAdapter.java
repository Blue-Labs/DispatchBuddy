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

/**
 * Created by david on 2/13/18.
 */

public class DispatchStatusAdapter {
    private static final String TAG = "DSA";
    private FirebaseAdapter FBA;
    private Context context;

    public DispatchStatusAdapter(Context context){
        this.context = context;
        FirebaseAdapter FBA = new FirebaseAdapter(context);
    }

    private static Boolean isLongPressDispatchDialogShowing() {
        Dialog dref = DispatchesActivity.dispatchLongpressDialog;
        return dref != null;
    }

    public void updateModelFromDialog(Dialog d) {
        if (!isLongPressDispatchDialogShowing()) {
            return;
        }

        final TextView dispatchKey = (TextView) d.findViewById(R.id.dispatchKey);
        String key = dispatchKey.getText().toString();
        DispatchStatusModel model=null;

        for (DispatchStatusModel smodel: DispatchesActivity.dispatch_statuses) {
            if (smodel.getKey().equals(key)) {
                model=smodel;
                break;
            }
        }
        if (model==null) {
//            Log.w("uMFD(d)", "creating new model for key:"+key);
            model = new DispatchStatusModel();
            model.setKey(key);
        }

        final CheckedTextView mResponding = (CheckedTextView) d.findViewById(R.id.responding_to_station);
        final CheckedTextView mEnroute = (CheckedTextView) d.findViewById(R.id.enroute);
        final CheckedTextView mOnScene = (CheckedTextView) d.findViewById(R.id.on_scene);
        final CheckedTextView mClearScene = (CheckedTextView) d.findViewById(R.id.clear_scene);
        final CheckedTextView mInQuarters = (CheckedTextView) d.findViewById(R.id.in_quarters);

        final String person = FBA.getUser();
        final DatabaseReference ref = FBA.getTopPathRef("dispatch-status")
                .child(key)
                .child("responding_personnel");

        if (!mResponding.isChecked()) {
            Log.d(TAG, "responder un-checked");
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
            Log.d(TAG, "responder is checked");
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
                                    Log.e(TAG,"Data could not be saved " + databaseError.getMessage());
                                } else {
                                    Log.e(TAG,"Data saved successfully.");
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

        model.setEn_route(mEnroute.isChecked());
        model.setOn_scene(mOnScene.isChecked());
        model.setClear_scene(mClearScene.isChecked());
        model.setIn_quarters(mInQuarters.isChecked());

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

        // now that all our buttons are updated, update FB
        FBA.getTopPathRef("dispatch-status").child(key).setValue(model);
        Log.d(TAG, "updating dispatch model's status for "+key);
        FBA.getTopPathRef("dispatches").child(key).child("event_status").setValue(status);
    }

    public void updateDialogFromModel(String key) {
        if (!isLongPressDispatchDialogShowing()) {
            return;
        }

        for (DispatchStatusModel model: DispatchesActivity.dispatch_statuses) {
            if (model.getKey().equals(key)) {
                updateDialogFromModel(model);
            }
        }
    }

//    public static void updateDialogFromModel(String key, String checkbox_name, Boolean state) {
//        if (!isLongPressDispatchDialogShowing()) {
//            return;
//        }
//
//        int index = DispatchesActivity.dispatch_statuses.indexOf(key);
//        for (DispatchStatusModel model: DispatchesActivity.dispatch_statuses) {
//            if (model.getKey().equals(key)) {
//                switch (checkbox_name) {
//                    case "en_route":
//                        model.setEn_route(state);
//                        break;
//                    case "on_scene":
//                        model.setOn_scene(state);
//                        break;
//                    case "clear_scene":
//                        model.setClear_scene(state);
//                        break;
//                    case "in_quarters":
//                        model.setIn_quarters(state);
//                        break;
//                    case "responding_personnel":
//                        Log.i("uDFM(knb)", "need to store the responding person");
//                        break;
//                    default:
//                        Log.w("uDFM(knb)", "unknown key sent to updateCheckBox:"+key);
//                        return;
//                }
//                updateDialogFromModel(model);
//                break;
//            }
//        }
//    }

    public void updateDialogFromModel(DispatchStatusModel model) {
        if (!isLongPressDispatchDialogShowing()) {
            return;
        }

        if (model == null) {
//            Log.w("uDFM(m)", "somehow we got a null model");
            return;
        }

        Dialog dref = DispatchesActivity.dispatchLongpressDialog;
        if (dref.isShowing()) {
            final TextView dispatchKey = (TextView) dref.findViewById(R.id.dispatchKey);
            if (model.getKey().equals(dispatchKey.getText())) {
                // TODO: set checkbox for responding
                // TODO: clicking on alternate device with unmatched state, will react opposite as intended
                final CheckedTextView mEnroute = (CheckedTextView) dref.findViewById(R.id.enroute);
                final CheckedTextView mOnScene = (CheckedTextView) dref.findViewById(R.id.on_scene);
                final CheckedTextView mClearScene = (CheckedTextView) dref.findViewById(R.id.clear_scene);
                final CheckedTextView mInQuarters = (CheckedTextView) dref.findViewById(R.id.in_quarters);

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

