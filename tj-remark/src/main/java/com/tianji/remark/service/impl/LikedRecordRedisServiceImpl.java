package com.tianji.remark.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.remark.constans.RedisConstants;
import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.dto.LikedTimesDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.service.ILikedRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * <p>
 * 点赞记录表 服务实现类
 * </p>
 *
 * @author hercat
 * @since 2025-06-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikedRecordRedisServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {

    private final RabbitMqHelper rabbitMqHelper;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void addLikeRecord(LikeRecordFormDTO dto) {
        //1.获取当前用户
        Long userId = UserContext.getUser();
        //2.判断是否点赞 dto,liked 为true则是点赞
        /*boolean flag = true;
        if (dto.getLiked()) {
            //2.1点赞逻辑
            flag = liked(dto);
        } else {
            //2.2取消赞逻辑
            flag = unliked(dto);
        }*/
        boolean flag = dto.getLiked() ? liked(dto, userId) : unliked(dto, userId);
        if (!flag) { //说明点赞或者取消赞失败
            return;
        }
        //3.统计该业务id的总点赞数
        /*Integer totalLikesNum = this.lambdaQuery()
                .eq(LikedRecord::getBizId, dto.getBizId())
                .count();*/
        //基于redis统计 业务id的总点赞量
        String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();
        Long totalLikesNum = redisTemplate.opsForSet().size(key);
        if (totalLikesNum == null) {
            return;
        }

        //4.采用zset结构缓存点赞的总数
        String bizTypeTotalLikeKey = RedisConstants.LIKE_COUNT_KEY_PREFIX + dto.getBizType();
        redisTemplate.opsForZSet().add(bizTypeTotalLikeKey, dto.getBizId().toString(), totalLikesNum);

        /*//4.发消息到mq
        String routingKey = StringUtils.format(MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE, dto.getBizType());
        */
        /*LikedTimesDTO msg = new LikedTimesDTO();
        msg.setBizId(dto.getBizId());
        msg.setLikedTimes(totalLikesNum);*/
        /*
//        LikedTimesDTO msg = LikedTimesDTO.builder().bizId(dto.getBizId()).likedTimes(totalLikesNum).build();
        LikedTimesDTO msg = LikedTimesDTO.of(dto.getBizId(), totalLikesNum);
        log.debug("发送点赞消息 消息内容{}",msg);
        rabbitMqHelper.send(
                MqConstants.Exchange.LIKE_RECORD_EXCHANGE,
                routingKey,
                msg
        );*/
    }

    @Override
    public Set<Long> getLikesStatusByBizIds(List<Long> bizIds) {
        //1.获取用户
        Long userId = UserContext.getUser();
        if (CollUtils.isEmpty(bizIds)) {
            return CollUtils.emptySet();
        }
        //2.查询点赞状态
        List<Object> objects = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection src = (StringRedisConnection) connection;
            for (Long bizId : bizIds) {
                String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + bizId;
                src.sIsMember(key, userId.toString());
            }
            return null;
        });
        //3.返回结构
        return IntStream.range(0, objects.size()) //创建从0到集合size的流
                .filter(i -> (boolean) objects.get(i)) //遍历每个元素 保留结果为true的角标i
                .mapToObj(bizIds::get) //用角标i取bizIds中的对应数据 就是点赞过的id
                .collect(Collectors.toSet()); //收集

        /*//1.获取用户
        Long userId = UserContext.getUser();
        if (CollUtils.isEmpty(bizIds)) {
            return CollUtils.emptySet();
        }
        //2.循环bizIds
        Set<Long> likedBizIds = new HashSet<>();
        for (Long bizId : bizIds) {
            //3.判断当前业务 当前用户是否点赞
            //判断该业务id 的点赞用户集合中是否包含当前用户
            Boolean member = redisTemplate.opsForSet().isMember(RedisConstants.LIKE_BIZ_KEY_PREFIX, userId.toString());
            //4.如果有当前用户id 则存入新结合返回
            if (member) {
                likedBizIds.add(bizId);
            }
        return likedBizIds;
        }*/
        /*if (CollUtils.isEmpty(bizIds)) {
            return CollUtils.emptySet();
        }

        //1.获取用户id
        Long userId = UserContext.getUser();
        //2.查询点赞记录表 in bizIds
        List<LikedRecord> recordList = this.lambdaQuery()
                .in(LikedRecord::getBizId, bizIds)
                .eq(LikedRecord::getUserId, userId)
                .list();
        //3.将查询到的bizId转集合返回
        Set<Long> likedBizIds = recordList.stream().map(LikedRecord::getBizId)
                .collect(Collectors.toSet());
        return likedBizIds;
        */
    }

    @Override
    public void readLikedTimesAndSendMessage(String bizType, int maxBizSize) {
        //1.拼接key
        String bizTypeTotalLikeKey = RedisConstants.LIKE_COUNT_KEY_PREFIX + bizType;
        //2.从redis的zset结构中 按分数取maxBizSize的业务点赞信息 popmin
        List<LikedTimesDTO> list = new ArrayList<>();
        Set<ZSetOperations.TypedTuple<String>> typedTuples = redisTemplate.opsForZSet().popMin(bizTypeTotalLikeKey, maxBizSize);
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String bizId = typedTuple.getValue();
            Double likedTimes = typedTuple.getScore();
            if (StringUtils.isBlank(bizId) || likedTimes == null) {
                continue;
            }
            //3.封装LikedTimesDTO 消息数据
            LikedTimesDTO likedTimesDTO = LikedTimesDTO.of(Long.valueOf(bizId), likedTimes.intValue());
            list.add(likedTimesDTO);
        }
        //4.发送消息到mq
        String routingKey = StringUtils.format(MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE, bizType);
        if (CollUtils.isNotEmpty(list)) {
            log.debug("发送的消息内容 {}", list);
            rabbitMqHelper.send(
                    MqConstants.Exchange.LIKE_RECORD_EXCHANGE,
                    routingKey,
                    list);
        }
    }

    private boolean liked(LikeRecordFormDTO dto, Long userId) {
        //基于redis做点赞
        //拼接key
        String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();
        //操作redis redisTemplate 往redis的set结构
        Long result = redisTemplate.boundSetOps(key).add(userId.toString());
//        redisTemplate.opsForSet().add(key,userId.toString());
        return  result != null && result > 0;
        /*LikedRecord record = this.lambdaQuery()
                .eq(LikedRecord::getUserId, userId)
                .eq(LikedRecord::getBizId, dto.getBizId())
                .one();
        if (record == null) {
            //说明没有点赞记录 取消失败
            return false;
        }
//        return this.remove(Wrappers.<LikedRecord>lambdaQuery().eq(LikedRecord::getId, userId).eq(LikedRecord::getBizId, dto.getBizId()));
        return this.removeById(record.getId());*/
    }

    private boolean unliked(LikeRecordFormDTO dto, Long userId) {
        //拼接key
        String key = RedisConstants.LIKE_BIZ_KEY_PREFIX + dto.getBizId();
        Long result = redisTemplate.opsForSet().remove(key, userId.toString());
        return result != null && result > 0;

        /*LikedRecord record = this.lambdaQuery()
                .eq(LikedRecord::getUserId, userId)
                .eq(LikedRecord::getBizId, dto.getBizId())
                .one();
        if (record != null) {
            //说明之前点过赞
            return false;
        }
        //保存点赞记录到表中
        LikedRecord likedRecord = new LikedRecord();
        likedRecord.setUserId(userId);
        likedRecord.setBizId(dto.getBizId());
        likedRecord.setBizType(dto.getBizType());
        return this.save(likedRecord);*/
    }
}
