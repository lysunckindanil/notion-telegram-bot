package com.notion.telegrambot.service.tools;

public class ConvertTool {

    public static String REGEX = "/0";

    public static String[] splitStringByRegex(String string) {
        return string.split(REGEX);
    }

    public static String joinArrayByRegex(String[] array) {
        return String.join(REGEX, array);
    }

    public static String addElementToRegexString(String string, String element) {
        if (string.isEmpty()) return element;
        else return string + REGEX + element;
    }

    public static int convertToMinutes(long time) {
        return (int) (time / 60 / 1000);
    }

    public static long convertFromMinutes(int time) {
        return (long) time * 60 * 1000;
    }
}
