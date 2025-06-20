package com.tianji.learning.service;

import com.tianji.learning.domain.po.PointsBoardSeason;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.vo.PointsBoardSeasonVO;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author hercat
 * @since 2025-06-04
 */
public interface IPointsBoardSeasonService extends IService<PointsBoardSeason> {

    List<PointsBoardSeasonVO> queryBoardsSeasons();

    void createPointsBoardLastestTable(Integer id);
}
