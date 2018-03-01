package org.fireground.dispatchbuddy;

import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
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
    final private DispatchBuddyBase DBB = DispatchBuddyBase.getInstance();

    private List<ModelDispatch> modelDispatchList;
    private String s;
    private Date d;
    private String short_datetime;
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private SimpleDateFormat sdfformatter = new SimpleDateFormat("MMM d''yy\n h:mma");
    CustomItemClickListener listener;

    public AdapterDispatch(List<ModelDispatch> modelDispatchList, CustomItemClickListener listener) {
        this.modelDispatchList = modelDispatchList;
        this.listener = listener;
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

    @Override
    public int getItemCount() {
        return modelDispatchList.size();
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
        ModelDispatch dispatch = modelDispatchList.get(position);

        // use this to store the firebase key?
        holder.item.setTag(dispatch.getKey());

        int apos = holder.getAdapterPosition();
//        Log.i(TAG, "adapter position is set to "+apos+" for "+dispatch.getKey()+"/"+dispatch.getAddress());
        dispatch.setAdapterPosition(holder.getAdapterPosition());
//        Log.i(TAG, "confirming adapter position: "+dispatch.getAdapterPosition());
//        Log.e(TAG, "d:"+dispatch.getKey()+" itemID:"+holder.getItemId());
//        Log.e(TAG, "d:"+dispatch.getKey()+" adapterPos:"+holder.getAdapterPosition());

        // TODO: new items added AFTER initial load have the grayed out background

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

