package org.fireground.dispatchbuddy.dispatchbuddy;

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
 */

public class DispatchAdapter extends RecyclerView.Adapter<DispatchAdapter.DispatchViewHolder> {
    private List<DispatchModel> list;
    private String s;
    private Date d;
    private String short_datetime;
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private SimpleDateFormat sdfformatter = new SimpleDateFormat("MMM d''yy\n h:ma");
    CustomItemClickListener listener;

    public DispatchAdapter(List<DispatchModel> list, CustomItemClickListener listener) {
        this.list = list;
        this.listener = listener;
    }

    @Override
    public DispatchViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.dispatch_item, parent, false);
        final DispatchViewHolder mViewHolder = new DispatchViewHolder(view);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
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
    public void onBindViewHolder(DispatchViewHolder holder, int position) {

        DispatchModel dispatch = list.get(position);

        Log.i("", "nature is: " + dispatch.getNature());
        //Log.i("FUCK", "key is: " + dispatch.getKey());
        if (dispatch.nature.contentEquals("RESCUE EMS CALL")) {
            holder.scenario_type.setImageResource(R.mipmap.ic_rescue_ems_foreground);
        } else if (dispatch.nature.contentEquals("SPEC RESP CODE GREEN")) {
            holder.scenario_type.setImageResource(R.mipmap.traffic_calmed_response);
        } else {
            holder.scenario_type.setImageResource(android.R.color.transparent);
        }

        // TODO: if the long-press dialog is open, we need to update the status on the checkboxes
        if (dispatch.event_status != null) {
            DispatchStatusAdapter.updateDialogFromModel(dispatch.getKey(), dispatch.event_status.toString(), dispatch.event_status.contentEquals("in_quarters"));
            if (dispatch.event_status.contentEquals("in_quarters")) {
                holder.item.setBackgroundResource(R.drawable.dispatch_itemview_disposed_border);
            } else{
                holder.item.setBackgroundResource(R.drawable.dispatch_itemview_border);
            }
        }

        s = dispatch.isotimestamp.toString();

        try {
            d = sdf.parse(s);
        } catch (ParseException e) {
            Log.e("wrtf", e.toString());
        }

        short_datetime = sdfformatter.format(d);

        holder.address.setText(dispatch.address);
        holder.timestamp.setText(short_datetime);
        holder.cross.setText(dispatch.cross);
        holder.nature.setText(dispatch.nature);
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    public static class DispatchViewHolder extends RecyclerView.ViewHolder {
        ImageView scenario_type;
        TextView address, timestamp, cross, nature;
        View item;

        public DispatchViewHolder(View itemView) {
            super(itemView);
            item = itemView;
            scenario_type = (ImageView) itemView.findViewById(R.id.scenario_type);
            address = (TextView) itemView.findViewById(R.id.address);
            timestamp = (TextView) itemView.findViewById(R.id.timestamp);
            cross = (TextView) itemView.findViewById(R.id.cross);
            nature = (TextView) itemView.findViewById(R.id.nature);
        }
    }
}

