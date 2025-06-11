package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.cache.CategoryCache;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.enums.QuestionStatus;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 互动提问的问题表 服务实现类
 * </p>
 *
 * @author hercat
 * @since 2025-06-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionQuestionServiceImpl extends ServiceImpl<InteractionQuestionMapper, InteractionQuestion> implements IInteractionQuestionService {

    private final IInteractionReplyService replyService;
    private final UserClient userClient;
    private final SearchClient searchClient;
    private final CourseClient courseClient;
    private final CatalogueClient catalogueClient;
    private final CategoryCache categoryCache;

    @Override
    public void saveQuestion(QuestionFormDTO dto) {
        Long userId = UserContext.getUser();
        InteractionQuestion question = BeanUtils.copyBean(dto, InteractionQuestion.class);
        question.setUserId(userId);
        this.save(question);
    }

    @Override
    public void updateQuestion(Long id, QuestionFormDTO dto) {

        //1.校验非空
        if (StringUtils.isBlank(dto.getTitle())
                || StringUtils.isBlank(dto.getDescription())
                || dto.getAnonymity() != null) {
            throw new BadRequestException("非法参数");
        }
        //2.校验id
        InteractionQuestion question = this.getById(id);
        if (question == null) {
            throw new BadRequestException("非法参数");
        }
        //修改只能修改自己的问题
        Long userId = UserContext.getUser();
        if (!userId.equals(question.getUserId())) {
            throw new BadRequestException("不能修改别人的互动问题");
        }
        //3.dto转po
        question.setTitle(dto.getTitle());
        question.setDescription(dto.getDescription());
        question.setAnonymity(dto.getAnonymity());

        //4.修改
        this.updateById(question);
    }

    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        //1.校验参数courseId
        if (query.getCourseId() == null) {
            throw new BadRequestException("课程id不能为空");
        }
        //2.获取登录用户id
        Long userId = UserContext.getUser();
        //3.分页查询互动问题interaction_question
        //条件 courseId onlyMine为true才会加userId 小节id且不为空 hidden为false
        //分页查询 按提问时间倒序
        Page<InteractionQuestion> page = this.lambdaQuery()
                //排除字段返回
                .select(InteractionQuestion.class, tableFieldInfo -> !tableFieldInfo.getProperty().equals("description"))
                .eq(InteractionQuestion::getCourseId, query.getCourseId())
                .eq(query.getOnlyMine(), InteractionQuestion::getUserId, userId)
                .eq(query.getSectionId() != null, InteractionQuestion::getSectionId, query.getSectionId())
                .eq(InteractionQuestion::getHidden, false)
                .page(query.toMpPage("create_time", false));
        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        Set<Long> latestAnswerIds = new HashSet<>(); //互动问题的最新回答集合
        Set<Long> userIds = new HashSet<>(); //互动问题的用户id集合
        for (InteractionQuestion record : records) {
            if (!record.getAnonymity()) {
                userIds.add(record.getUserId());
            }
            if (record.getLatestAnswerId() != null) {
                latestAnswerIds.add(record.getLatestAnswerId());
            }
        }
//        Set<Long> latestAnswerIds = records.stream()
//                .filter(c -> c.getLatestAnswerId() != null)
//                .map(InteractionQuestion::getLatestAnswerId)
//                .collect(Collectors.toSet());

        //4.根据最新回答id 批量查询回答信息 interaction_reply 条件 in id集合
        Map<Long, InteractionReply> replyMap = new HashMap<>();
        if (CollUtils.isEmpty(latestAnswerIds)) {
//            List<InteractionReply> replyList = replyService.listByIds(latestAnswerIds);
            List<InteractionReply> replyList = replyService.list(Wrappers.<InteractionReply>lambdaQuery().in(InteractionReply::getId, userIds)
                    .eq(InteractionReply::getHidden, false));
            for (InteractionReply reply : replyList) {
                if (!reply.getAnonymity()) {
                    userIds.add(reply.getUserId());
                }
                replyMap.put(reply.getId(), reply);
            }
//            Map<Long, InteractionReply> replyMap1 = replyList.stream()
//                    .collect(Collectors.toMap(InteractionReply::getId, c -> c));
        }


        //5.远程调用用户服务 获取用户信息 批量
        List<UserDTO> userDTOS = userClient.queryUserByIds(userIds);
        Map<Long, UserDTO> userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, c -> c));

        //6.封装vo返回
        List<QuestionVO> list = new ArrayList<>();
        for (InteractionQuestion record : records) {
            QuestionVO vo = BeanUtils.copyBean(record, QuestionVO.class);
            if (!record.getAnonymity()) {
                UserDTO userDTO = userDTOMap.get(record.getId());
                if (userDTO != null) {
                    vo.setUserName(userDTO.getName());
                    vo.setUserIcon(userDTO.getIcon());
                }
            }
            InteractionReply reply = replyMap.get(record.getId());
            if (reply != null) {
                if (!reply.getAnonymity()) {
                    UserDTO userDTO = userDTOMap.get(reply.getUserId());
                    if (userDTO != null) {
                        vo.setLatestReplyUser(userDTO.getName()); //最新回答者的昵称
                    }
                }
                vo.setLatestReplyContent(reply.getContent()); //最新回答的内容
            }

            list.add(vo);
        }
        return PageDTO.of(page, list);
    }

    @Override
    public QuestionVO queryQuestionById(Long id) {
        //1.校验
        if (id == null) {
            throw new BadRequestException("请求参数不能为空");
        }
        //2.查询互动问题表 按主键查询
//        InteractionQuestion question = this.lambdaQuery().eq(InteractionQuestion::getId, id).one();
        InteractionQuestion question = this.getById(id);

        //3.如果该问题管理员设置了隐藏 返回空
        if (question.getHidden()) {
            return null;
        }
        //5.封装返回vo
        QuestionVO vo = BeanUtils.copyBean(question, QuestionVO.class);

        //4.如果用户是匿名提问 不用查询提问者的昵称和投降
        if (!question.getAnonymity()) {
            UserDTO userDTO = userClient.queryUserById(question.getId());
            if (userDTO != null) {
                vo.setUserName(userDTO.getName());
                vo.setUserIcon(userDTO.getIcon());
            }
        }

        return null;
    }

    @Override
    public PageDTO<QuestionAdminVO> queryQuestionAdminVOPage(QuestionAdminPageQuery query) {
        //0.如果用户传了课程的名称参数 则从es中获取该名称对应的课程id
        String courseName = query.getCourseName();
        List<Long> courseSearchIds = null;
        if (StringUtils.isBlank(courseName)) {
            courseSearchIds = searchClient.queryCoursesIdByName(courseName);  //通过feign远程调用搜索服务 从es中搜索该关键字对应的课程id
            if (CollUtils.isEmpty(courseSearchIds)) {
                return PageDTO.empty(0L, 0L);
            }
        }
        //1.查询互动列表 条件前端条件添加条件 分页 排序提问时间倒序
        Page<InteractionQuestion> page = this.lambdaQuery()
                .in(CollUtils.isNotEmpty(courseSearchIds), InteractionQuestion::getCourseId, courseSearchIds)
                .eq(query.getStatus() != null, InteractionQuestion::getStatus, query.getStatus())
                .between(query.getBeginTime() != null && query.getEndTime() != null,
                        InteractionQuestion::getCreateTime, query.getBeginTime(), query.getEndTime())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        Set<Long> userIds = new HashSet<>(); //用户id集合
        Set<Long> courseIds = new HashSet<>(); //课程id集合
        Set<Long> chapterAndSectionIds = new HashSet<>(); //章和节的id集合
        for (InteractionQuestion record : records) {
            userIds.add(record.getUserId());
            courseIds.add(record.getCourseId());
            chapterAndSectionIds.add(record.getChapterId());
            chapterAndSectionIds.add(record.getSectionId());
        }

        //2.远程调用用户服务 获取用户信息
        List<UserDTO> userDTOS = userClient.queryUserByIds(userIds);
        if (userDTOS == null) {
            throw new BizIllegalException("用户不存在");
        }
        Map<Long, UserDTO> userDTOMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, c -> c));

        //3.远程调用课程服务 获取课程信息
        List<CourseSimpleInfoDTO> courseSimpleInfoDTOS = courseClient.getSimpleInfoList(courseIds);
        if (courseSimpleInfoDTOS == null) {
            throw new BizIllegalException("课程不存在");
        }
        Map<Long, CourseSimpleInfoDTO> courseSimpleInfoDTOMap = courseSimpleInfoDTOS.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));

        //4.远程调用课程服务 获取章节信息
        List<CataSimpleInfoDTO> cataInfoDTOS = catalogueClient.batchQueryCatalogue(chapterAndSectionIds);
        if (cataInfoDTOS == null) {
            throw new BizIllegalException("章或节不存在");
        }
        Map<Long, String> cataInfoDTOMap = cataInfoDTOS.stream().collect(Collectors.toMap(CataSimpleInfoDTO::getId, c -> c.getName()));

        //6.封装vo返回
        List<QuestionAdminVO> voList = new ArrayList<>();
        for (InteractionQuestion record : records) {
            QuestionAdminVO adminVO = BeanUtils.copyBean(record, QuestionAdminVO.class);
            UserDTO userDTO = userDTOMap.get(record.getUserId());
            if (userDTO != null) {
                adminVO.setUserName(userDTO.getName());
            }
            CourseSimpleInfoDTO courseSimpleInfoDTO = courseSimpleInfoDTOMap.get(record.getCourseId());
            if (courseSimpleInfoDTO != null) {
                adminVO.setCourseName(courseSimpleInfoDTO.getName());
                //5.获取分类信息
                List<Long> categoryIds = courseSimpleInfoDTO.getCategoryIds();
                String categoryNames = categoryCache.getCategoryNames(categoryIds);
                adminVO.setCategoryName(categoryNames); //三级分类名称
            }
            adminVO.setChapterName(cataInfoDTOMap.get(record.getChapterId())); //章名称
            adminVO.setSectionName(cataInfoDTOMap.get(record.getSectionId())); //节名称
            voList.add(adminVO);
        }
        return PageDTO.of(page, voList);
    }

    @Override
    public void deleteMyQuestion(Long id) {
        InteractionQuestion question = this.getById(id);
        if (question == null) {
            throw new BizIllegalException("问题不存在");
        }
        if (!Objects.equals(question.getUserId(), UserContext.getUser())) {
            throw new BadRequestException("不能删除他人的问题");
        }
        replyService.remove(Wrappers.<InteractionReply>lambdaQuery().eq(InteractionReply::getQuestionId, question.getId()));
        this.remove(Wrappers.query(question));
    }

    @Override
    public void hideQuestion(Long id, Boolean hidden) {
        if (id == null || hidden == null) {
            throw new BadRequestException("参数错误");
        }
        this.lambdaUpdate()
                .set(InteractionQuestion::getHidden, hidden)
                .eq(InteractionQuestion::getId, id)
                .update();

    }

    @Override
    public QuestionAdminVO queryQuestionByIdAdmin(Long id) {
        // 1.根据id查询问题
        InteractionQuestion question = this.getById(id);
        if (question == null) {
            return null;
        }
        // 2.转PO为VO
        QuestionAdminVO adminVO = BeanUtils.copyBean(question, QuestionAdminVO.class);
        // 3.查询提问者信息
        UserDTO userDTO = userClient.queryUserById(question.getUserId());
        if (userDTO != null) {
            adminVO.setUserName(userDTO.getName());
            adminVO.setUserIcon(userDTO.getIcon());
        }
        //4.查询课程信息
        CourseFullInfoDTO course = courseClient.getCourseInfoById(
                question.getCourseId(), false, true);
        if (course != null) {
            //课程信息
            adminVO.setCourseName(course.getName());
            //分类信息
            adminVO.setCategoryName(categoryCache.getCategoryNames(course.getCategoryIds()));
            //教师信息
            List<Long> teacherIds = course.getTeacherIds();
            List<UserDTO> teachers = userClient.queryUserByIds(teacherIds);
            if (CollUtils.isNotEmpty(teachers)) {
                adminVO.setTeacherName(teachers.stream()
                        .map(UserDTO::getName).collect(Collectors.joining("/")));
            }
        }

        //5.查询章节信息
        List<CataSimpleInfoDTO> catas = catalogueClient.batchQueryCatalogue(
                List.of(question.getChapterId(), question.getSectionId())
        );
        Map<Long, String> cataMap = new HashMap<>(catas.size());
        if (CollUtils.isNotEmpty(catas)) {
            cataMap = catas.stream().collect(
                    Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName)
            );
        }
        //使用getOrDefault是为了简写非空校验
        adminVO.setChapterName(cataMap.getOrDefault(question.getChapterId(),""));
        adminVO.setSectionName(cataMap.getOrDefault(question.getSectionId(), ""));
        //6.疯转vo
        //更改Status的值为已查看
        this.lambdaUpdate().set(InteractionQuestion::getStatus, QuestionStatus.CHECKED).update();
        return adminVO;
    }
}
