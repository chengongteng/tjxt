package com.cgt.tjxt;

import com.tianji.promotion.PromotionApplication;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

@SpringBootTest(classes = PromotionApplication.class)
public class RedisLockTest {

    @Autowired
    RedissonClient redissonClient;

    @Test
    public void test() throws InterruptedException {
        RLock lock = redissonClient.getLock("anyLock");//可重入锁
        try {
            //boolean isLock = lock.tryLock(1, 30, TimeUnit.SECONDS); //看门狗会失效
            //看门机制不能设置失效时间 采用默认的失效时间30秒
            boolean isLock = lock.tryLock();
            if (isLock) {
                System.out.println("获取分布式锁");
            }else {
                System.out.println("没有获取到锁");
            }
            //通过睡眠 查看看门狗机制是否生效 不能打断点
            TimeUnit.SECONDS.sleep(60);
            //Thread.sleep(60000);

        }finally {
            //释放分布式锁
            lock.unlock();
        }

    }
}
