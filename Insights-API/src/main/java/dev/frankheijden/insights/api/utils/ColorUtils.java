package dev.frankheijden.insights.api.utils;

public class ColorUtils {

    private static final String COLOR_CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx";

    private ColorUtils() {}

    /**
     * Colorizes given strings.
     */
    public static String[] colorize(String... strings) {
        String[] colored = new String[strings.length];
        for (int i = 0; i < strings.length; i++) {
            colored[i] = colorize(strings[i]);
        }
        return colored;
    }

    public static String colorize(String color) {
        char[] chars = color.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && COLOR_CODES.indexOf(chars[i + 1]) >= 0) {
                chars[i] = (char) 167;
            }
        }
        return new String(chars);
    }
}
