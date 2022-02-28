package com.lin.producer.impl;

import com.lin.producer.Address;
import com.lin.producer.User;
import com.lin.producer.UserAddressService;
import org.springframework.beans.factory.InitializingBean;

import java.util.HashMap;
import java.util.Map;

public class UserAddressServiceImpl implements UserAddressService, InitializingBean {

    private Map<Integer, Address> addressMap;

    @Override
    public Address queryAddressByUser(User user) {
        System.out.println("根据用户Id查询地址. 用户是: " + user);

        Address address = addressMap.get(user.getUserId());

        System.out.println("查询到的地址是: " + address);

        return address;
    }

    @Override
    public void afterPropertiesSet() throws Exception {

        Map<Integer, Address> addressMap = new HashMap<Integer, Address>();

        addressMap.put(1, new Address(1, "北京昌平区"));
        addressMap.put(1, new Address(2, "北京海淀区"));
        addressMap.put(1, new Address(3, "北京朝阳区"));
        addressMap.put(1, new Address(4, "北京东城区"));
        addressMap.put(1, new Address(5, "北京西城区"));
        addressMap.put(1, new Address(6, "北京丰台区"));

        this.addressMap = addressMap;
    }
}
