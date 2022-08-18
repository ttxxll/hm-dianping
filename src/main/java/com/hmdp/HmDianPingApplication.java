package com.hmdp;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

// 暴露代理对：后面AopContext.currentProxy();就能获得当前类的代理对象
@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.hmdp.mapper")
@SpringBootApplication
@Slf4j
public class HmDianPingApplication {

    public static void main(String[] args) {

//        Thread thread = new Thread("thread_name1") {
//            @Override
//            public void run() {
//                try {
//                    log.info("Thread-Name: {}", Thread.currentThread().getName());
//                    Thread.sleep(10000L);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//            }
//        };
//
//        Thread thread1 = new Thread("thread_name1") {
//            @Override
//            public void run() {
//                try {
//                    log.info("Thread-Name: {}", Thread.currentThread().getName());
//                    Thread.sleep(10000L);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//            }
//        };
//
//        thread.start();
//        thread1.start();

        SpringApplication.run(HmDianPingApplication.class, args);
    }

}
