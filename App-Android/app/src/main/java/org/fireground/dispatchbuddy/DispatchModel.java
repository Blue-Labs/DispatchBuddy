package org.fireground.dispatchbuddy;

import com.google.firebase.database.IgnoreExtraProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by david on 2/10/18.
 */

@IgnoreExtraProperties
public class DispatchModel {
    public String isotimestamp;
    public String date_time;
    public String event_uuid;
    public String event_status;
    public String owning_city;
    public String owning_station;
    public String nature;
    public String business;
    public String msgtype;
    public String cross;
    public String notes;
    public String address;
    public String units;
    public String incident_number;
    public String report_number;
    public String subdivision;
    public String premise;
    public String ra;
    public String gmapurl;
    public String gmapurldir;
    private int icon_scenario_type; // the image used for the overall scenario for this incident
    private int icon_incident_state; // dispatched, en-route, on-scene, clear-of-scene
    private String key; // Firebase created row key

    public DispatchModel() {}

    /* these must match the key:value pairs already in use in the original event.
     * we'll start abstracting this away in the get/set methods
     */
    public DispatchModel(String isotimestamp, String date_time,
                         String event_uuid, String event_status,
                         String owning_city, String owning_station,
                         String nature, String business, String msgtype, String cross,
                         String notes, String address, String units, String incident_number,
                         String report_number, String subdivision, String premise, String ra,
                         String gmapurl, String gmapurldir, String key
                      ) {
        this.isotimestamp = isotimestamp;
        this.date_time = date_time;
        this.event_uuid = event_uuid;
        this.event_status = event_status;
        this.owning_city = owning_city;
        this.owning_station = owning_station;
        this.nature = nature;
        this.business = business;
        this.msgtype = msgtype;
        this.cross = cross;
        this.notes = notes;
        this.address = address;
        this.units = units;
        this.incident_number = incident_number;
        this.report_number = report_number;
        this.subdivision = subdivision;
        this.premise = premise;
        this.ra = ra;
        this.gmapurl = gmapurl;
        this.gmapurldir = gmapurldir;
        this.key = key;
    }

    public String getTimestamp() { return isotimestamp; }
    public String getDatetime_out() { return date_time; }
    public String getEvent_uuid() { return event_uuid; }
    public String getEvent_status() { return event_status; }
    public String getOwning_city() { return owning_city; }
    public String getOwning_station() { return owning_station; }
    public String getNature() { return nature; }
    public String getBusiness() { return business; }
    public String getMsgtype() { return msgtype; }
    public String getCross() { return cross; }
    public String getNotes() { return notes; }
    public String getAddress() { return address; }
    public String getUnits() { return units; }
    public String getIncident_number() { return incident_number; }
    public String getReport_number() { return report_number; }
    public String getSubdivision() { return subdivision; }
    public String getPremise() { return premise; }
    public String getRa() { return ra; }
    public String getGmap_address() { return gmapurl; }
    public String getGmap_station_to_address() { return gmapurldir; }

    public int getIcon_scenario_type() {
        return icon_scenario_type;
    }

    public int getIcon_incident_state() {
        return icon_incident_state;
    }

    public String getKey() { return key; }


    public void setIsotimestamp(String isotimestamp) {
        this.isotimestamp = isotimestamp;
    }

    public void setDate_time(String date_time) {
        this.date_time = date_time;
    }

    public void setEvent_uuid(String event_uuid) {
        this.event_uuid = event_uuid;
    }

    public void setEvent_status(String event_status) {
        this.event_status = event_status;
    }

    public void setOwning_city(String owning_city) {
        this.owning_city = owning_city;
    }

    public void setOwning_station(String owning_station) {
        this.owning_station = owning_station;
    }

    public void setNature(String nature) {
        this.nature = nature;
    }

    public void setBusiness(String business) {
        this.business = business;
    }

    public void setMsgtype(String msgtype) {
        this.msgtype = msgtype;
    }

    public void setCross(String cross) {
        this.cross = cross;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public void setUnits(String units) {
        this.units = units;
    }

    public void setIncident_number(String incident_number) {
        this.incident_number = incident_number;
    }

    public void setReport_number(String report_number) {
        this.report_number = report_number;
    }

    public void setSubdivision(String subdivision) {
        this.subdivision = subdivision;
    }

    public void setPremise(String premise) {
        this.premise = premise;
    }

    public void setRa(String ra) {
        this.ra = ra;
    }

    public void setGmapurl(String gmapurl) {
        this.gmapurl = gmapurl;
    }

    public void setGmapurldir(String gmapurldir) {
        this.gmapurldir = gmapurldir;
    }

    public void setIcon_scenario_type(int icon_scenario_type) {
        this.icon_scenario_type = icon_scenario_type;
    }

    public void setIcon_incident_state(int icon_incident_state) {
        this.icon_incident_state = icon_incident_state;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Map<String, Object> toMap() {
//        Log.i("DispatchModel", "Map called");
        HashMap<String, Object> result = new HashMap<>();
//        result.put("isotimestamp", isotimestamp);

        return result;
    }
}

