package com.platelookup;

/** 一条车辆档案：车牌号 + 车主姓名 + 手机号。 */
public class Vehicle {

    public final String plate;
    public final String owner;
    public final String phone;

    public Vehicle(String plate, String owner, String phone) {
        this.plate = plate;
        this.owner = owner;
        this.phone = phone;
    }
}
