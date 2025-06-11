package com.tianji.learning.service.impl;

import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardItemVO;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.mapper.PointsBoardMapper;
import com.tianji.learning.service.IPointsBoardService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 学霸天梯榜 服务实现类
 * </p>
 *
 * @author hercat
 * @since 2025-06-04
 */
@Service
@RequiredArgsConstructor
public class PointsBoardServiceImpl extends ServiceImpl<PointsBoardMapper, PointsBoard> implements IPointsBoardService {

    private final StringRedisTemplate redisTemplate;
    private final UserClient userClient;


    @Override
    public PointsBoardVO queryPointsBoardList(PointsBoardQuery query) {
        //1.获取当前登录的用户id
        Long userId = UserContext.getUser();

        //2，判断是查当前赛季还是历史赛季 query_season 赛季id 为0或null表示查当前赛季
        boolean isCurrent = query.getSeason() == null || query.getSeason() == 0;

        //3.查询我的排名和积分 根据 query_season 判断redis还是db
        LocalDate now = LocalDate.now();
        String format = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + format;
        long season = query.getSeason(); //历史赛季id
        PointsBoard board = isCurrent ? queryMyCurrentBoard(key) : queryMyHistoryBoard(season);
        /*if (isCurrent) {
            board = queryMyCurrentBoard(key);
        } else {
            board = queryMyHistoryBoard(season);
        }*/
        //4.分页查询赛季列表 根据 query_season 判断是查redis还是db
        List<PointsBoard> list = isCurrent ? queryCurrentBoard(key, query.getPageNo(), query.getPageSize()) : queryHistoryBoard(query);

        //5.封装用户id集合 远程调用用户服务 获取用户服务 转map
        Set<Long> uIds = list.stream().map(PointsBoard::getUserId).collect(Collectors.toSet());
        List<UserDTO> userDTOS = userClient.queryUserByIds(uIds);
        if (CollUtils.isEmpty(userDTOS)) {
            throw new BizIllegalException("用户不存在");
        }
        Map<Long, String> userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, UserDTO::getName));

        //6.封装vo返回
        PointsBoardVO vo = new PointsBoardVO();
        vo.setRank(board.getRank()); //我的排名
        vo.setPoints(board.getPoints()); //我的积分

        List<PointsBoardItemVO> voList = new ArrayList<>();
        for (PointsBoard pointsBoard : list) {
            PointsBoardItemVO itemVO = new PointsBoardItemVO();
            itemVO.setName(userDTOMap.get(pointsBoard.getUserId()));
            itemVO.setPoints(pointsBoard.getPoints());
            itemVO.setRank(pointsBoard.getRank());
            voList.add(itemVO);
        }
        vo.setBoardList(voList);
        return vo;
    }

    //查询历史赛季列表 从db查
    private List<PointsBoard> queryHistoryBoard(PointsBoardQuery query) {
        return null;
    }

    /**
     * 查询当前赛季排行榜列表 从redis zset查
     * @param key 缓存
     * @param pageNo 页码
     * @param pageSize 条数
     * @return
     */
    @Override
    public List<PointsBoard> queryCurrentBoard(String key, Integer pageNo, Integer pageSize) {
        //1.计算start和stop 分页值
        int start = (pageNo - 1) * pageSize;
        int end = start + pageSize - 1;
        //2.利用zrerange名 会按分数倒序 分页查询
        Set<ZSetOperations.TypedTuple<String>> typedTuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
        if (CollUtils.isEmpty(typedTuples)) {
            return CollUtils.emptyList();
        }
        //3.封装结果返回
        int rank = start + 1;
        List<PointsBoard> voList = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String value = typedTuple.getValue();
            Double score = typedTuple.getScore();
            if (StringUtils.isBlank(value) || score == null) {
                continue;
            }
            PointsBoard board = new PointsBoard();
            board.setUserId(Long.valueOf(value)); //用户id
            board.setPoints(score.intValue()); //积分
            board.setRank(rank++); //排名
            voList.add(board);
        }
        return voList;
    }

    private PointsBoard queryMyHistoryBoard(long season) {
        //todo
        return null;
    }

    private PointsBoard queryMyCurrentBoard(String key) {
        Long userId = UserContext.getUser();
        //获取分值
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        //获取排名 从0开始 需要加1
        Long rank = redisTemplate.opsForZSet().reverseRank(key, userId.toString());
        PointsBoard board = new PointsBoard();
        board.setRank(rank == null ? 0 : rank.intValue() + 1);
        board.setPoints(score == null ? 0 : score.intValue());
        return board;
    }
}
