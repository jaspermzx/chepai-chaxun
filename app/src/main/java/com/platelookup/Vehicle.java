package com.platelookup;

/** 一条车辆档案：车牌号 + 车主姓名 + 手机号（手机号可能为空，表示库中没有登记）。 */
public class Vehicle {

    public final String plate;
    public final String owner;
    public final String phone;

    public Vehicle(String plate, String owner, String phone) {
        this.plate = plate;
        this.owner = owner;
        this.phone = phone == null ? "" : phone;
    }

    /** 是否有可用的手机号。历史数据里存在只登记了姓名的记录。 */
    public boolean hasPhone() {
        return phone != null && !phone.trim().isEmpty();
    }
}
