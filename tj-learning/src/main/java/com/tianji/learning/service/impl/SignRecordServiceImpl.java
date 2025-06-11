package com.tianji.learning.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.mq.msg.SignInMessage;
import com.tianji.learning.service.ISignRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignRecordServiceImpl implements ISignRecordService {

    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper rabbitMqHelper;

    @Override
    public SignResultVO addSignRecords() {
        //1.获取用户id
        Long userId = UserContext.getUser();
        //2.拼接key
        //SimpleDateFormat format = new SimpleDateFormat("yyyyMM");
        //format.format(new Date());
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM")); //得到 冒号年月 格式字符串
        String key = RedisConstants.SING_RECORD_KEY_PREFIX + userId.toString() + format;
        //3.利用bitset命令 将签到记录保存到redis的bitmap结构中  需要校验是否已签到
        int offset = now.getDayOfMonth() - 1; //redis中的setbit是从0开始的 如果要让第7天签到成功 则需要传入偏移量 6
        Boolean setBit = redisTemplate.opsForValue().setBit(key, offset, true);
        if (setBit) {
            //说明当天已经签到了
            throw new BizIllegalException("不能重复签到");
        }
        //4.计算连续签到的天数
        int days = countSignDays(key, now.getDayOfMonth());
        //5.计算连续签到的奖励积分
        int rewardPoints = 0;
        switch (days) {
            case 7:
                rewardPoints = 10;
                break;
            case 14:
                rewardPoints = 20;
                break;
            case 28:
                rewardPoints = 40;
                break;
        }
        //6.保存积分
        rabbitMqHelper.send(MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.SIGN_IN,
                SignInMessage.of(userId, rewardPoints + 1));


        //7.封装vo返回
        SignResultVO vo = new SignResultVO();
        vo.setSignDays(days);
        vo.setRewardPoints(rewardPoints);

        return vo;
    }

    @Override
    public Byte[] querySignRecord() {
        Long userId = UserContext.getUser();
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM")); //得到 冒号年月 格式字符串
        String key = RedisConstants.SING_RECORD_KEY_PREFIX + userId.toString() + format;
        int days = now.getDayOfMonth();
        List<Long> bitField = redisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(days)).valueAt(0));
        if (CollUtil.isEmpty(bitField)) {
            return new Byte[0];
        }
        Long num = bitField.get(0);
        /*String str = Long.toBinaryString(num);
        Byte[] arr = new byte[days];
        int index = 0;
        for (char c : str.toCharArray()) {
            arr[index] = (byte) (c == '1' ? 1 : 0);
            index++;
        }
        return arr;*/
        int offset = days - 1;
        //4.利用与运算和 位移 封装结果
        Byte[] arr = new Byte[days];
        while (offset >= 0) {
            arr[offset] = (byte) (num & 1);
            offset--;
            num = num >>> 1;
        }

        return arr;
    }

    /**
     * 计算连续签到的天数
     *
     * @param key        缓存中的key
     * @param dayOfMonth 本月第一天到今天的天数
     * @return counter 本月连续签到的天数
     */
    private int countSignDays(String key, int dayOfMonth) {
        List<Long> list = redisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (CollUtil.isEmpty(list)) {
            return 0;
        }
        Long num = list.get(0);
        int counter = 0; //计数器
        while ((num & 1) == 1) {
            counter++;
            num >>>= 1;
        }
        return counter;
    }
}
