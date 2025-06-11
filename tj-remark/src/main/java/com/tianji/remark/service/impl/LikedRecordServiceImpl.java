//package com.tianji.remark.service.impl;
//
//import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
//import com.tianji.common.constants.MqConstants;
//import com.tianji.common.utils.CollUtils;
//import com.tianji.common.utils.StringUtils;
//import com.tianji.common.utils.UserContext;
//import com.tianji.remark.domain.dto.LikeRecordFormDTO;
//import com.tianji.api.dto.msg.LikedTimesDTO;
//import com.tianji.remark.domain.po.LikedRecord;
//import com.tianji.remark.mapper.LikedRecordMapper;
//import com.tianji.remark.service.ILikedRecordService;
//import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Service;
//
//import java.util.List;
//import java.util.Set;
//import java.util.stream.Collectors;
//
///**
// * <p>
// * 点赞记录表 服务实现类
// * </p>
// *
// * @author hercat
// * @since 2025-06-04
// */
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class LikedRecordServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {
//
//    private final RabbitMqHelper rabbitMqHelper;
//
//    @Override
//    public void addLikeRecord(LikeRecordFormDTO dto) {
//        //1.获取当前用户
//        Long userId = UserContext.getUser();
//        //2.判断是否点赞 dto,liked 为true则是点赞
//        /*boolean flag = true;
//        if (dto.getLiked()) {
//            //2.1点赞逻辑
//            flag = liked(dto);
//        } else {
//            //2.2取消赞逻辑
//            flag = unliked(dto);
//        }*/
//        boolean flag = dto.getLiked() ? liked(dto, userId) : unliked(dto, userId);
//        if (!flag) { //说明点赞或者取消赞失败
//            return;
//        }
//        //3.统计该业务id的总点赞数
//        Integer totalLikesNum = this.lambdaQuery()
//                .eq(LikedRecord::getBizId, dto.getBizId())
//                .count();
//
//        //4.发消息到mq
//        String routingKey = StringUtils.format(MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE, dto.getBizType());
//        /*LikedTimesDTO msg = new LikedTimesDTO();
//        msg.setBizId(dto.getBizId());
//        msg.setLikedTimes(totalLikesNum);*/
////        LikedTimesDTO msg = LikedTimesDTO.builder().bizId(dto.getBizId()).likedTimes(totalLikesNum).build();
//        LikedTimesDTO msg = LikedTimesDTO.of(dto.getBizId(), totalLikesNum);
//        log.debug("发送点赞消息 消息内容{}",msg);
//        rabbitMqHelper.send(
//                MqConstants.Exchange.LIKE_RECORD_EXCHANGE,
//                routingKey,
//                msg
//        );
//    }
//
//    @Override
//    public Set<Long> getLikesStatusByBizIds(List<Long> bizIds) {
//        if (CollUtils.isEmpty(bizIds)) {
//            return CollUtils.emptySet();
//        }
//
//        //1.获取用户id
//        Long userId = UserContext.getUser();
//        //2.查询点赞记录表 in bizIds
//        List<LikedRecord> recordList = this.lambdaQuery()
//                .in(LikedRecord::getBizId, bizIds)
//                .eq(LikedRecord::getUserId, userId)
//                .list();
//        //3.将查询到的bizId转集合返回
//        Set<Long> likedBizIds = recordList.stream().map(LikedRecord::getBizId)
//                .collect(Collectors.toSet());
//        return likedBizIds;
//    }
//
//    private boolean liked(LikeRecordFormDTO dto, Long userId) {
//        LikedRecord record = this.lambdaQuery()
//                .eq(LikedRecord::getUserId, userId)
//                .eq(LikedRecord::getBizId, dto.getBizId())
//                .one();
//        if (record == null) {
//            //说明没有点赞记录 取消失败
//            return false;
//        }
////        return this.remove(Wrappers.<LikedRecord>lambdaQuery().eq(LikedRecord::getId, userId).eq(LikedRecord::getBizId, dto.getBizId()));
//        return this.removeById(record.getId());
//    }
//
//    private boolean unliked(LikeRecordFormDTO dto, Long userId) {
//        LikedRecord record = this.lambdaQuery()
//                .eq(LikedRecord::getUserId, userId)
//                .eq(LikedRecord::getBizId, dto.getBizId())
//                .one();
//        if (record != null) {
//            //说明之前点过赞
//            return false;
//        }
//        //保存点赞记录到表中
//        LikedRecord likedRecord = new LikedRecord();
//        likedRecord.setUserId(userId);
//        likedRecord.setBizId(dto.getBizId());
//        likedRecord.setBizType(dto.getBizType());
//        return this.save(likedRecord);
//    }
//}
