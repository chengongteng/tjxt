package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSearchDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.api.dto.leanring.LearningRecordDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.SectionType;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.tianji.learning.service.ILearningRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.utils.LearningRecordDelayTaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 学习记录表 服务实现类
 * </p>
 *
 * @author hercat
 * @since 2025-05-29
 */
@Service
@RequiredArgsConstructor
public class LearningRecordServiceImpl extends ServiceImpl<LearningRecordMapper, LearningRecord> implements ILearningRecordService {

    final ILearningLessonService learningLesson;
    final CourseClient courseClient;
    final LearningRecordDelayTaskHandler taskHandler;

    @Override
    public LearningLessonDTO queryLearningRecordByCourse(Long courseId) {
        //1.获取当前登录用户id
        Long userId = UserContext.getUser();

        //2.查询课表信息 条件user_id和course_id
        LearningLesson lesson = learningLesson.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (lesson == null) {
            throw new BizIllegalException("该课程未加入学习课表");
        }
        //3.查询学习记录 条件lesson_id和user_id
        List<LearningRecord> list = this.lambdaQuery()
                .eq(LearningRecord::getUserId, userId)
                .eq(LearningRecord::getLessonId, lesson.getId())
                .list();
        if (CollUtils.isEmpty(list)) {
            throw new BizIllegalException("查询为空");
        }
        //4.封装结果返回
        LearningLessonDTO learningLessonDTO = new LearningLessonDTO();
        learningLessonDTO.setId(lesson.getId());
        learningLessonDTO.setLatestSectionId(lesson.getLatestSectionId());
        List<LearningRecordDTO> learningRecordDTOS = BeanUtils.copyList(list, LearningRecordDTO.class);
        learningLessonDTO.setRecords(learningRecordDTOS);
        return learningLessonDTO;
    }

    @Override
    public void addLearningRecord(LearningRecordFormDTO dto) {
        //1.获取当前登录的用户id
        Long userId = UserContext.getUser();

        //2.处理学习记录
        boolean isFinished = false;

        SectionType sectionType = dto.getSectionType();
        if (sectionType.equals(SectionType.VIDEO)) {
            //2.1提交视频播放记录
            isFinished = handleViedeRecord(userId, dto);
        } else {
            //2.2提交考试记录
            isFinished = handleExamRecord(userId, dto);
        }

        //3.处理课表数据
        if (!isFinished) {
            return;
        }
        handleLessonData(dto);
    }

    //处理课表相关数据
    private void handleLessonData(LearningRecordFormDTO dto) {
        //1.查询课表 learning_lesson 条件lesson_id主键
        LearningLesson lesson = learningLesson.lambdaQuery().eq(LearningLesson::getId, dto.getLessonId()).one();
        if (lesson == null) {
            throw new BizIllegalException("课表不存在");
        }
        //2.判断是否是第一次学完 isFinished是不是true
        boolean allFinish = false;
        //3.远程调用课程服务 得到课程信息 小节总数
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(dto.getSectionId(), false, false);
        //4.如果isFinished为true 本小节是第一次学完 判断用户对该课程下全部小节是否学完
        Integer sectionNum = cInfo.getSectionNum();
        Integer learnedSections = lesson.getLearnedSections();
        allFinish = learnedSections + 1 >= sectionNum;
        //5.更新课表数据
        learningLesson.lambdaUpdate()
                .set(lesson.getStatus() == LessonStatus.NOT_BEGIN, LearningLesson::getStatus, LessonStatus.LEARNING)
                .set(allFinish, LearningLesson::getStatus, LessonStatus.FINISHED)
                .set(LearningLesson::getLatestSectionId, dto.getSectionId())
                .set(LearningLesson::getLatestLearnTime, dto.getCommitTime())
//                .set(LearningLesson::getLearnedSections, lesson.getLearnedSections() + 1)
                .setSql("learned_sections = learned_sections + 1")
                .eq(LearningLesson::getId, dto.getLessonId())
                .update();
    }

    private boolean handleViedeRecord(Long userId, LearningRecordFormDTO dto) {
        //1.查询旧的学习记录 learning_record 条件查询 uerId lessonId sectionId
        LearningRecord learningRecord = queryOldRecord(dto.getLessonId(), dto.getSectionId());
//        LearningRecord learningRecord = this.lambdaQuery().eq(LearningRecord::getUserId, userId)
//                .eq(LearningRecord::getLessonId, dto.getLessonId())
//                .eq(LearningRecord::getSectionId, dto.getSectionId())
//                .one();
        //2.判断是否存在
        if (learningRecord == null) {
            //3.如果不存在则新增学习记录
            LearningRecord record = BeanUtils.copyBean(dto, LearningRecord.class);

            boolean result = this.save(record);
            if (!result) {
                throw new DbException("新增考试记录失败");
            }
            return false; //代表本小节没有学习完
        }

        //4.如果存在则更新学习记录 learning_record 更新字段 moment
        //判断本小节是否是第一次学完 isFinished为true代表第一次学完
        boolean isFinished = !learningRecord.getFinished() && dto.getMoment() * 2 >= dto.getDuration();
        //如果不是第一次学完 则返回缓存
        if (!isFinished) {
            LearningRecord record = new LearningRecord();
            record.setLessonId(dto.getLessonId());
            record.setMoment(dto.getMoment());
            record.setFinished(learningRecord.getFinished());
            record.setId(learningRecord.getId());
            taskHandler.addLearningRecordTask(record);
            return false;
        }
        boolean result = this.lambdaUpdate().set(LearningRecord::getMoment, dto.getMoment())
                .set(isFinished, LearningRecord::getFinished, true)
                .set(isFinished, LearningRecord::getFinishTime, dto.getCommitTime())
                .update();
        if (!result) {
            throw new DbException("更新学习记录失败");
        }

        //清除缓存
        taskHandler.cleanRecordCache(dto.getLessonId(), dto.getSectionId());

        return true;
    }

    private LearningRecord queryOldRecord(Long lessonId, Long sectionId) {
        //1.查缓存
        LearningRecord fromCache = taskHandler.readRecordCache(lessonId, sectionId);
        //2.如果命中直接返回
        if (fromCache != null) {
            return fromCache;
        }
        //3.如果未命中 查询db
        LearningRecord fromDB = this.lambdaQuery().eq(LearningRecord::getLessonId, lessonId)
                .eq(LearningRecord::getSectionId, sectionId)
                .one();
        //4.如果db中仍然没有数据 说明是第一次写入db return null
        if (fromDB == null) {
            return null;
        }
        //5.写回缓存
        taskHandler.writeRecordCache(fromDB);

        return fromDB;
    }

    //处理考试记录
    private boolean handleExamRecord(Long userId, LearningRecordFormDTO dto) {
        //1.将dto转换为po
        LearningRecord record = BeanUtils.copyBean(dto, LearningRecord.class);
        record.setUserId(userId);
        record.setFinished(true); //提交考试记录 代表本小节已学完
        record.setFinishTime(dto.getCommitTime());
        //2.保存学习记录到learning_record
        boolean result = this.save(record);
        if (!result) {
            throw new DbException("新增考试记录失败");
        }
        return true;
    }
}
