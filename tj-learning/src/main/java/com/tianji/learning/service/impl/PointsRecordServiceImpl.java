package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mapper.PointsRecordMapper;
import com.tianji.learning.mq.msg.SignInMessage;
import com.tianji.learning.service.IPointsRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务实现类
 * </p>
 *
 * @author hercat
 * @since 2025-06-04
 */
@Service
@RequiredArgsConstructor
public class PointsRecordServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord> implements IPointsRecordService {


    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void addPointRecord(SignInMessage msg, PointsRecordType type) {
        //0.校验
        if (msg.getPoints() == null && msg.getUserId() == null) {
            return ;
        }
        //1.判断该积分类型是否有上限 type.maxPoints是否大于0
        int maxPoints = type.getMaxPoints();
        int realPoints = msg.getPoints(); //实际可以增加的积分
        if (maxPoints>0) {
            //2.如果有上线 查询该用户 该积分类型 今日已得积分 points_record 条件 userId type 今天 sum(points)
            LocalDate now = LocalDate.now();
            LocalDateTime beginTime = DateUtils.getWeekBeginTime(now);
            LocalDateTime endTime = DateUtils.getWeekEndTime(now);
            QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
            wrapper.select("sum(points) as totalPoints");
            wrapper.eq("user_id", msg.getUserId());
            wrapper.eq("type", type);
            wrapper.between("create_time", beginTime, endTime);
            Map<String, Object> map = this.getMap(wrapper);
            int currentPoints = 0;
            if (map != null) {
                BigDecimal totalPoints = (BigDecimal) map.get("totalPoints");
                currentPoints = totalPoints.intValue();
            }
            //3.判断已得积分是否超过上限
            if (currentPoints >= maxPoints) {
                return;
            }
            if (realPoints + currentPoints > maxPoints) {
                realPoints = maxPoints - currentPoints;
            }
        }
        //4.保存积分
        PointsRecord record = new PointsRecord();
        record.setUserId(msg.getUserId());
        record.setPoints(realPoints);
        record.setType(type);
        this.save(record);

        //5.累加并保存总积分值到redis 采用size 当前赛季的排行榜
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format;
        stringRedisTemplate.opsForZSet().incrementScore(key, msg.getUserId().toString(),realPoints);
    }

    @Override
    public List<PointsStatisticsVO> queryMyTodayPoints() {
        //1.获取用户id
        Long userId = UserContext.getUser();
        //2.查询积分表points——record 条件 userId 今日 按type分组 type, sum(points)
        //select type, sum(points) from points_record
        //where user_id=2 and create_time between '2023-05-29 00:00:01' and '2023-05-29 23:59:59'
        //group by type
        LocalDate now = LocalDate.now();
        LocalDateTime beginTime = DateUtils.getWeekBeginTime(now);
        LocalDateTime endTime = DateUtils.getWeekEndTime(now);
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.select("type", "sum(points) as userId"); //将查到的今日总积分暂存到userId这个空闲字段中
        wrapper.eq("user_id", userId);
        wrapper.between("create_time", beginTime, endTime);
        wrapper.groupBy("type");
        List<PointsRecord> list = this.list(wrapper);
        if (CollUtils.isEmpty(list)) {
            return CollUtils.emptyList();
        }

        //封装vo返回
        List<PointsStatisticsVO> voList = new ArrayList<>();
        for (PointsRecord r : list) {
            PointsStatisticsVO vo = new PointsStatisticsVO();
            vo.setType(r.getType().getDesc()); //积分类型的中文
            vo.setMaxPoints(r.getType().getMaxPoints()); //积分类型的上限
            vo.setPoints(r.getUserId().intValue()); //这个getUserId并不是用户的id 而是sum(points)暂存的值 即今日总积分
            voList.add(vo);
        }

        return voList;
    }
}
