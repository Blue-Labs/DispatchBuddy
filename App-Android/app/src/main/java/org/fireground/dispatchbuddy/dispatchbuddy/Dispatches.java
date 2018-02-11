package org.fireground.dispatchbuddy.dispatchbuddy;

import com.google.firebase.database.IgnoreExtraProperties;

import java.sql.Time;
import java.sql.Timestamp;
import java.util.Date;
import java.util.UUID;

/**
 * Created by david on 2/10/18.
 */

@IgnoreExtraProperties
public class Dispatches {
    public Timestamp isotimestamp;
    public Date date_time;
    public UUID event_uuid;
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

    public Dispatches() {
    }

    /* these must match the key:value pairs already in use in the original event.
     * we'll start abstracting this away in the get/set methods
     */
    public Dispatches(Timestamp isotimestamp, Date date_time,
                      UUID event_uuid, String event_status,
                      String owning_city, String owning_station,
                      String nature, String business, String msgtype, String cross,
                      String notes, String address, String units, String incident_number,
                      String report_number, String subdivision, String premise, String ra,
                      String gmapurl, String gmapurldir
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
    }

    public Timestamp getTimestamp() { return isotimestamp; }
    public Date   getDatetime_out() { return date_time; }
    public UUID   getEvent_uuid() { return event_uuid; }
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
}
