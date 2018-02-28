package org.fireground.dispatchbuddy;

import android.media.Image;
import android.util.Log;

import com.google.firebase.database.IgnoreExtraProperties;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by david on 2/13/18.
 */

@IgnoreExtraProperties
public class ModelPersonnel {
    private String email;
    private String fullName;
    private String firstName;
    private String lastName;
    private String personnelTitle;
    private String profileIcon;
    private Boolean certifiedEms = false;
    private Boolean certifiedFire = false;

    private Boolean canModifyDispatchStatus = false;
    private Boolean canModifyPersonnel = false;
    private Boolean canViewNonPublicInformation = false;

    private String key; // not stored in FB

    /* this isn't used by the adapter, it's here for pretty printing */
    @Override
    public String toString() {
        String r = "{";
        r += "email="+getEmail()+",";
        r += "fullName="+getFullName()+",";
        r += "firstName="+getFirstName()+",";
        r += "lastName="+getLastName()+",";
        r += "profileIcon="+getProfileIcon()+",";
        r += "certifiedEms="+getCertifiedEms()+",";
        r += "certifiedFire="+getCertifiedFire()+",";
        r += "canModifyDispatchStatus="+getCanModifyDispatchStatus()+",";
        r += "canModifyPersonnel="+getCanModifyDispatchStatus()+",";
        r += "canViewNonPublicInformation="+getCanViewNonPublicInformation();
        r += "}";
        return r;
    }

    public ModelPersonnel() {}

    public ModelPersonnel(String email, String fullName, String firstName, String lastName, String personnelTitle,
                          String profileIcon,
                          Boolean certifiedEms, Boolean certifiedFire,
                          Boolean canModifyDispatchStatus, Boolean canModifyPersonnel, Boolean canViewNonPublicInformation
    ) {
        this.email = email;
        this.fullName = fullName;
        this.firstName = firstName;
        this.lastName = lastName;
        this.personnelTitle = personnelTitle;
        this.profileIcon = profileIcon;
        this.certifiedEms = certifiedEms;
        this.certifiedFire = certifiedFire;
        this.canModifyDispatchStatus = canModifyDispatchStatus;
        this.canModifyPersonnel = canModifyPersonnel;
        this.canViewNonPublicInformation = canViewNonPublicInformation;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        lastName = lastName;
    }

    public String getPersonnelTitle() {
        return personnelTitle;
    }

    public void setPersonnelTitle(String personnelTitle) {
        this.personnelTitle = personnelTitle;
    }

    public String getProfileIcon() {
        return profileIcon;
    }

    public void setProfileIcon(String profileIcon) {
        this.profileIcon = profileIcon;
    }

    public Boolean getCertifiedEms() {
        return certifiedEms;
    }

    public void setCertifiedEms(Boolean certifiedEms) {
        this.certifiedEms = certifiedEms;
    }

    public Boolean getCertifiedFire() {
        return certifiedFire;
    }

    public void setCertifiedFire(Boolean certifiedFire) {
        this.certifiedFire = certifiedFire;
    }

    public Boolean getCanModifyDispatchStatus() {
        return canModifyDispatchStatus;
    }

    public void setCanModifyDispatchStatus(Boolean canModifyDispatchStatus) {
        this.canModifyDispatchStatus = canModifyDispatchStatus;
    }

    public Boolean getCanModifyPersonnel() {
        return canModifyPersonnel;
    }

    public void setCanModifyPersonnel(Boolean canModifyPersonnel) {
        this.canModifyPersonnel = canModifyPersonnel;
    }

    public Boolean getCanViewNonPublicInformation() {
        return canViewNonPublicInformation;
    }

    public void setCanViewNonPublicInformation(Boolean canViewNonPublicInformation) {
        this.canViewNonPublicInformation = canViewNonPublicInformation;
    }

    public String getKey() { return key; }

    public void setKey(String key) { this.key = key; }

}
