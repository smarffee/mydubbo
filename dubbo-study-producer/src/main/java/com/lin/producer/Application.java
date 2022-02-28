package com.lin.producer;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.concurrent.TimeUnit;

public class Application {

    public static void main(String[] args) throws InterruptedException {

        ApplicationContext context =
                new ClassPathXmlApplicationContext("spring-producer.xml");

        System.out.println("服务端成功启动");

        Thread.sleep(10000000);
    }

}
