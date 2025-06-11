package com.tianji.promotion.service.impl;

import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.mapper.ExchangeCodeMapper;
import com.tianji.promotion.service.IExchangeCodeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.tianji.promotion.constants.PromotionConstants.COUPON_RANGE_KEY;

/**
 * <p>
 * 兑换码 服务实现类
 * </p>
 *
 * @author hercat
 * @since 2025-06-06
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeCodeServiceImpl extends ServiceImpl<ExchangeCodeMapper, ExchangeCode> implements IExchangeCodeService {

    private final StringRedisTemplate redisTemplate;

    //异步生成兑换码
    @Override
    //需要在引导类上加注解 @EnableAsync
    @Async("generateExchangeCodeExecutor") //使用generateExchangeCodeExecutor 自定义线程池中的线程异步执行
    public void asyncGenerateExchangeCode(Coupon coupon) {
        log.debug("生成兑换码 线程名 {}", Thread.currentThread().getName());
        //totalNum代表优惠券的的发放总数 也就是需要生成的兑换码总数量
        Integer totalNum = coupon.getTotalNum();
        //方式1 循环兑换码总数量 循环中当个获取自增id incr (效率不高)
        //方式2 调用incrby 得到自增id最大值 然后在循环生成兑换码(只需要操作一次redis即可)
        //1.生成自增id 借助于redis incr
        Long increment = redisTemplate.opsForValue().increment(PromotionConstants.COUPON_CODE_SERIAL_KEY, totalNum);
        if (increment == null) {
            return;
        }
        int maxSerialNum = increment.intValue(); //本地自增id的最大值
        int begin = (int) (increment - totalNum + 1); //自增id循环开始值
        //2.循环生成兑换码 调用工具类 生成兑换码
        List<ExchangeCode> exList = new ArrayList<>();
        for (int serialName = begin; serialName < maxSerialNum; serialName++) {
            //参数1为自增id值 参数2为优惠券id(内部会计算出0-15之间的数字 然后找对应的密钥)
            String code = CodeUtil.generateCode(serialName, coupon.getId());
            ExchangeCode exchangeCode = new ExchangeCode();
            exchangeCode.setId(serialName); //兑换码id ExchangeCode主键生成策略需要修改为手动输入
            exchangeCode.setCode(code);
            exchangeCode.setExchangeTargetId(coupon.getId()); //优惠券id
            exchangeCode.setExpiredTime(coupon.getIssueEndTime()); //兑换码 兑换的截止时间 就是优惠券领取的截止时间
            exList.add(exchangeCode);
        }
        //3.将段换吗保存到db exchange_code 批量保存
        this.saveBatch(exList);

        //4.写入Redis缓存 member:couponId, score:兑换码的最大序列号
        redisTemplate.opsForZSet().add(COUPON_RANGE_KEY, coupon.getId().toString(), maxSerialNum);
    }

    @Override
    public boolean updateExchangeCodeMark(long serialNum, boolean flag) {
        String key = PromotionConstants.COUPON_CODE_MAP_KEY;
        //修改兑换码 的 自增id 对应的offset 的值
        Boolean aBoolean = redisTemplate.opsForValue().setBit(key, serialNum, flag); //返回值是 该偏移量上原来的值
        return aBoolean != null && aBoolean;
    }
}
