package org.fireground.dispatchbuddy;

import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * Created by david on 2/21/18.
 *
 * todo: make a volley queue to handle the address to latlng resolution
 *
 */

//public class DataLongOperationAsyncTask extends AsyncTask<String, Void, String[]> {
//    @Override
//    protected void onPreExecute() {
//        super.onPreExecute();
//    }
//
//    @Override
//    protected String[] doInBackground(String... params) {
//        String url = "https://maps.google.com/maps/api/geocode/json?address="
//                + Uri.encode(params[0])
//                + "&sensor=true&key="
//                + R.string.google_android_map_api_key;
//        String response;
//        try {
//            response = getLatLongByURL(url);
//            Log.d("response",""+response);
//            return new String[]{response};
//        } catch (Exception e) {
//            return new String[]{"error"};
//        }
//    }
//
//    @Override
//    protected void onPostExecute(String... result) {
//        try {
//            JSONObject jsonObject = new JSONObject(result[0]);
//
//            double lng = ((JSONArray)jsonObject.get("results")).getJSONObject(0)
//                    .getJSONObject("geometry").getJSONObject("location")
//                    .getDouble("lng");
//
//            double lat = ((JSONArray)jsonObject.get("results")).getJSONObject(0)
//                    .getJSONObject("geometry").getJSONObject("location")
//                    .getDouble("lat");
//
//            Log.d("latitude", "" + lat);
//            Log.d("longitude", "" + lng);
//        } catch (JSONException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private String getLatLongByURL(String url) {
//        RequestQueue queue = Volley.newRequestQueue(DataLongOperationAsyncTask.getApplicationContext());
//        JsonObjectRequest stateReq = new JsonObjectRequest(Request.Method.GET, url,null, new Response.Listener<JSONObject>() {
//            @Override
//            public void onResponse(JSONObject response) {
//                JSONObject location;
//                try {
//                    // Get JSON Array called "results" and then get the 0th
//                    // complete object as JSON
//                    location = response.getJSONArray("results").getJSONObject(0).getJSONObject("geometry").getJSONObject("location");
//                    // Get the value of the attribute whose name is
//                    // "formatted_string"
//                    if (location.getDouble("lat") != 0 && location.getDouble("lng") != 0) {
//                        LatLng latLng = new LatLng(location.getDouble("lat"), location.getDouble("lng"));
//
//                        //
//                    }
//                } catch (JSONException e1) {
//                    e1.printStackTrace();
//
//                }
//            }
//
//        }, new Response.ErrorListener() {
//            @Override
//            public void onErrorResponse(VolleyError error) {
//                Log.d("Error.Response", error.toString());
//            }
//        });
//        // add it to the queue
//        queue.add(stateReq);
//    }
//
//    public String getLatLongByURL2(String requestURL) {
//        URL url;
//        String response = "";
//        try {
//            Log.e("DOAT", "url: "+requestURL);
//            url = new URL(requestURL);
//
//            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
//            conn.setReadTimeout(15000);
//            conn.setConnectTimeout(15000);
//            conn.setRequestMethod("GET");
//            conn.setDoInput(true);
//            conn.setRequestProperty("Content-Type",
//                    "application/x-www-form-urlencoded");
//            conn.setDoOutput(true);
//            int responseCode = conn.getResponseCode();
//            Log.e("DOAT", "http code: "+responseCode);
//
//            if (responseCode == HttpsURLConnection.HTTP_OK) {
//                String line;
//                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
//                while ((line = br.readLine()) != null) {
//                    response += line;
//                    Log.e("DOAT", "appending:"+line);
//                }
//            } else {
//                response = "";
//            }
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        return response;
//    }
//}
