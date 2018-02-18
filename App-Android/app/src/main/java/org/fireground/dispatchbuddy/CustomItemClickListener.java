package org.fireground.dispatchbuddy;

import android.view.View;

public interface CustomItemClickListener {
    public void onItemClick(View v, int position);
    public boolean onItemLongClick(View v, int position);
}