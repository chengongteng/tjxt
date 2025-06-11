package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.remark.RemarkClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.enums.QuestionStatus;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.mapper.InteractionReplyMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.tianji.learning.service.IInteractionReplyService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.tianji.common.constants.Constant.DATA_FIELD_NAME_CREATE_TIME;
import static com.tianji.common.constants.Constant.DATA_FIELD_NAME_LIKED_TIME;

/**
 * <p>
 * 互动问题的回答或评论 服务实现类
 * </p>
 *
 * @author hercat
 * @since 2025-06-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionReplyServiceImpl extends ServiceImpl<InteractionReplyMapper, InteractionReply> implements IInteractionReplyService {

    private final InteractionQuestionMapper questionMapper;
    private final UserClient userClient;
    private final RemarkClient remarkClient;

    @Override
    public void saveReply(ReplyDTO dto) {
        //1.获取登录用户id
        Long userId = UserContext.getUser();
        //2.新增回答
        InteractionReply reply = BeanUtils.copyBean(dto, InteractionReply.class);
        if (!dto.getAnonymity()) {
            reply.setUserId(userId);
        }
        this.save(reply);
        boolean flag = dto.getAnswerId() == null;
        if (flag) {
            if (reply.getQuestionId() != null) {
                // 如果是评论，累加回答次数
                this.lambdaUpdate()
                        .setSql("reply_times = reply_times + 1")
                        .eq(InteractionReply::getId, dto.getAnswerId())
                        .update();

            }
            // 更新问题表中的回答次数
            InteractionQuestion question = questionMapper.selectById(dto.getQuestionId());
            questionMapper.update(question, Wrappers.<InteractionQuestion>lambdaUpdate()
                    .setSql(!flag, "answer_times = answer_times + 1")
                    .set(!flag, InteractionQuestion::getLatestAnswerId, dto.getAnswerId())
                    .set(dto.getIsStudent(), InteractionQuestion::getStatus, QuestionStatus.UN_CHECK)
                    .eq(InteractionQuestion::getId, dto.getQuestionId()));
//                LambdaUpdateWrapper<InteractionQuestion> wrapper = new LambdaUpdateWrapper<>();
//                wrapper.eq(InteractionQuestion::getId, dto.getQuestionId());
//                wrapper.set(InteractionQuestion::getLatestAnswerId, dto.getAnswerId());
//                wrapper.set(!dto.getIsStudent(), InteractionQuestion::getStatus, false);
//                wrapper.setSql("answer_times = answer_times + 1");
//                questionMapper.update(question, wrapper);

        }
    }

    @Override
    public PageDTO<ReplyVO> queryReplyPage(ReplyPageQuery query) {
        if (query.getQuestionId() == null && query.getAnswerId() == null) {
            throw new BadRequestException("问题id和回答id不能都为空");
        }
        Page<InteractionReply> page = this.lambdaQuery()
                .eq(InteractionReply::getAnswerId, query.getAnswerId() == null ? 0L : query.getAnswerId())
                .eq(query.getQuestionId() != null, InteractionReply::getQuestionId, query.getQuestionId())
                .eq(InteractionReply::getHidden, false)
                .page(query.toMpPage(new OrderItem(DATA_FIELD_NAME_LIKED_TIME, false),
                        new OrderItem(DATA_FIELD_NAME_CREATE_TIME, false)));
        List<InteractionReply> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(0L, 0L);
        }
        Set<Long> userIds = new HashSet<>();
        Set<Long> answerIds = new HashSet<>();
        Set<Long> targetReplyIds = new HashSet<>();
        for (InteractionReply record : records) {
            if (!record.getAnonymity()) {
                userIds.add(record.getUserId());
                userIds.add(record.getTargetUserId());
            }
            if (record.getTargetReplyId() != null && record.getTargetReplyId() > 0) {
                targetReplyIds.add(record.getTargetReplyId());
            }
            answerIds.add(record.getId());
        }
        //查询目标回复 如果目标回复不是匿名 则需要查询出目标回复的用户信息
        if (targetReplyIds.size() > 0) {
            List<InteractionReply> targetReplies = listByIds(targetReplyIds);
            Set<Long> targetUserIds = targetReplies.stream().filter(Predicate.not(InteractionReply::getAnonymity))
                    .map(InteractionReply::getUserId)
                    .collect(Collectors.toSet());
            userIds.addAll(targetUserIds);
        }
        List<UserDTO> userDTOList = userClient.queryUserByIds(userIds);
        Map<Long, UserDTO> userDTOMap = new HashMap<>();
        if (userDTOList != null) {
            userDTOMap = userDTOList.stream().collect(Collectors.toMap(UserDTO::getId, c -> c));
        }
        Set<Long> bizLiked = remarkClient.getLikesStatusByBizIds(new ArrayList<>(answerIds));
        //封装vo返回
        List<ReplyVO> voList = new ArrayList<>();
        for (InteractionReply record : records) {
            ReplyVO vo = BeanUtils.copyBean(record, ReplyVO.class);
            if (!record.getAnonymity()) {
                UserDTO userDTO = userDTOMap.get(record.getUserId());
                if (userDTO != null) {
                    vo.setUserName(userDTO.getName());
                    vo.setUserIcon(userDTO.getIcon());
                    vo.setUserType(userDTO.getType());
                }
            }
            UserDTO targetUserDTO = userDTOMap.get(record.getTargetUserId());
            if (targetUserDTO != null) {
                vo.setTargetUserName(targetUserDTO.getName());
            }
            vo.setLiked(bizLiked.contains(record.getId()));
            voList.add(vo);
        }
        return PageDTO.of(page, voList);
    }
}
