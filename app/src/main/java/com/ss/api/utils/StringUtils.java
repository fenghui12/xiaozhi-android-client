package com.ss.api.utils;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StringUtils {
    public StringUtils() {
    }

    public static String intToHexString(int value, int digits) {
        return String.format("%" + digits + "s", Integer.toHexString(value)).replace(' ', '0');
    }

    public static boolean isInteger(String s) {
        String strPattern = "^-?[0-9]+$";
        Pattern p = Pattern.compile(strPattern);
        Matcher m = p.matcher(s);
        return m.matches();
    }

    public static boolean isPositiveInteger(String s) {
        String strPattern = "^[0-9]+$";
        Pattern p = Pattern.compile(strPattern);
        Matcher m = p.matcher(s);
        return m.matches();
    }

    public static boolean isHex(String strHex) {
        String strPattern = "^[a-fA-F0-9]+$";
        Pattern p = Pattern.compile(strPattern);
        Matcher m = p.matcher(strHex);
        return m.matches();
    }

    public static String printHexString(byte[] b) {
        String a = "";

        for(int i = 0; i < b.length; ++i) {
            String hex = Integer.toHexString(b[i] & 255);
            if (hex.length() == 1) {
                hex = '0' + hex;
            }

            a = a + hex;
        }

        return a;
    }

    public static byte[] hexString2Bytes(String src) {
        byte[] ret = new byte[src.length() / 2];
        byte[] tmp = src.getBytes();

        for(int i = 0; i < src.length() / 2; ++i) {
            ret[i] = uniteBytes(tmp[i * 2], tmp[i * 2 + 1]);
        }

        return ret;
    }

    public static byte uniteBytes(byte src0, byte src1) {
        byte _b0 = Byte.decode(String.format("0x%s", new String(new byte[]{src0})));
        _b0 = (byte)(_b0 << 4);
        byte _b1 = Byte.decode(String.format("0x%s", new String(new byte[]{src1})));
        return (byte)(_b0 | _b1);
    }

    public static String bytesToHexString(byte[] src, int size) {
        String ret = "";
        if (src != null && size > 0) {
            for(int i = 0; i < size; ++i) {
                String hex = Integer.toHexString(src[i] & 255);
                if (hex.length() < 2) {
                    hex = "0" + hex;
                }

                ret = ret + hex;
            }

            return ret.toUpperCase();
        } else {
            return null;
        }
    }

    public static String[] matcher(String regex, String input) {
        return matcher(regex, 2, input);
    }

    public static String[] matcher(String regex, int flag, String input) {
        Pattern pattern = Pattern.compile(regex, flag);
        Matcher matcher = pattern.matcher(input);
        ArrayList list = new ArrayList();

        while(matcher.find()) {
            for(int i = 0; i <= matcher.groupCount(); ++i) {
                list.add(matcher.group(i));
            }
        }

        return (String[])list.toArray(new String[0]);
    }

    public static boolean isMatcher(String regex, String input) {
        if (!TextUtils.isEmpty(input) && !TextUtils.isEmpty(regex)) {
            String[] match = matcher(regex, input);
            return match != null && match.length > 0;
        } else {
            return false;
        }
    }
}

