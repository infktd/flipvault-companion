package com.flippingcopilot.model;

import com.flippingcopilot.controller.Persistance;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SessionManager {

    public static final String SESSION_DATA_FILE_TEMPLATE = "%s_session_data.jsonl";

    private final OsrsLoginManager osrsLoginManager;
    private final ScheduledExecutorService executorService;
    private final Gson gson;

    private final Map<String, SessionData> cachedSessionData =  new HashMap<>();
    private final Map<String, File> displayNameToFile = new HashMap<>();

    private Instant lastSessionUpdateTime;
    private Runnable profitUpdatedCallback = () -> {};

    public synchronized SessionData getCachedSessionData() {
        SessionData sd = getSessionData(osrsLoginManager.getPlayerDisplayName());
        return new SessionData(sd.startTime, sd.durationMillis, sd.averageCash, sd.sessionProfit);
    }

    public synchronized void resetSession() {
        String displayName = osrsLoginManager.getPlayerDisplayName();
        SessionData sd = getSessionData(displayName);
        sd.startTime = (int) Instant.now().getEpochSecond();
        sd.averageCash = 0;
        sd.durationMillis = 0;
        sd.sessionProfit = 0;
        saveAsync(displayName);
    }

    public void setProfitUpdatedCallback(Runnable callback) {
        this.profitUpdatedCallback = callback;
    }

    public synchronized void addSessionProfit(long profit, String displayName) {
        if (displayName == null || profit == 0) return;
        SessionData sd = getSessionData(displayName);
        sd.sessionProfit += profit;
        saveAsync(displayName);
        profitUpdatedCallback.run();
    }

    public synchronized boolean updateSessionStats(boolean currentlyFlipping, long cashStack) {
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if (!currentlyFlipping || displayName == null) {
            lastSessionUpdateTime = null;
            return false;
        } else if (lastSessionUpdateTime == null) {
            lastSessionUpdateTime = Instant.now();
            return false;
        } else {
            SessionData sd = getSessionData(displayName);
            Instant now = Instant.now();
            long duration = Duration.between(lastSessionUpdateTime, now).toMillis();
            long newAverageCashStack = (cashStack * duration + sd.durationMillis * sd.averageCash) / (sd.durationMillis + duration);
            sd.durationMillis = sd.durationMillis + duration;
            lastSessionUpdateTime = now;
            sd.averageCash = newAverageCashStack;
            saveAsync(displayName);
            return true;
        }
    }

    private void saveAsync(String displayName) {
        executorService.submit(() -> {
            File file = getFile(displayName);
            synchronized (file) {
                SessionData data = cachedSessionData.computeIfAbsent(displayName, this::load);
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
                    String json = gson.toJson(data);
                    writer.write(json);
                    writer.newLine();
                } catch (IOException e) {
                    log.warn("error storing session data to file {}", file, e);
                }
            }
        });
    }

    private static final int SESSION_MAX_AGE_SECONDS = 6 * 60 * 60; // 6 hours

    private SessionData freshSession() {
        return new SessionData((int) Instant.now().getEpochSecond(), 0, 0, 0);
    }

    private SessionData load(String displayName) {
        File file = getFile(displayName);
        if (!file.exists()) {
            return freshSession();
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            SessionData sd = gson.fromJson(reader, SessionData.class);
            if (sd != null) {
                int now = (int) Instant.now().getEpochSecond();
                if (now - sd.startTime > SESSION_MAX_AGE_SECONDS) {
                    log.info("session data for {} is stale ({}s old), starting fresh", displayName, now - sd.startTime);
                    return freshSession();
                }
                return sd;
            }
        } catch (JsonSyntaxException | JsonIOException | IOException e) {
            log.warn("error loading session data json file {}", file, e);
        }
        return freshSession();
    }

    private File getFile(String displayName) {
        return displayNameToFile.computeIfAbsent(displayName,
                (k) -> new File(Persistance.FV_DIR, String.format(SESSION_DATA_FILE_TEMPLATE, Persistance.hashDisplayName(displayName))));
    }

    private SessionData getSessionData(String displayName) {
         return cachedSessionData.computeIfAbsent(displayName, this::load);
    }

    public synchronized void reset() {
        lastSessionUpdateTime = null;
    }
}
