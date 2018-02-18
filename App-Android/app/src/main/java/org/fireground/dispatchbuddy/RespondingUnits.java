package org.fireground.dispatchbuddy;

/**
 * Created by david on 2/14/18.
 */

public class RespondingUnits {
    private String unit;

    /* this isn't used by the adapter, it's here for pretty printing */
    @Override
    public String toString() {
        return "{unit='" + unit +"'}";
    }

    public RespondingUnits() {}

    public RespondingUnits(String unit) {
        this.unit = unit;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }
}

