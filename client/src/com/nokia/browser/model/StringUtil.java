package com.nokia.browser.model;

import java.util.Vector;

/**
 * String utility methods compatible with CLDC 1.1 (no String.split or regex).
 */
public class StringUtil {

    public static String[] split(String str, char delimiter) {
        if (str == null) return new String[0];
        Vector list = new Vector();
        int start = 0;
        int len = str.length();
        for (int i = 0; i < len; i++) {
            if (str.charAt(i) == delimiter) {
                list.addElement(str.substring(start, i));
                start = i + 1;
            }
        }
        list.addElement(str.substring(start, len));
        String[] result = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = (String) list.elementAt(i);
        }
        return result;
    }

    public static String replace(String source, String target, String replacement) {
        if (source == null || target == null || target.length() == 0) return source;
        StringBuffer sb = new StringBuffer();
        int start = 0;
        int idx = 0;
        int targetLen = target.length();
        while ((idx = source.indexOf(target, start)) >= 0) {
            sb.append(source.substring(start, idx));
            sb.append(replacement);
            start = idx + targetLen;
        }
        sb.append(source.substring(start));
        return sb.toString();
    }
}
