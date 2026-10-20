package com.anonusal.zygiskfix.vendor;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.View;


class ViewUtils {
    public static boolean isLayoutRtl(View view) {
        return view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    public static float dpToPx(Context context, int dp) {
        Resources r = context.getResources();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, r.getDisplayMetrics());
    }
}
