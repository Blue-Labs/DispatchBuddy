package org.fireground.dispatchbuddy.dispatchbuddy;

import com.google.firebase.database.IgnoreExtraProperties;

import java.util.Map;

/**
 * Created by david on 2/13/18.
 */

@IgnoreExtraProperties
public class DispatchStatusModel {
    public String key;
    public Boolean en_route = false;
    public Boolean on_scene = false;
    public Boolean clear_scene = false;
    public Boolean in_quarters = false;
    Map<String, String> responding_personnel;
    Map<String, String> responding_units;

    /* this isn't used by the adapter, it's here for pretty printing */
    @Override
    public String toString() {
        return "dispatch-status{" +
                "en_root='" + en_route + "'" +
                ", on_scene='" + on_scene + "'" +
                ", clear_scene='" + clear_scene + "'" +
                ", in_quarters='" + in_quarters + "'" +
                ", responding_personnel=" + responding_personnel +
                ", responding_units=" + responding_units +
                "}";
    }

    public DispatchStatusModel() {}

    public DispatchStatusModel(String key, Boolean en_route, Boolean on_scene, Boolean clear_scene, Boolean in_quarters) {
        this.key = key;
        this.en_route = en_route;
        this.on_scene = on_scene;
        this.clear_scene = clear_scene;
        this.in_quarters = in_quarters;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Boolean getEn_route() {
        return en_route;
    }

    public void setEn_route(Boolean en_route) {
        this.en_route = en_route;
    }

    public Boolean getOn_scene() {
        return on_scene;
    }

    public void setOn_scene(Boolean on_scene) {
        this.on_scene = on_scene;
    }

    public Boolean getClear_scene() {
        return clear_scene;
    }

    public void setClear_scene(Boolean clear_scene) {
        this.clear_scene = clear_scene;
    }

    public Boolean getIn_quarters() {
        return in_quarters;
    }

    public void setIn_quarters(Boolean in_quarters) {
        this.in_quarters = in_quarters;
    }

    public Map<String, String> getResponding_personnel() {
        return responding_personnel;
    }

    public Map<String, String> getResponding_units() {
        return responding_units;
    }
}
