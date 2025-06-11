package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSearchDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.LearningPlanDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;
import com.tianji.learning.domain.vo.LearningPlanVO;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.PlanStatus;
import com.tianji.learning.mapper.LearningLessonMapper;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.BindException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 学生课程表 服务实现类
 * </p>
 *
 * @author hercat
 * @since 2025-05-27
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LearningLessonServiceImpl extends ServiceImpl<LearningLessonMapper, LearningLesson> implements ILearningLessonService {

    final CourseClient courseClient;
    final CatalogueClient catalogueClient;
    final LearningRecordMapper recordMapper;

    @Override
    public void addUserLesson(Long userId, List<Long> courseIds) {
        List<CourseSimpleInfoDTO> cinfos = courseClient.getSimpleInfoList(courseIds);
        ArrayList<LearningLesson> list = new ArrayList<>();
        for (CourseSimpleInfoDTO cinfo : cinfos) {
            LearningLesson lesson = new LearningLesson();
            lesson.setId(userId);
            lesson.setCourseId(cinfo.getId());
            Integer validDuration = cinfo.getValidDuration();
            if (validDuration != null) {
                LocalDateTime now = LocalDateTime.now();
                lesson.setCreateTime(now);
                lesson.setExpireTime(now.plusMonths(validDuration));
            }
            list.add(lesson);
        }
        //批量插入
        this.saveBatch(list);
    }

    @Override
    public PageDTO<LearningLessonVO> queryMyLessons(PageQuery query) {
        //1.获取当前用户id
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new BadRequestException("当前用户未登录");
        }

        //2.分页查询我的课表
        Page<LearningLesson> page = this.lambdaQuery().eq(LearningLesson::getUserId, userId)
                .page(query.toMpPage("latest_learn_time", false));
        List<LearningLesson> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        //3.远程调用课程服务，给vo中课程名 封面 章节赋值
        Set<Long> courseIds = records.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());
        List<CourseSimpleInfoDTO> cInfoList = courseClient.getSimpleInfoList(courseIds);
        if (CollUtils.isEmpty(cInfoList)) {
            throw new BizIllegalException("课程不能为空");
        }
        //将cInfoList转换为Map结构<课程id, 课程对象>
        Map<Long, CourseSimpleInfoDTO> infoDTOMap = cInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c, (oldValue, newValue) -> newValue));

        //4.将po中的数据封装到vo中
        List<LearningLessonVO> voList = new ArrayList<>();
        for (LearningLesson record : records) {
            LearningLessonVO vo = BeanUtils.copyBean(record, LearningLessonVO.class);
            CourseSimpleInfoDTO infoDTO = infoDTOMap.get(record.getCourseId());
            if (infoDTO != null) {
                vo.setCourseName(infoDTO.getName());
                vo.setCourseCoverUrl(infoDTO.getCoverUrl());
                vo.setSections(infoDTO.getSectionNum());
            }
            voList.add(vo);
        }
        //5.返回
        return PageDTO.of(page, voList);
    }

    @Override
    public LearningLessonVO queryMyCurrentLesson() {
        //1.获取当前登录用户的id
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new BadRequestException("当前用户未登录");
        }
        //2.查询当前用户最新学习课程 按latest_learn_time降序排序 取第一条 正在学习中 status=1
        //select * from learning_lesson where userId = xxx and status = 1 order by latest_learning_time desc limit 1
        LearningLesson lesson = this.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getStatus, LessonStatus.LEARNING)
                .orderByDesc(LearningLesson::getLatestLearnTime)
                .last("limit 1")    //直接拼接sql语句
                .one();

        //this.getOne(Wrappers.<LearningLesson>lambdaQuery()
        //        .eq(LearningLesson::getUserId, userId)
        //        .eq(LearningLesson::getStatus, LessonStatus.LEARNING)
        //        .orderByDesc(LearningLesson::getLatestLearnTime)
        //        .last("limit 1"));

        if(lesson == null) {
            return null;
        }
        //3.远程调用课程服务，给vo中的课程名 封面 章节赋值
        CourseFullInfoDTO courseInfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        if (courseInfo == null) {
            throw new BizIllegalException("课程不存在");
        }

        //4.查询当前用户课表中 总的课程数
        //select count(*) from learning_lesson where user_id = xxx
        Integer count = this.lambdaQuery().eq(LearningLesson::getUserId, userId).count();

        //5.通过feign远程调用课程服务 获取小节名称 小节编号
        Long latestSectionId = lesson.getLatestSectionId();
        List<CataSimpleInfoDTO> cataSimpleInfoDTOS = catalogueClient.batchQueryCatalogue(CollUtils.singletonList(latestSectionId));
        if (CollUtils.isEmpty(cataSimpleInfoDTOS)) {
            throw new BizIllegalException("小节不存在");
        }
        //6.封装vo返回
        LearningLessonVO vo = BeanUtils.copyBean(lesson, LearningLessonVO.class);
        vo.setCourseName(courseInfo.getName());
        vo.setCourseCoverUrl(courseInfo.getCoverUrl());
        vo.setSections(courseInfo.getSectionNum());
        vo.setCourseAmount(count);   //当前用户能学习课程总数
        CataSimpleInfoDTO cataSimpleInfoDTO = cataSimpleInfoDTOS.get(0);
        vo.setLatestSectionName(cataSimpleInfoDTO.getName());  //最近学习的小节名称
        vo.setLatestSectionIndex(cataSimpleInfoDTO.getCIndex());     //最近学习的小节序号

        return vo;
    }

    @Override
    public Long isLessonValid(Long courseId) {
        //1.获取当前用户的userId
        Long userId = UserContext.getUser();

        //2.查询用户课表是否有该课表
        LearningLesson lesson = this.lambdaQuery().eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (lesson == null) {
            return null;
        }

        //3.查询课表状态是否过期
        LocalDateTime expireTime = lesson.getExpireTime();
        LocalDateTime now = LocalDateTime.now();
        if (expireTime != null && now.isAfter(expireTime)) {
            return null;
        }

        //4.返回 lessonId
        return lesson.getId();
    }

    @Override
    public void deleteCourseById(Long courseId) {
        Long userId = UserContext.getUser();
        LambdaUpdateWrapper<LearningLesson> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(LearningLesson::getUserId, userId)
                .eq(lesson -> lesson.getCourseId(), courseId);
        remove(wrapper);
    }

    @Override
    public LearningLessonVO queryCourseStatus(Long courseId) {
        Long userId = UserContext.getUser();

        LearningLesson lesson = this.lambdaQuery().eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();

        if (lesson == null) {
            return null;
        }
        LearningLessonVO vo = BeanUtils.copyBean(lesson, LearningLessonVO.class);
        return vo;
    }

    @Override
    public Integer countLearningLessonByCourse(Long courseId) {
        CourseSearchDTO searchInfo = courseClient.getSearchInfo(courseId);
        if (searchInfo == null) {
            throw new BadRequestException("课程id错误，未知课程");
        }
        Integer sold = searchInfo.getSold();
        if (sold == null) {
            throw new BadRequestException("查询错误");
        }
        return sold;
    }

    @Override
    public void createLearningPlan(LearningPlanDTO dto) {
        Long userId = UserContext.getUser();
        LearningLesson lesson = this.lambdaQuery().eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, dto.getCourseId())
                .one();
        if (lesson == null) {
            throw new BizIllegalException("该课程没有加入课表");
        }
        //这种写法每个字段都会更新
        //lesson.setWeekFreq(dto.getFreq());
        //this.updateById(lesson);
        this.lambdaUpdate()
                .set(LearningLesson::getWeekFreq, dto.getFreq())
                .set(LearningLesson::getPlanStatus, PlanStatus.PLAN_RUNNING)
                .eq(LearningLesson::getId, lesson.getId())
                .update();
    }

    @Override
    public LearningPlanPageVO queryMyPlans(PageQuery query) {
        //1.获取当前登录用户id
        Long userId = UserContext.getUser();

        //todo 2.查询积分
        //3.查询本周学习计划总数 learning_lesson 条件userId status in(0,1) plan_status=1  查询sum(week_freq)
        QueryWrapper<LearningLesson> wrapper = new QueryWrapper<>();
        wrapper.select("sum(week_freq) as plansTotal"); //查询哪些列
        wrapper.eq("user_id", userId);
        wrapper.in("status", LessonStatus.NOT_BEGIN, LessonStatus.LEARNING);
        wrapper.eq("plan_status", PlanStatus.PLAN_RUNNING);
        Map<String, Object> map = this.getMap(wrapper);
        //BigDecimal plansTotal = (BigDecimal) map.get("plansTotal");
        //int weekFreqTotal = plansTotal.intValue();
        Integer plansTotal = 0;
        if (map != null && map.get("plansTotal") != null) {
            plansTotal = Integer.valueOf(map.get("plansTotal").toString());
        }

        //4.查询本周 已学习的计划总数 learning_record 条件userId finish_time在本周区间之内 finished为true count(*)
        LocalDate now = LocalDate.now();
        LocalDateTime beginTime = DateUtils.getWeekBeginTime(now);
        LocalDateTime endTime = DateUtils.getWeekEndTime(now);

        Integer weekFinishedPlanNum = recordMapper.selectCount(Wrappers.<LearningRecord>lambdaQuery()
                .eq(LearningRecord::getUserId, userId)
                .eq(LearningRecord::getFinished, true)
                .between(LearningRecord::getFinishTime, beginTime, endTime));


        //5.查询课表数据 learning_lessons 条件userId status in(0, 1) plan_status=1 分页
        Page<LearningLesson> page = this.lambdaQuery().eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getPlanStatus, PlanStatus.PLAN_RUNNING)
                .in(LearningLesson::getStatus, LessonStatus.NOT_BEGIN, LessonStatus.LEARNING)
                .page(query.toMpPage("latest_learn_time", false));
        List<LearningLesson> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            LearningPlanPageVO vo = new LearningPlanPageVO();
            vo.setTotal(0L);
            vo.setPages(0L);
            vo.setList(CollUtils.emptyList());
            return vo;
        }

        //6.远程调用课程服务 获取课程信息
        Set<Long> courseIds = records.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());
        List<CourseSimpleInfoDTO> cInfoList = courseClient.getSimpleInfoList(courseIds);
        if (CollUtils.isEmpty(cInfoList)) {
            throw new BizIllegalException("课程不存在");
        }
        //将cInfoList转换为map结构 键是课程id 值是CourseSimpleInfoDTO对象
        Map<Long, CourseSimpleInfoDTO> cInfosMap = cInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));


        //7.查询学习记录表learning_record 本周 当前用户下 每一门课下 已学习的小节数
        //SELECT lesson_id COUNT(*) FROM learning_record
        // Where user_id = 2
        // AND finished = 1
        // AND finish_item BETWEEN '2023-05-22 00:00:01' AND '2023-05-28 23:59:59'
        // GROUP BY lesson_id
        QueryWrapper<LearningRecord> rWrapper = new QueryWrapper<>();
        rWrapper.select("lesson_id as lesson_id", "count(*) as userId");
        rWrapper.eq("user_id", userId);
        rWrapper.eq("finished", true);
        rWrapper.between("finish_time", beginTime, endTime);
        rWrapper.groupBy("lesson_id");
        List<LearningRecord> learningRecords = recordMapper.selectList(rWrapper);
        //map中的key是 lessonId value是当前用户对该课程下已学习的小节数量
        Map<Long, Long> courseWeekFinishNum = learningRecords.stream().collect(Collectors.toMap(LearningRecord::getLessonId, LearningRecord::getUserId));

        //8.封装vo返回
        LearningPlanPageVO planPageVO = new LearningPlanPageVO();
        planPageVO.setWeekTotalPlan(plansTotal);
        planPageVO.setWeekFinished(weekFinishedPlanNum);
        ArrayList<LearningPlanVO> voList = new ArrayList<>();
        for (LearningLesson record : records) {
            LearningPlanVO planVO = BeanUtils.copyBean(record, LearningPlanVO.class);
            CourseSimpleInfoDTO infoDTO = cInfosMap.get(record.getCourseId());
            if (infoDTO != null) {
                planVO.setCourseName(infoDTO.getName()); //课程名字
                planVO.setSections(infoDTO.getSectionNum()); //课程下的总小节数
            }
//            Long along = courseWeekFinishNum.get(record.getId());
//            if (along != null) {
//                planVO.setWeekLearnedSections(along.intValue()); //本周已学习的章节数
//            }else{
//                planVO.setWeekLearnedSections(0);
//            }
            planVO.setWeekLearnedSections(courseWeekFinishNum.getOrDefault(record.getId(), 0L).intValue());


            voList.add(planVO);
        }
        planPageVO.setList(voList);
        planPageVO.setTotal(page.getTotal());
        planPageVO.setPages(page.getPages());

        return planPageVO;
    }
}
