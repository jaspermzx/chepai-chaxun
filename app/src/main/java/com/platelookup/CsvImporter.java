package com.platelookup;

import java.util.ArrayList;
import java.util.List;

/** CSV 文本解析：每行「车牌号,姓名,手机号」，分隔符支持逗号/制表符/分号/顿号。 */
public final class CsvImporter {

    private CsvImporter() {
    }

    public static List<Vehicle> parse(String content) {
        List<Vehicle> out = new ArrayList<>();
        if (content == null) {
            return out;
        }
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        String[] lines = content.split("\\r?\\n");
        boolean firstLine = true;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("[,，\\t;；、]");
            if (cols.length < 3) {
                firstLine = false;
                continue;
            }
            String plate = PlateParser.fromUserInput(cols[0]);
            String owner = cols[1].trim();
            String phone = cols[2].trim();
            if (firstLine && isHeader(plate, owner, phone)) {
                firstLine = false;
                continue;
            }
            firstLine = false;
            if (!PlateParser.isValid(plate) || owner.isEmpty() || phone.isEmpty()) {
                continue;
            }
            out.add(new Vehicle(plate, owner, phone));
        }
        return out;
    }

    private static boolean isHeader(String plate, String owner, String phone) {
        String joined = plate + owner + phone;
        return joined.contains("车牌") || joined.contains("姓名") || joined.contains("电话")
                || plate.toUpperCase().contains("PLATE");
    }
}
