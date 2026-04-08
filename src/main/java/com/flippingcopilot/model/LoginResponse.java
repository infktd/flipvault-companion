package com.flippingcopilot.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponse {
    public String jwt;

    @SerializedName("user_id")
    public int userId;

    public String error;

    @SerializedName("session_token")
    public String sessionToken;

    public List<ApiKeyInfo> keys;

    /** True if this response requires key selection before the plugin can proceed. */
    public boolean needsKeySelection() {
        return (jwt == null || jwt.isEmpty()) && keys != null && !keys.isEmpty();
    }


    private static String readString(DataInputStream s) throws IOException {
        int len = s.readInt();
        if (len < 0) throw new IOException("invalid string length: " + len);
        byte[] bytes = new byte[len];
        s.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static LoginResponse fromRaw(DataInputStream s) {
        try {
            String token = readString(s);
            int userId = s.readInt();
            String error = readString(s);

            // Read key list (may be absent for older servers)
            List<ApiKeyInfo> keyList = null;
            if (s.available() >= 4) {
                int keyCount = s.readInt();
                if (keyCount > 0) {
                    keyList = new ArrayList<>(keyCount);
                    for (int i = 0; i < keyCount; i++) {
                        String id = readString(s);
                        String label = readString(s);
                        String boundTo = readString(s);
                        keyList.add(new ApiKeyInfo(id, label, boundTo.isEmpty() ? null : boundTo));
                    }
                }
            }

            return new LoginResponse(token, userId, error, null, keyList);
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode login response", e);
        }
    }
}
