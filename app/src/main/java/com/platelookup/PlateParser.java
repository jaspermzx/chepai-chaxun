package com.platelookup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 车牌文本解析：从 OCR 文本里抠出车牌号，并生成 OCR 易混淆字符的候选变体。
 *
 * 车牌规则（民用）：省份简称 + 发牌机关字母 + 5 位序号（7 位），
 * 新能源车牌为 6 位序号（8 位）。这里同时接受 6~8 位，用于容忍漏检。
 */
public final class PlateParser {

    private static final String PROVINCES = "京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领";
    private static final int MIN_LEN = 6;
    private static final int MAX_LEN = 8;
    private static final int MAX_VARIANTS = 40;

    /** 省份简称之后的部分：1 位字母 + 5~6 位字母/数字。 */
    private static final Pattern REST = Pattern.compile("[A-Z][A-Z0-9]{5,6}");

    private PlateParser() {
    }

    /** 统一大小写、去分隔符、全角转半角，只保留车牌可能出现的字符。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 'ａ' && c <= 'ｚ') {
                c = (char) (c - 0xFEE0);
            } else if (c >= 'Ａ' && c <= 'Ｚ') {
                c = (char) (c - 0xFEE0);
            } else if (c >= '０' && c <= '９') {
                c = (char) (c - 0xFEE0);
            }
            if (c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            }
            boolean keep = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || isProvinceChar(c);
            if (keep) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static boolean isProvinceChar(char c) {
        return PROVINCES.indexOf(c) >= 0;
    }

    /** 从一段 OCR 文本中提取全部车牌候选，按出现顺序去重。 */
    public static List<String> extract(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            collect(normalize(line), out);
        }
        // 车牌可能被 OCR 拆成多行，整体再嗅一遍
        collect(normalize(text), out);
        return out;
    }

    private static void collect(String s, List<String> out) {
        for (int i = 0; i < s.length(); i++) {
            if (!isProvinceChar(s.charAt(i))) {
                continue;
            }
            for (int len = MAX_LEN; len >= MIN_LEN; len--) {
                int end = i + len;
                if (end > s.length()) {
                    continue;
                }
                String cand = s.substring(i, end);
                if (REST.matcher(cand.substring(1)).matches()) {
                    out.add(cand);
                    break;
                }
            }
        }
    }

    public static boolean isValid(String plate) {
        if (plate == null) {
            return false;
        }
        String p = normalize(plate);
        if (p.length() < MIN_LEN || p.length() > MAX_LEN) {
            return false;
        }
        return isProvinceChar(p.charAt(0)) && REST.matcher(p.substring(1)).matches();
    }

    /** 展示用：粤B12345 -> 粤B·12345 */
    public static String format(String plate) {
        String p = normalize(plate);
        if (p.length() < 3) {
            return p;
        }
        return p.substring(0, 2) + "·" + p.substring(2);
    }

    /**
     * 生成 OCR 混淆候选：0/O/Q、1/I/L、2/Z、5/S、8/B、6/G 这类互换在实际识别里非常常见。
     * 调用方逐个拿去查库，命中即算识别成功。
     */
    public static List<String> variants(String plate) {
        Set<String> result = new LinkedHashSet<>();
        String base = normalize(plate);
        if (base.isEmpty()) {
            return new ArrayList<>();
        }
        result.add(base);

        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(base);
        int guard = 0;
        while (!queue.isEmpty() && result.size() < MAX_VARIANTS && guard++ < 500) {
            String cur = queue.poll();
            for (int i = 1; i < cur.length(); i++) {
                String alts = alternatives(cur.charAt(i));
                for (int k = 0; k < alts.length(); k++) {
                    char[] chars = cur.toCharArray();
                    chars[i] = alts.charAt(k);
                    String next = new String(chars);
                    if (result.add(next)) {
                        if (result.size() >= MAX_VARIANTS) {
                            return new ArrayList<>(result);
                        }
                        queue.add(next);
                    }
                }
            }
        }
        return new ArrayList<>(result);
    }

    private static String alternatives(char c) {
        switch (c) {
            case '0':
                return "OQD";
            case 'O':
                return "0Q";
            case 'Q':
                return "0";
            case 'D':
                return "0";
            case '1':
                return "IL";
            case 'I':
            case 'L':
                return "1";
            case '2':
                return "Z";
            case 'Z':
                return "2";
            case '5':
                return "S";
            case 'S':
                return "5";
            case '6':
                return "G";
            case 'G':
                return "6";
            case '8':
                return "B";
            case 'B':
                return "8";
            case '4':
                return "A";
            case 'A':
                return "4";
            case '7':
                return "T";
            case 'T':
                return "7";
            default:
                return "";
        }
    }

    /** 手动输入兜底：尽量把用户输入整理成标准形式。 */
    public static String fromUserInput(String raw) {
        return normalize(raw).toUpperCase(Locale.ROOT);
    }
}
