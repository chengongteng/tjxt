package com.tianji.learning;

import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

@SpringBootTest(classes = LearningApplication.class)
public class TimeTest {
    @org.junit.jupiter.api.Test
    public void Test1(){
        LocalDate now = LocalDate.now();
        System.out.println(now.getDayOfMonth());
    }
}
