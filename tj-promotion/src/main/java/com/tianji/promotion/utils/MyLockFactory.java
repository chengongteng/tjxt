package com.tianji.promotion.utils;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

import static com.tianji.promotion.utils.MyLockType.*;

/**
 * 工厂作用
 * 根据用户参数不同 可以获取不同类型的锁对象
 */

@Component
public class MyLockFactory {

    //传入的 Function<String, RLock> 是一个方法引用 不是一个锁对象
    private final Map<MyLockType, Function<String, RLock>> lockHandlers;

    public MyLockFactory(RedissonClient redissonClient) {
        this.lockHandlers = new EnumMap<>(MyLockType.class);
        this.lockHandlers.put(RE_ENTRANT_LOCK, redissonClient::getLock);
        //方法引用还原成匿名内部类
        //this.lockHandlers.put(RE_ENTRANT_LOCK, new Function<String, RLock>() {
        //    @Override
        //    public RLock apply(String name) {
        //        return redissonClient.getLock(name);
        //    }
        //});


        this.lockHandlers.put(FAIR_LOCK, redissonClient::getFairLock);
        this.lockHandlers.put(READ_LOCK, name -> redissonClient.getReadWriteLock(name).readLock());
        this.lockHandlers.put(WRITE_LOCK, name -> redissonClient.getReadWriteLock(name).writeLock());
        //lambda表达式换成匿名内部类
        //this.lockHandlers.put(WRITE_LOCK, new Function<String, RLock>() {
        //    @Override
        //    public RLock apply(String name) {
        //        return redissonClient.getReadWriteLock(name).writeLock();
        //    }
        //});

    }

    public RLock getLock(MyLockType lockType, String name){
        //apply的作用是获得get的引用 比如MyLockFactory中 用户获得的是getLock(可重入锁) 即apply获得是getLock的引用 得到的也是getLock的锁
        return lockHandlers.get(lockType).apply(name);
    }
}