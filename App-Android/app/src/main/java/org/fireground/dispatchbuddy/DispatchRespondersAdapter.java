package org.fireground.dispatchbuddy;

import android.content.ClipData;
import android.support.v7.util.SortedList;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.util.SortedListAdapterCallback;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.ParseException;
import java.util.List;

/**
 * Created by david on 2/24/18.
 */

public class DispatchRespondersAdapter extends RecyclerView.Adapter<DispatchRespondersAdapter.DispatchRespondersViewHolder> {
    final private String TAG = "DA";
    final private DispatchBuddyBase DBB = DispatchBuddyBase.getInstance();

    CustomItemClickListener listener;

    SortedList<DispatchResponderModel> mData;
    final LayoutInflater mLayoutInflater;
    public DispatchRespondersAdapter(LayoutInflater layoutInflater, DispatchResponderModel... items) {
        mLayoutInflater = layoutInflater;
        mData = new SortedList<DispatchResponderModel>(DispatchResponderModel.class, new SortedListAdapterCallback<DispatchResponderModel>(this) {
            @Override
            public int compare(DispatchResponderModel t0, DispatchResponderModel t1) {
                if (!t0.getEmail().equals(t1.getEmail())) {
                    return t0.getEmail().compareToIgnoreCase(t1.getEmail());
                }

                if (!t0.getFullName().equals(t1.getFullName())) {
                    return t0.getFullName().compareToIgnoreCase(t1.getFullName());
                }

                return 0;
            }

            @Override
            public boolean areContentsTheSame(DispatchResponderModel oldItem,
                                              DispatchResponderModel newItem) {
                return oldItem.getEmail().equals(newItem.getEmail()) &&
                        oldItem.getFullName().equals(newItem.getFullName());
            }

            @Override
            public boolean areItemsTheSame(DispatchResponderModel item1, DispatchResponderModel item2) {
                return item1.getEmail().equals(item2.getEmail());
            }
        });
        for (DispatchResponderModel item : items) {
            mData.add(item);
        }
    }

    public void addItem(DispatchResponderModel item) {
        mData.add(item);
    }




    // todo: make a POJO dispatchRespondersModel;
//    private List<String> list;
//
//    public DispatchRespondersAdapter(List<String> list, CustomItemClickListener listener) {
//        this.list = list;
//        this.listener = listener;
//    }
//
//    @Override
//    public int getItemCount() {
//        return list.size();
//    }

    @Override
    public DispatchRespondersViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.dispatch_responders_item, parent, false);

        final DispatchRespondersViewHolder mViewHolder = new DispatchRespondersViewHolder(view);

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

    public static class DispatchRespondersViewHolder extends RecyclerView.ViewHolder {
        ImageView profileIcon;
        TextView responderName;
        ImageView locator;

        View item;

        public DispatchRespondersViewHolder(View itemView) {
            super(itemView);
            item = itemView;
            profileIcon = (ImageView) itemView.findViewById(R.id.profileIcon);
            responderName = (TextView) itemView.findViewById(R.id.responderName);
            locator = (ImageView) itemView.findViewById(R.id.locatePerson);
        }

    }

    @Override
    public void onBindViewHolder(DispatchRespondersViewHolder holder, int position) {
        String person = list.get(position);
        Log.i(TAG, "DRV person is: "+person);
        DBB.getProfileIcon(DBB.getAppContext(), holder.profileIcon, person);
        holder.responderName.setText(person);
    }


}
