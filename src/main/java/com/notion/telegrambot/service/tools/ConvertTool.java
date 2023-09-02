package com.notion.telegrambot.service.tools;

public class ConvertTool {
    public static long DEFAULT_INTERVAL = convertFromMinutes(45); // 45 minutes by default

    public static int convertToMinutes(long time) {
        return (int) (time / 60 / 1000);
    }

    public static long convertFromMinutes(int time) {
        return (long) time * 60 * 1000;
    }
}
