package org.fireground.dispatchbuddy;

import android.util.Log;

import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.FirebaseInstanceIdService;

/**
 * Created by david on 2/18/18.
 */


/*
 * test to see if this is really needed since we have the auth changed listener
 * inside DBB now
 */
public class DispatchBuddyFirebaseInstanceIDService extends FirebaseInstanceIdService {
    private DispatchBuddyBase DBB;

    @Override
    public void onTokenRefresh() {
        //registration token
        DBB = DispatchBuddyBase.getInstance();
        String registrationToken = DBB.getRegToken();

        Log.i("DBFIIDS", "storing updated registration token: "+registrationToken);
        DBB.pushFirebaseClientRegistrationData(registrationToken);
    }
}
