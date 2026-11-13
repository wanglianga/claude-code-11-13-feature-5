package com.example.rtbf.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public final class HashUtil {

    private HashUtil() {
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 主体哈希：对归并到同一主体的全部标识符哈希去重排序后再哈希。
     * 姓名不参与计算——姓名相同不构成合并依据。
     */
    public static String subjectHash(List<String> identifierHashes) {
        SortedSet<String> distinct = new TreeSet<>(identifierHashes);
        return sha256(String.join("|", distinct));
    }

    public static String maskPhone(String v) {
        return v == null || v.length() < 7 ? v : v.substring(0, 3) + "****" + v.substring(v.length() - 4);
    }

    public static String maskEmail(String v) {
        if (v == null || !v.contains("@")) {
            return v;
        }
        int at = v.indexOf('@');
        String name = v.substring(0, at);
        String head = name.substring(0, Math.min(2, name.length()));
        return head + "***" + v.substring(at);
    }

    public static String maskIdCard(String v) {
        return v == null || v.length() < 8 ? v : v.substring(0, 4) + "**********" + v.substring(v.length() - 4);
    }
}
