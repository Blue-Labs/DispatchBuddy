package org.fireground.dispatchbuddy.dispatchbuddy;

import android.app.Dialog;
import android.util.Log;
import android.widget.CheckedTextView;
import android.widget.TextView;

import com.google.firebase.database.FirebaseDatabase;

import java.util.List;

/**
 * Created by david on 2/13/18.
 */

public class DispatchStatusAdapter {
    private List<DispatchStatusModel> list;

    public DispatchStatusAdapter(List<DispatchStatusModel> list) {
        this.list = list;
    }

    public static void updateModelFromDialog(Dialog d) {
        final TextView dispatchKey = (TextView) d.findViewById(R.id.dispatchKey);
        String key = dispatchKey.getText().toString();
        DispatchStatusModel model=null;

        for (DispatchStatusModel smodel: DispatchesActivity.dispatch_statuses) {
            if (smodel.getKey().equals(key)) {
                Log.i("uMFD(d)", "found model");
                model=smodel;
                break;
            }
        }

        if (model==null) {
            Log.i("uMFD(d)", "creating new model for key:"+key);
            model = new DispatchStatusModel();
            model.setKey(key);
        }

        // TODO: put the responding person in too
        final CheckedTextView mEnroute = (CheckedTextView) d.findViewById(R.id.enroute);
        final CheckedTextView mOnScene = (CheckedTextView) d.findViewById(R.id.on_scene);
        final CheckedTextView mClearScene = (CheckedTextView) d.findViewById(R.id.clear_scene);
        final CheckedTextView mInQuarters = (CheckedTextView) d.findViewById(R.id.in_quarters);
        model.setEn_route(mEnroute.isChecked());
        model.setOn_scene(mOnScene.isChecked());
        model.setClear_scene(mClearScene.isChecked());
        model.setIn_quarters(mInQuarters.isChecked());

        // TODO, concurrency?
        FirebaseDatabase.getInstance().getReference("dispatch-status").child(key).setValue(model);

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

        FirebaseDatabase.getInstance().getReference("dispatches").child(key).child("event_status").setValue(status);
    }

    public static void updateDialogFromModel(String key) {
        for (DispatchStatusModel model: DispatchesActivity.dispatch_statuses) {
            if (model.getKey().equals(key)) {
                Log.i("uDFM(k)", "uC(k)");
                updateDialogFromModel(model);
            }
        }
    }

    public static void updateDialogFromModel(String key, String checkbox_name, Boolean state) {
        int index = DispatchesActivity.dispatch_statuses.indexOf(key);
        Log.i("uDFM(knb)", "looking for key:"+key);
        Log.i("uDFM(knb)", "index of key:"+index);
        for (DispatchStatusModel model: DispatchesActivity.dispatch_statuses) {
            Log.i("uDFM(knb)", "mK:"+model.getKey());
            Log.i("uDFM(knb)", "mK=K:"+model.getKey().equals(key));
            if (model.getKey().equals(key)) {
                Log.i("uDFM(knb)", "updating property:"+checkbox_name);
                switch (checkbox_name) {
                    case "en_route":
                        model.setEn_route(state);
                        break;
                    case "on_scene":
                        model.setOn_scene(state);
                        break;
                    case "clear_scene":
                        model.setClear_scene(state);
                        break;
                    case "in_quarters":
                        model.setIn_quarters(state);
                        break;
                    case "responding_personnel":
                        Log.i("uDFM(knb)", "need to store the responding person");
                        break;
                    default:
                        Log.w("uDFM(knb)", "unknown key sent to updateCheckBox:"+key);
                        return;
                }
                Log.i("uDFM(knb)", "uC(k,n,b)");
                updateDialogFromModel(model);
                break;
            }
        }
    }

    public static void updateDialogFromModel(DispatchStatusModel model) {
        if (model == null) {
            Log.w("uDFM(m)", "somehow we got a null model");
            return;
        }

        Dialog dref = DispatchesActivity.dispatchLongpressDialog;
        Log.i("uDFM(m)", "dref:"+dref);
        if (dref != null) {
            if (dref.isShowing()) {
                final TextView dispatchKey = (TextView) dref.findViewById(R.id.dispatchKey);
                Log.i("uDFM(m)", "dref key is:"+dispatchKey);

                if (model.getKey().equals(dispatchKey.getText()) ) {
                    Log.i("uDFM(m)", "setting checkboxes");
                    final CheckedTextView mEnroute = (CheckedTextView) dref.findViewById(R.id.enroute);
                    final CheckedTextView mOnScene = (CheckedTextView) dref.findViewById(R.id.on_scene);
                    final CheckedTextView mClearScene = (CheckedTextView) dref.findViewById(R.id.clear_scene);
                    final CheckedTextView mInQuarters = (CheckedTextView) dref.findViewById(R.id.in_quarters);

                    Log.i("uDFM(m)", "enroute:"+model.getEn_route());
                    Log.i("uDFM(m)", "onscene:"+model.getOn_scene());
                    Log.i("uDFM(m)", "clear:"+model.getClear_scene());
                    Log.i("uDFM(m)", "quarters:"+model.getIn_quarters());

                    mEnroute.setChecked(model.getEn_route());
                    mOnScene.setChecked(model.getOn_scene());
                    mClearScene.setChecked(model.getClear_scene());
                    mInQuarters.setChecked(model.getIn_quarters());
                } else {
                    Log.i("uDFM(m)", "keys don't match d: "+dispatchKey.getText());
                    Log.i("uDFM(m)", "keys don't match m: "+model.getKey());
                }
            }
        }
    }
}

