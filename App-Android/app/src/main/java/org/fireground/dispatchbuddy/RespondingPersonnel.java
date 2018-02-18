package org.fireground.dispatchbuddy;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by david on 2/14/18.
 */

public class RespondingPersonnel {
    private String person;

    /* this isn't used by the adapter, it's here for pretty printing */
    @Override
    public String toString() {
        return "{person='" + person +"'}";
    }

    public RespondingPersonnel() {
//        Log.i("RP()", "construct empty");
    }

    public RespondingPersonnel(String person) {
        this.person = person;
//        Log.i("RP()", "construct:"+person);

    }

    public String getPerson() {
//        Log.i("bro", "RP() getPerson");
        return person;
    }

    public void setPerson(String person) {
        this.person = person;
//        Log.i("RP()", "set person:"+person);
    }

    public Map<String, Object> toMap() {
//        Log.w("dsm", "RP toMap");
        HashMap<String, Object> result = new HashMap<>();
        result.put("person", person);
        return result;
    }
}
