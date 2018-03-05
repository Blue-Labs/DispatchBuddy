package org.fireground.dispatchbuddy;

import android.support.v7.util.SortedList;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.util.SortedListAdapterCallback;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Created by david on 2/11/18.
 *
 * TODO: we'll have to move sort order into the client, the adapter/recyclerview isn't stuffing them in the middle when a new but older dispatch is inserted
 * TODO: autoscroll the recyclerview with new events/optional/maintain current position
 * TODO: don't push a notification during app startup, that's 10 billion notifications...
 */

public class AdapterDispatch extends RecyclerView.Adapter<AdapterDispatch.ViewHolderDispatch> {
    final private String TAG = "DA";

    private String s;
    private Date d;
    private String short_datetime;
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private SimpleDateFormat sdfformatter = new SimpleDateFormat("MMM d''yy\n h:mma");
    CustomItemClickListener listener;

//    private List<ModelDispatch> modelDispatchList;
    SortedList<ModelDispatch> mData;

    public AdapterDispatch(ArrayList<ModelDispatch> list, CustomItemClickListener listener) {
        this.listener = listener;

        Log.e(TAG, "started up AdapterDispatch, size:"+list.size());

        mData = new SortedList<ModelDispatch>(ModelDispatch.class, new SortedListAdapterCallback<ModelDispatch>(this) {

            @Override
            public int compare(ModelDispatch t0, ModelDispatch t1) {
                if (t0 == null) {
                    return 1;
                }
                if (t1 == null) {
                    return -1;
                }

                if (!t0.getIsotimestamp().equals(t1.getIsotimestamp())) {
                    return t0.getIsotimestamp().compareToIgnoreCase(t1.getIsotimestamp());
                }

                if (!t0.getIncident_number().equals(t1.getIncident_number())) {
                    return t0.getIncident_number().compareToIgnoreCase(t1.getIncident_number());
                }

                if (!t0.getAddress().equals(t1.getAddress())) {
                    return t0.getAddress().compareToIgnoreCase(t1.getAddress());
                }

                return 0;
            }

            // todo: why..
            @Override
            public boolean areContentsTheSame(ModelDispatch oldItem,
                                              ModelDispatch newItem) {
                return oldItem.getIsotimestamp().equals(newItem.getIsotimestamp()) &&
                       oldItem.getIncident_number().equals(newItem.getIncident_number()) &&
                       oldItem.getAddress().equals(newItem.getAddress()
                       );
            }

            @Override
            public boolean areItemsTheSame(ModelDispatch item1, ModelDispatch item2) {
                return item1.getIsotimestamp().equals(item2.getIsotimestamp());
            }
        });

        for (ModelDispatch dispatch : list) {
            Log.i(TAG, "adding dispatch: "+dispatch.getKey());
            mData.add(dispatch);
        }

    }

    @Override
    public int getItemCount() {
        return mData.size();
    }

    @Override
    public ViewHolderDispatch onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.dispatch_item, parent, false);

        final ViewHolderDispatch mViewHolder = new ViewHolderDispatch(view);

        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.e(TAG, "onClick fired for: "+v.getId());
                listener.onItemClick(v, mViewHolder.getLayoutPosition());
            }
        });

        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return listener.onItemLongClick(v, mViewHolder.getLayoutPosition());
            }
        });
        return mViewHolder;
    }

    public static class ViewHolderDispatch extends RecyclerView.ViewHolder {
        ImageView scenario_type;
        TextView firebaseKey, address, timestamp, cross, nature, responderCount;
        View item;

        public ViewHolderDispatch(View itemView) {
            super(itemView);

            item = itemView;
            firebaseKey = (TextView) itemView.findViewById(R.id.firebaseKey);
            scenario_type = (ImageView) itemView.findViewById(R.id.scenario_type);
            address = (TextView) itemView.findViewById(R.id.address);
            timestamp = (TextView) itemView.findViewById(R.id.timestamp);
            cross = (TextView) itemView.findViewById(R.id.cross);
            nature = (TextView) itemView.findViewById(R.id.nature);
            responderCount = (TextView) itemView.findViewById(R.id.respondingPersonnelCount);
        }
    }

    @Override
    public void onBindViewHolder(ViewHolderDispatch holder, int position) {
        ModelDispatch dispatch = mData.get(position);

        // use this to store the firebase key?
        holder.item.setTag(dispatch.getKey());

        dispatch.setAdapterPosition(holder.getAdapterPosition());

        if (dispatch.nature.contentEquals("RESCUE EMS CALL")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_rescue_ems_foreground);
        } else if (dispatch.nature.contentEquals("SPEC RESP CODE GREEN")) {
            holder.scenario_type.setImageResource(R.mipmap.traffic_calmed_response_foreground);
        } else if (dispatch.nature.contentEquals("AUTO ACCID INJ UNKNOWN")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_auto_accid_foreground);
        } else if (dispatch.nature.contentEquals("ALARM CO DETECT NO MED SYMPTOM")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_smoke_detector_foreground);
        } else if (dispatch.nature.contentEquals("ALARM FIRE SOUNDING")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_fire_alarm_sounding_foreground);
        } else if (dispatch.nature.contentEquals("FIRE INSIDE BLDG OR STRUCTURE")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_fire_in_building_foreground);
        } else if (dispatch.nature.contentEquals("WIRES DOWN OR HANGING")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_downed_line_foreground);
        } else if (dispatch.nature.contentEquals("HAZARD GAS OIL SPILL FROM MV")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_fluid_spill_from_motorvehicle_foreground);
        } else if (dispatch.nature.contentEquals("RESCUE AIR BALLOON IN DISTRESS")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_hot_air_balloon_foreground);
        } else if (dispatch.nature.contentEquals("HAZARD GAS ODOR INDOORS")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_hazard_gas_foreground);
        } else if (dispatch.nature.contentEquals("ALARM MASTER BOX")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_fire_alarm_control_panel_foreground);
        } else if (dispatch.nature.contentEquals("ODOR OF SMOKE EXTERIOR")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_odor_of_smoke_foreground);
        } else if (dispatch.nature.contentEquals("ODOR OF SMOKE INTERIOR")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_odor_of_smoke_foreground);
        } else if (dispatch.nature.contentEquals("FIRE IN WOODS xxxxxxxxxxxxxxxxxxx")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_woods_fire_foreground);
        } else if (dispatch.nature.contentEquals("FIRE ILLEGAL BURN")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_illegal_burn_foreground);
        } else if (dispatch.nature.contentEquals("WATER EMERGENCY IN BUILDING")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_water_emergency_foreground);
        } else if (dispatch.nature.contentEquals("FIRE- BOX TRK/TRAC TRLR/TRAIN")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_fire_rescue_foreground);
        } else if (dispatch.nature.contentEquals("MV ACCIDENT INVOLVING BUILDING")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_mv_vs_building_foreground);
        } else {
            //Log.w(TAG, "unknown image type, nature is: " + dispatch.getNature());
            holder.scenario_type.setImageResource(android.R.color.transparent);
        }

        if (dispatch.event_status != null) {
            if (dispatch.event_status.contentEquals("in_quarters")) {
                holder.item.setBackgroundResource(R.drawable.dispatch_itemview_disposed_border);
            } else{
                holder.item.setBackgroundResource(R.drawable.dispatch_itemview_border);
            }
        } else {
            holder.item.setBackgroundResource(R.drawable.dispatch_itemview_border);
        }

        s = dispatch.isotimestamp.toString();

        try {
            d = sdf.parse(s);
        } catch (ParseException e) {
//            Log.e(TAG, e.toString());
        }

        short_datetime = sdfformatter.format(d);

        String rpcount;
        if (dispatch.getRespondingPersonnel() != null) {
            rpcount = String.valueOf(dispatch.getRespondingPersonnel().size());
        } else {
            rpcount = "";
        }

        holder.firebaseKey.setText(dispatch.getKey());
        holder.address.setText(dispatch.address);
        holder.timestamp.setText(short_datetime);
        holder.cross.setText(dispatch.cross);
        holder.nature.setText(dispatch.nature);
        holder.responderCount.setText(rpcount);

    }
}

