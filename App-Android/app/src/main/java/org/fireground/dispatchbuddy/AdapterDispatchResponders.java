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

import com.google.firebase.crash.FirebaseCrash;

import java.util.ArrayList;

import static org.fireground.dispatchbuddy.DispatchBuddyBase.addNullPerson;
import static org.fireground.dispatchbuddy.DispatchBuddyBase.getPerson;

/**
 * Created by david on 2/24/18.
 */

public class AdapterDispatchResponders extends RecyclerView.Adapter<AdapterDispatchResponders.ViewHolderDispatchResponders> {
    final private String TAG = "DA";

    CustomItemClickListener listener;

    SortedList<ModelPersonnel> mData;
    public AdapterDispatchResponders(ArrayList<String> list/*, CustomItemClickListener listener*/) {
//        this.listener = listener;

        mData = new SortedList<ModelPersonnel>(ModelPersonnel.class, new SortedListAdapterCallback<ModelPersonnel>(this) {

            @Override
            public int compare(ModelPersonnel t0, ModelPersonnel t1) {
                if (t0 == null) {
                    return 1;
                }
                if (t1 == null) {
                    return -1;
                }

                if (!t0.getEmail().equals(t1.getEmail())) {
                    return t0.getEmail().compareToIgnoreCase(t1.getEmail());
                }

                if (!t0.getFullName().equals(t1.getFullName())) {
                    return t0.getFullName().compareToIgnoreCase(t1.getFullName());
                }

                return 0;
            }

            @Override
            public boolean areContentsTheSame(ModelPersonnel oldItem,
                                              ModelPersonnel newItem) {
                return oldItem.getEmail().equals(newItem.getEmail()) &&
                        oldItem.getFullName().equals(newItem.getFullName());
            }

            @Override
            public boolean areItemsTheSame(ModelPersonnel item1, ModelPersonnel item2) {
                return item1.getEmail().equals(item2.getEmail());
            }
        });

        for (String e : list) {
            Log.w(TAG, "email is:"+e);
            ModelPersonnel person = getPerson(e);
            if (person == null) {
                Log.e(TAG, "***** missing personnel file for "+e);
                person = addNullPerson(e);
                Log.e(TAG, "created local: "+person);
            }
            mData.add(person);
        }
    }

    @Override
    public int getItemCount() {
        return mData.size();
    }

    public void addItem(ModelPersonnel item) {
        if (item != null) {
            Log.i(TAG, "ADDING personnel person: " + item.getEmail());
            mData.add(item);
        } else {
            Log.w(TAG, "ADD failed, person is null!");
        }
    }

    @Override
    public ViewHolderDispatchResponders onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.dispatch_responders_item, parent, false);

        final ViewHolderDispatchResponders mViewHolder = new ViewHolderDispatchResponders(view);

        FirebaseCrash.log("mViewHolder is: "+mViewHolder.toString());
        FirebaseCrash.log("parent is: "+parent.toString());

        /*
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
                Integer lp = mViewHolder.getLayoutPosition();
                return listener.onItemLongClick(v, lp);
            }
        });
        */

        Log.i(TAG, "dispatch responders onCreateViewHolder created");
        return mViewHolder;
    }

    public static class ViewHolderDispatchResponders extends RecyclerView.ViewHolder {
        ImageView profileIcon;
        TextView personnelFullName;
        TextView personnelTitle;
        TextView personnelEmail;
        ImageView locator;

        View item;

        public ViewHolderDispatchResponders(View itemView) {
            super(itemView);
            item = itemView;
            profileIcon = (ImageView) itemView.findViewById(R.id.profileIcon);
            personnelFullName = (TextView) itemView.findViewById(R.id.personnelFullName);
            personnelTitle = (TextView) itemView.findViewById(R.id.personnelTitle);
            personnelEmail = (TextView) itemView.findViewById(R.id.personnelEmail);
            locator = (ImageView) itemView.findViewById(R.id.locatePerson);
            Log.i("VHDR", "ViewHolder for DispatchResponders created");

            locator.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.i("vhdr", "onClick for "+personnelEmail.getText());
                }
            });
        }

    }

    @Override
    public void onBindViewHolder(ViewHolderDispatchResponders holder, int position) {
        if (mData.get(position) == null) {
            String person = "unknown";
            Log.w(TAG, "cannot determine person for position: "+position);
        } else {
            String person = mData.get(position).getEmail();
            Log.i(TAG, "DRV person is: " + person.toString());
            DispatchBuddyBase.getProfileIcon(DispatchBuddyBase.context, holder.profileIcon, person);
            String fullName = mData.get(position).getFullName();
            if (fullName == null) {
                String f = mData.get(position).getFirstName();
                if (f == null) {
                    f = "";
                }
                String l = mData.get(position).getLastName();
                if (l == null) {
                    l = "";
                }
                fullName = f+" "+l;
            }

            holder.personnelFullName.setText(fullName);
            holder.personnelTitle.setText(mData.get(position).getPersonnelTitle());
            holder.personnelEmail.setText(mData.get(position).getEmail());
        }
    }


}
