package com.tianji.remark.constans;

public interface RedisConstants {
    //给业务点赞的用户集合的key前缀，后缀使业务id
    String LIKE_BIZ_KEY_PREFIX = "likes:set:biz:";
    //业务点赞数统计key前缀 后缀使业务类型
    String LIKE_COUNT_KEY_PREFIX = "likes:times:type:";
}
