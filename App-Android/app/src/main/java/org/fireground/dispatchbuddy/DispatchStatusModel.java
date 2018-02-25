package org.fireground.dispatchbuddy;

import android.util.Log;

import com.google.firebase.database.IgnoreExtraProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by david on 2/13/18.
 */

@IgnoreExtraProperties
public class DispatchStatusModel {
    public String key;
    private Boolean en_route = false;
    private Boolean on_scene = false;
    private Boolean clear_scene = false;
    private Boolean in_quarters = false;

    Map<String, RespondingPersonnel> responding_personnel;
    Map<String, RespondingUnits> responding_units;

    /* this isn't used by the adapter, it's here for pretty printing */
    @Override
    public String toString() {
        return "dispatch-status{" +
                "en_root='" + en_route + "'" +
                ", on_scene='" + on_scene + "'" +
                ", clear_scene='" + clear_scene + "'" +
                ", in_quarters='" + in_quarters + "'" +
                ", responding_personnel:" + responding_personnel +
                ", responding_units:" + responding_units +
                "}";
    }

    public DispatchStatusModel() {}

    public DispatchStatusModel(String key, Boolean en_route, Boolean on_scene, Boolean clear_scene, Boolean in_quarters
                                , Map<String, RespondingPersonnel> responding_personnel
                                , Map<String, RespondingUnits> responding_units
                              ) {
        this.key = key;
        this.en_route = en_route;
        this.on_scene = on_scene;
        this.clear_scene = clear_scene;
        this.in_quarters = in_quarters;
        this.responding_personnel = responding_personnel;
        this.responding_units = responding_units;
    }

    public String getKey() {
        return key;
    }
    public Boolean getEn_route() {
        return en_route;
    }
    public Boolean getOn_scene() {
        return on_scene;
    }
    public Boolean getClear_scene() {
        return clear_scene;
    }
    public Boolean getIn_quarters() {
        return in_quarters;
    }

    public void setKey(String key) {
        this.key = key;
    }
    public void setEn_route(Boolean en_route) {
        this.en_route = en_route;
    }
    public void setOn_scene(Boolean on_scene) {
        this.on_scene = on_scene;
    }
    public void setClear_scene(Boolean clear_scene) {
        this.clear_scene = clear_scene;
    }
    public void setIn_quarters(Boolean in_quarters) {
        this.in_quarters = in_quarters;
    }

    public Map<String, RespondingPersonnel> getResponding_personnel() {
        return responding_personnel;
    }
    public Map<String, RespondingUnits> getResponding_units() {
        return responding_units;
    }

    public void setResponding_personnel(Map<String, RespondingPersonnel> responding_personnel) {
        this.responding_personnel = responding_personnel;
    }

    public void setResponding_units(Map<String, RespondingUnits> responding_units) {
        this.responding_units = responding_units;
    }

//    public Map<String, Object> toMap() {
//        Log.e("dsm", "DSM toMap");
//        HashMap<String, Object> result = new HashMap<String, Object>();
//
//        result.put("key", key);
//        result.put("en_route", en_route);
//        result.put("on_scene", on_scene);
//        result.put("clear_scene", clear_scene);
//        result.put("in_quarters", in_quarters);
//        result.put("responding_personnel", responding_personnel);
//        result.put("responding_units", responding_units);
//
//        return result;
//    }
}
