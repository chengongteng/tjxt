//package com.tianji.promotion.config;
//
//import org.redisson.Redisson;
//import org.redisson.api.RedissonClient;
//import org.redisson.config.Config;
//import org.springframework.beans.factory.annotation.Configurable;
//import org.springframework.context.annotation.Bean;
//
//父工程已经有了Redisson依赖 配置了RedissonConfig 无需再配置
//
//@Configurable
//public class RedisConfig {
//
//    @Bean
//    public RedissonClient redissonClient() {
//        //配置类
//        Config config = new Config();
//        //添加redis地址 这里添加了单点的地址 也可以使用config.userClusterServers()添加集群地址
//        config.useSingleServer()
//                .setAddress("redis://197.168.150.101:6379")
//                .setPassword("123321");
//        //创建客户端
//        return Redisson.create(config);
//    }
//}
