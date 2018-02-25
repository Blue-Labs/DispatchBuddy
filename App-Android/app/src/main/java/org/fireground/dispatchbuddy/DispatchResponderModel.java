package org.fireground.dispatchbuddy;

import android.media.Image;
import android.util.Log;

import com.google.firebase.database.IgnoreExtraProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by david on 2/13/18.
 */

@IgnoreExtraProperties
public class DispatchResponderModel {
    private String email;
    private String fullName;
    private Image profileIcon;
    private Boolean certifiedEms = false;
    private Boolean certifiedFire = false;

    /* this isn't used by the adapter, it's here for pretty printing */
    @Override
    public String toString() {
        return "";
    }

    public DispatchResponderModel() {}

    public DispatchResponderModel(String email, String fullName, Image profileIcon, Boolean certifiedEms, Boolean certifiedFire
    ) {
        this.email = email;
        this.fullName = fullName;
        this.profileIcon = profileIcon;
        this.certifiedEms = certifiedEms;
        this.certifiedFire = certifiedFire;
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

    public Image getProfileIcon() {
        return profileIcon;
    }

    public void setProfileIcon(Image profileIcon) {
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
}
