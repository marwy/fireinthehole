package org.marwy;

public class PlayerFireData {
    private int streak;          // Текущий стрик (дни подряд)
    private int maxStreak;       // Максимальный достигнутый стрик
    private long lastStreakDate; // Дата последнего полученного стрика (в днях с 1970)
    private long sessionStart;   // Время начала текущей 15-минутной сессии

    public PlayerFireData() {
        this.streak = 0;
        this.maxStreak = 0;
        this.lastStreakDate = 0;
        this.sessionStart = 0;
    }

    public int getStreak() {
        return streak;
    }

    public void setStreak(int streak) {
        this.streak = streak;
        if (streak > maxStreak) {
            maxStreak = streak;
        }
    }

    public int getMaxStreak() {
        return maxStreak;
    }

    public long getLastStreakDate() {
        return lastStreakDate;
    }

    public void setLastStreakDate(long lastStreakDate) {
        this.lastStreakDate = lastStreakDate;
    }

    public long getSessionStart() {
        return sessionStart;
    }

    public void setSessionStart(long sessionStart) {
        this.sessionStart = sessionStart;
    }

    public boolean hasActiveSession() {
        return sessionStart > 0;
    }
} 