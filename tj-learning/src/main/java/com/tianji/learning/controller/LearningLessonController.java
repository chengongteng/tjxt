package com.tianji.learning.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.learning.domain.dto.LearningPlanDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;
import com.tianji.learning.service.ILearningLessonService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 学生课程表 前端控制器
 * </p>
 *
 * @author hercat
 * @since 2025-05-27
 */
@RestController
@Api(tags = "我的课程相关接口")
@RequiredArgsConstructor
@RequestMapping("/lessons")
public class LearningLessonController {

    final ILearningLessonService lessonService;

    @GetMapping("page")
    @ApiOperation("分页查询我的课表")
    public PageDTO<LearningLessonVO> queryMyLessons(PageQuery query) {
        return lessonService.queryMyLessons(query);
    }

    @GetMapping("now")
    @ApiOperation("查询正在学习的课程")
    public LearningLessonVO queryMyCurrentLesson() {
        return lessonService.queryMyCurrentLesson();
    }

    @GetMapping("/{courseId}/valid")
    @ApiOperation("查询当前用户是否可以学习当前课程")
    public Long isLessonValid(@PathVariable("courseId") Long courseId) {
        return lessonService.isLessonValid(courseId);
    }

    @GetMapping("/{courseId}")
    @ApiOperation("查询用户课表中指定课程状态")
    public LearningLessonVO queryCourseStatus(@PathVariable("courseId") Long courseId) {
        return lessonService.queryCourseStatus(courseId);
    }

    @DeleteMapping("/{courseId}")
    @ApiOperation("处理用户退款")
    public void deleteCourseById(@PathVariable("courseId") Long courseId) {
        lessonService.deleteCourseById(courseId);
    }


    /**
     * 统计课程学习人数
     *
     * @param courseId 课程id
     * @return 学习人数
     */
    @ApiOperation("统计课程的学习人数")
    @GetMapping("/{courseId}/count")
    public Integer countLearningLessonByCourse(@PathVariable("courseId") Long courseId) {
        return lessonService.countLearningLessonByCourse(courseId);
    }


    /**
     * 创建学习计划
     */
    @ApiOperation("创建学习计划")
    @PostMapping("/plans")
    public void createLearningPlan(@RequestBody @Validated LearningPlanDTO dto) {
        lessonService.createLearningPlan(dto);
    }

    @ApiOperation("分页查询我的课程计划 ")
    @GetMapping("/plans")
    public LearningPlanPageVO queryMyPlans(PageQuery query) {
        return lessonService.queryMyPlans(query);
    }

}
