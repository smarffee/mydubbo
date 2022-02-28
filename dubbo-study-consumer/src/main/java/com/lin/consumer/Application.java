package com.lin.consumer;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Application {

    public static void main(String[] args) {

        ApplicationContext context =
                new ClassPathXmlApplicationContext("spring-consumer.xml");

        UserAddressService userAddressService = context.getBean(UserAddressService.class);

        User user = new User(2, "张三");

        Address address = userAddressService.queryAddressByUser(user);

        System.out.println("获取到的用户地址是: " + address);

    }

}
