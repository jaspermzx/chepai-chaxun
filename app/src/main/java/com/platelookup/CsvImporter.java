package com.platelookup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 文本解析：每行「车牌号,姓名[,手机号]」，分隔符支持逗号/制表符/分号/顿号。
 *
 * 第二版起按「宁多勿少」处理：
 * - 手机号可以没有（只有两列也能导入），缺手机号不算错误；
 * - 车牌单元格里混进了序号、空格、书名号等杂物时自动剥离出车牌，不再整行丢弃；
 * - 同一车牌出现多次以最后一行为准，并在结果里区分新增与更新。
 * 只有「认不出车牌」或「没有姓名」的行才会被跳过，且跳过原因会返回给调用方。
 */
public final class CsvImporter {

    /** 解析结果：条目 + 统计信息。 */
    public static class Result {
        public final List<Vehicle> items = new ArrayList<>();
        public int noPhoneCount;
        public int skippedCount;
        public final List<String> skippedSamples = new ArrayList<>();

        public int duplicateCount;
    }

    private CsvImporter() {
    }

    public static Result parse(String content) {
        Result result = new Result();
        if (content == null) {
            return result;
        }
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        // 车牌 -> 记录；同一车牌重复出现时后者覆盖前者
        Map<String, Vehicle> byPlate = new LinkedHashMap<>();
        String[] lines = content.split("\\r?\\n");
        boolean firstLine = true;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.charAt(0) == '\uFEFF') {
                line = line.substring(1).trim();
                if (line.isEmpty()) {
                    continue;
                }
            }
            String[] cols = split(line);
            String plateCell = cols.length > 0 ? cols[0] : "";
            String ownerCell = cols.length > 1 ? cols[1].trim() : "";
            String phoneCell = cols.length > 2 ? cols[2].trim() : "";

            if (firstLine) {
                firstLine = false;
                if (isHeader(cols)) {
                    continue;
                }
            }

            String plate = pickPlate(plateCell);
            if (plate == null || ownerCell.isEmpty()) {
                result.skippedCount++;
                if (result.skippedSamples.size() < 3) {
                    result.skippedSamples.add(line);
                }
                continue;
            }

            String phone = cleanPhone(phoneCell);
            Vehicle vehicle = new Vehicle(plate, ownerCell, phone);
            if (byPlate.put(plate, vehicle) != null) {
                result.duplicateCount++;
            }
        }

        result.items.addAll(byPlate.values());
        for (Vehicle v : result.items) {
            if (!v.hasPhone()) {
                result.noPhoneCount++;
            }
        }
        return result;
    }

    /** 支持带引号的单元格，避免「"张伟,236"」这种含逗号的姓名被切碎。 */
    private static String[] split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' || c == '“' || c == '”') {
                quoted = !quoted;
                continue;
            }
            boolean isSep = c == ',' || c == '，' || c == '\t' || c == ';' || c == '；' || c == '、';
            if (!quoted && isSep) {
                out.add(sb.toString());
                sb.setLength(0);
                continue;
            }
            sb.append(c);
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }

    /** 从脏单元格里抠车牌：优先按车牌规则提取，其次退回整体归一化。 */
    private static String pickPlate(String cell) {
        if (cell == null) {
            return null;
        }
        String trimmed = cell.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        List<String> found = PlateParser.extract(trimmed);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        String normalized = PlateParser.fromUserInput(trimmed);
        return PlateParser.isValid(normalized) ? normalized : null;
    }

    /** 手机号清洗：只留数字；「无」「-」「/」这类占位符一律当作没有手机号。 */
    public static String cleanPhone(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        String digits = sb.toString();
        // 少于 6 位基本是「无」「未知」之类的占位，不当手机号
        return digits.length() < 6 ? "" : digits;
    }

    private static final String[] HEADER_WORDS = {
            "车牌", "号牌", "姓名", "车主", "电话", "手机", "联系方式",
            "plate", "name", "phone", "owner"
    };

    private static boolean isHeader(String[] cols) {
        StringBuilder joined = new StringBuilder();
        for (String c : cols) {
            joined.append(c);
        }
        String s = joined.toString().toLowerCase();
        boolean hit = false;
        for (String w : HEADER_WORDS) {
            if (s.contains(w)) {
                hit = true;
                break;
            }
        }
        // 表头不含任何车牌字符，正文首行一般会含省份简称
        return hit && PlateParser.extract(joined.toString()).isEmpty();
    }
}
