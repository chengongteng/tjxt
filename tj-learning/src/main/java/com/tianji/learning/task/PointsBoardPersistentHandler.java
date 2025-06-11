package com.tianji.learning.task;

import com.tianji.common.utils.CollUtils;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.tianji.learning.service.IPointsBoardService;
import com.tianji.learning.utils.TableInfoContext;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.tianji.learning.constants.LearningConstants.POINTS_BOARD_TABLE_PREFIX;

@Slf4j
@Component
@RequiredArgsConstructor
public class PointsBoardPersistentHandler {

    private final IPointsBoardSeasonService pointsBoardSeasonService;
    private final IPointsBoardService pointsBoardService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 创建上赛季（上个月） 排行表
     */
    @Scheduled(cron = "0 0 3 1 * ?") //每个月1号凌晨3点运行
    public void createPointsBoardTableOfLastSeason() {
        log.debug("创建上赛季榜单表任务执行了");
        //1.获取上个月当前事件点
        LocalDate time = LocalDate.now().minusMonths(1);
        //2.查询赛季表获取赛季id 条件 begin_time <= time and end_time >= time
        PointsBoardSeason one = pointsBoardSeasonService.lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .one();
        log.debug("上赛季信息 {}", one);
        if (one == null) {
            return;
        }

        //3.创建上赛季榜单表 points_board_7
        pointsBoardSeasonService.createPointsBoardLastestTable(one.getId());
    }


    //持久化上赛季（上个月）排行榜数据 到db中
    @XxlJob("savePointsBoard2DB")
    public void savePointsBoard2DB() {
        //1.获取上个月 当前时间点
        LocalDate time = LocalDate.now().minusMonths(1);
        //2.查询赛季表points_board_season 获取上赛季信息
        //select * from points_board_season where begin_time <= '2025-05-01' and end_time >= '2025-05-01'
        PointsBoardSeason one = pointsBoardSeasonService.lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .one();
        //3.计算动态表名 并存入threadLocal
        String tableName = POINTS_BOARD_TABLE_PREFIX + one.getId();
        log.debug("动态表名为 {}", tableName);
        TableInfoContext.setInfo(tableName);
        //4.分页获取redis上赛季积分排行榜数据
        String format = time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format; //boards:上赛季的年月 boards:2025


        int shardIndex = XxlJobHelper.getShardIndex(); //当前分片的索引 从0开始
        int shardTotal = XxlJobHelper.getShardTotal(); //总分片数
        int pageNo = shardIndex + 1; //页码；
        int pageSize = 1000; //条数
        while (true) {
            List<PointsBoard> pointsBoardList = pointsBoardService.queryCurrentBoard(key, pageNo, pageSize);
            if (CollUtils.isEmpty(pointsBoardList)) {
                break; //跳出循环 进行下一步
            }
            pageNo += shardTotal;
            //5.持久化到db相应的赛季表中
            for (PointsBoard board : pointsBoardList) {
                //历史赛季排行榜中的id 代表了排名
                board.setId(Long.valueOf(board.getRank()));
                //历史赛季排行榜中没有rank字段 所以需要清空赋值
                board.setRank(null);
            }
            //pointsBoardList.forEach(p -> {
            //    p.setId(p.getRank().longValue());
            //    p.setRank(null);
            //});

            pointsBoardService.saveBatch(pointsBoardList);

            //7.删除redis本页数据
        }
        //6.清空threadLocal中的表名数据
        TableInfoContext.remove();
    }

    @XxlJob("clearPointsBoardFromRedis")
    public void clearPointsBoardFromRedis(){
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 2.计算key
        String format = time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format; //boards:上赛季的年月 boards:2025
        // 3.删除
        redisTemplate.unlink(key);
    }
}

