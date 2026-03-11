package com.flipvault.plugin.controller;

import com.flipvault.plugin.model.SessionStats;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
public class WebhookController {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build();

    public void sendSessionSummary(String webhookUrl, String playerName, SessionStats stats) {
        if (stats == null) {
            return;
        }

        String content = "```"
            + "\nFlipVault Session Summary"
            + "\n─────────────────────────"
            + "\nPlayer:   " + playerName
            + "\nDuration: " + stats.getTimeActive()
            + "\nFlips:    " + stats.getFlipsDone()
            + "\nProfit:   " + stats.getFormattedProfit() + " gp"
            + "\nGP/Hour:  " + formatGpPerHour(stats.getGpPerHour())
            + "\n```";

        String json = "{\"content\":" + escapeJson(content) + "}";

        try {
            Request request = new Request.Builder()
                .url(webhookUrl)
                .post(RequestBody.create(JSON, json))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("Discord webhook returned {}", response.code());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to send Discord webhook: {}", e.getMessage());
        }
    }

    private String formatGpPerHour(long gpPerHour) {
        if (Math.abs(gpPerHour) >= 1_000_000) {
            return String.format("%+.1fM", gpPerHour / 1_000_000.0);
        } else if (Math.abs(gpPerHour) >= 1_000) {
            return String.format("%+.0fK", gpPerHour / 1_000.0);
        }
        return String.format("%+d", gpPerHour);
    }

    private String escapeJson(String value) {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            + "\"";
    }
}
