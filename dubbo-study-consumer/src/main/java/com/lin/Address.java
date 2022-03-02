package com.lin;

import java.io.Serializable;

public class Address implements Serializable {

    private int id;

    private String desc;

    public Address() {
    }

    public Address(int id, String desc) {
        this.id = id;
        this.desc = desc;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    @Override
    public String toString() {
        return "Address{" +
                "id=" + id +
                ", desc='" + desc + '\'' +
                '}';
    }
}
