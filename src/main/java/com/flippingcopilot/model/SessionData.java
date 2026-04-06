package com.flippingcopilot.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
public class SessionData {

    @SerializedName("start_time")
    public int startTime;

    @SerializedName("duration_millis")
    public long durationMillis;

    @SerializedName("average_cash")
    public long averageCash;

    @SerializedName("session_profit")
    public long sessionProfit;
}
