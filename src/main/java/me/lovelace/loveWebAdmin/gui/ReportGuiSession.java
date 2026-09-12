package me.lovelace.loveWebAdmin.gui;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Сессия заполнения формы жалобы игроком.
 */
public class ReportGuiSession {

    private final UUID reporterUuid;
    private final String reporterName;
    private final String reporterIp;
    private final UUID targetUuid;
    private final String targetName;

    private final Set<String> selectedCauses = new LinkedHashSet<>();
    private String description = null;
    private boolean isRecent = true;
    private boolean awaitingChatInput = false;
    private long lastActiveTime = System.currentTimeMillis();

    public ReportGuiSession(UUID reporterUuid, String reporterName, String reporterIp, UUID targetUuid, String targetName) {
        this.reporterUuid = reporterUuid;
        this.reporterName = reporterName;
        this.reporterIp = reporterIp;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
    }

    public UUID getReporterUuid() {
        return reporterUuid;
    }

    public String getReporterName() {
        return reporterName;
    }

    public String getReporterIp() {
        return reporterIp;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public Set<String> getSelectedCauses() {
        return selectedCauses;
    }

    public boolean toggleCause(String cause) {
        touch();
        if (selectedCauses.contains(cause)) {
            selectedCauses.remove(cause);
            return true;
        }
        if (selectedCauses.size() >= 3) {
            return false;
        }
        selectedCauses.add(cause);
        return true;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        touch();
        this.description = description;
    }

    public boolean isRecent() {
        return isRecent;
    }

    public void setRecent(boolean recent) {
        touch();
        isRecent = recent;
    }

    public boolean isAwaitingChatInput() {
        return awaitingChatInput;
    }

    public void setAwaitingChatInput(boolean awaitingChatInput) {
        touch();
        this.awaitingChatInput = awaitingChatInput;
    }

    public long getLastActiveTime() {
        return lastActiveTime;
    }

    public void touch() {
        this.lastActiveTime = System.currentTimeMillis();
    }
}
