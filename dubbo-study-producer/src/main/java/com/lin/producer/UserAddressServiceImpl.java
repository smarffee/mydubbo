package com.lin.producer;

import com.lin.Address;
import com.lin.User;
import com.lin.UserAddressService;
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
        addressMap.put(2, new Address(2, "北京海淀区"));
        addressMap.put(3, new Address(3, "北京朝阳区"));
        addressMap.put(4, new Address(4, "北京东城区"));
        addressMap.put(5, new Address(5, "北京西城区"));
        addressMap.put(6, new Address(6, "北京丰台区"));

        this.addressMap = addressMap;
    }
}
