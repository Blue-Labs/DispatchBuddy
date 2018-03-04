package org.fireground.dispatchbuddy;

import android.view.View;

public interface CustomItemClickListener {
    void onItemClick(View v, int position);
    boolean onItemLongClick(View v, int position);
}