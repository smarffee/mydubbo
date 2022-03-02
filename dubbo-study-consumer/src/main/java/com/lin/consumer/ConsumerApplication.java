package com.lin.consumer;

import com.lin.Address;
import com.lin.User;
import com.lin.UserAddressService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class ConsumerApplication {

    public static void main(String[] args) {

        ApplicationContext context =
                new ClassPathXmlApplicationContext("spring-consumer.xml");

        UserAddressService userAddressService = context.getBean(UserAddressService.class);

        User user = new User(2, "张三");

        Address address = userAddressService.queryAddressByUser(user);

        System.out.println("获取到的用户地址是: " + address);

    }

}
