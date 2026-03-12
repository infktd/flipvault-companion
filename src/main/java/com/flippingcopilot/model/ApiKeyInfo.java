package com.flippingcopilot.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApiKeyInfo {
    private String id;
    private String label;

    @SerializedName("bound_to")
    private String boundTo;

    /** Display label for the dropdown — prefer boundTo (OSRS name), fall back to label. */
    public String getDisplayLabel() {
        if (boundTo != null && !boundTo.isEmpty()) return boundTo;
        if (label != null && !label.isEmpty()) return label;
        return "Key " + id.substring(0, 8);
    }
}
