package com.tianji.learning.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.domain.vo.PointsBoardSeasonVO;
import com.tianji.learning.mapper.PointsBoardSeasonMapper;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.tianji.learning.constants.LearningConstants.POINTS_BOARD_TABLE_PREFIX;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author hercat
 * @since 2025-06-04
 */
@Service
public class PointsBoardSeasonServiceImpl extends ServiceImpl<PointsBoardSeasonMapper, PointsBoardSeason> implements IPointsBoardSeasonService {

    @Override
    public List<PointsBoardSeasonVO> queryBoardsSeasons() {
        List<PointsBoardSeason> list = this.lambdaQuery().list();
        if (CollUtil.isEmpty(list)) {
            throw new BadRequestException("赛季列表查询失败");
        }
        List<PointsBoardSeasonVO> voList = new ArrayList<>();
        for (PointsBoardSeason p : list) {
            PointsBoardSeasonVO vo = BeanUtils.copyBean(p, PointsBoardSeasonVO.class);
            voList.add(vo);
        }
        return voList;
    }

    /**
     * 创建上赛季表
     * @param id
     */
    @Override
    public void createPointsBoardLastestTable(Integer id) {
        getBaseMapper().createPointsBoardTable(POINTS_BOARD_TABLE_PREFIX + id);
    }
}
