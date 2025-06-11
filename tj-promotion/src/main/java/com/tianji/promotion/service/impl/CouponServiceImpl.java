package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.*;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.query.CouponQuery;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.ICouponService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.beans.Transient;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 优惠券的规则信息 服务实现类
 * </p>
 *
 * @author hercat
 * @since 2025-06-06
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements ICouponService {

    //优惠券的限定范围业务类
    private final ICouponScopeService couponScopeService;
    //兑换码的业务类
    private final IExchangeCodeService exchangeCodeService;
    private final IUserCouponService userCouponService;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public void saveCoupon(CouponFormDTO dto) {
        //1.dto转po 保存优惠券 coupon表
        Coupon coupon = BeanUtils.copyBean(dto, Coupon.class);
        this.save(coupon);

        //2.判断是否限定了范围 dto.specific 如果为false直接return
        if (!dto.getSpecific()) {
            return;
        }
        //3.如果dto.specific 为true  需要校验dto.scopes
        List<Long> scopes = dto.getScopes();
        if (CollUtils.isEmpty(scopes)) {
            throw new BadRequestException("分类id不能为空");
        }
        //4.保存优惠券的限定范围
        List<CouponScope> csList = new ArrayList<>();
        for (Long scope : scopes) {
            CouponScope couponScope = new CouponScope();
            couponScope.setCouponId(coupon.getId());
            couponScope.setBizId(scope);
            couponScope.setType(1);
            csList.add(couponScope);
        }
        couponScopeService.saveBatch(csList);
    }

    @Override
    public PageDTO<CouponPageVO> queryCouponPage(CouponQuery query) {
        //1.分页条件查询优惠券表 Coupon
        Page<Coupon> page = this.lambdaQuery()
                .eq(query.getType() != null, Coupon::getDiscountType, query.getType())
                .eq(query.getStatus() != null, Coupon::getStatus, query.getStatus())
                .like(StringUtils.isBlank(query.getName()), Coupon::getName, query.getName())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<Coupon> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        //2.封装vo返回
        List<CouponPageVO> voList = BeanUtils.copyList(records, CouponPageVO.class);
        return PageDTO.of(page, voList);
    }

    @Override
    public void modifyCoupon(Long id, CouponFormDTO dto) {
        if (!Objects.equals(id, dto.getId())) {
            throw new BadRequestException("请求参数id不一致");
        }
        Coupon coupon = this.getById(dto.getId());
        if (coupon == null) {
            throw new BizIllegalException("修改失败");
        }
        if (coupon.getStatus() != CouponStatus.DRAFT) {
            throw new BadRequestException("只能修改待发放的优惠券");
        }
        Coupon copyBean = BeanUtils.copyBean(dto, Coupon.class);
        this.save(copyBean);
    }

    @Override
    public void deleteCoupon(Long id, CouponFormDTO dto) {
        if (!Objects.equals(id, dto.getId())) {
            throw new BadRequestException("请求参数id不一致");
        }
        if (id == null) {
            throw new BadRequestException("请求参数错误");
        }
        Coupon coupon = this.getById(id);
        if (coupon == null) {
            throw new BizIllegalException("删除失败");
        }
        if (coupon.getStatus() != CouponStatus.DRAFT) {
            throw new BadRequestException("只能删除待发放的优惠券");
        }
        this.removeById(coupon);
    }

    @Override
    public void issueCoupon(Long id, CouponIssueFormDTO dto) {
        log.debug("发放优惠券 线程名 {}", Thread.currentThread().getName());
        //1.校验
        if (!Objects.equals(id, dto.getId()) || id == null) {
            throw new BadRequestException("参数错误");
        }
        //2.校验优惠券id是否存在
        Coupon coupon = this.getById(id);
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在");
        }
        //3.校验优惠券状态 只有待发放和暂停状态才能发放
        if (coupon.getStatus() != CouponStatus.DRAFT && coupon.getStatus() != CouponStatus.PAUSE) {
            throw new BadRequestException("只有待发放和暂停状态才能发放");
        }

        LocalDateTime now = LocalDateTime.now();
        //表示该优惠券是否立刻发放
        boolean isBeginIssue = dto.getIssueBeginTime() == null
                || !dto.getIssueBeginTime().isAfter(now);
        //4.修改优惠券的 领取开始和结束日期 使用有效期开始和结束日 天数 状态
        /*if (isBeginIssue) {
            coupon.setIssueBeginTime(now);
            coupon.setIssueEndTime(dto.getIssueEndTime());
            //如果是立即发放 优惠券状态需要更改为发放中
            coupon.setStatus(CouponStatus.ISSUING);
        }else{
            coupon.setIssueBeginTime(dto.getIssueBeginTime());
            coupon.setIssueEndTime(dto.getIssueEndTime());
            //如果是立即发放 优惠券状态需要更改为发放中
            coupon.setStatus(CouponStatus.UN_ISSUE);
        }
        coupon.setTermDays(dto.getTermDays());
        coupon.setTermBeginTime(dto.getTermBeginTime());
        coupon.setTermEndTime(dto.getTermEndTime());
        this.updateById(coupon);*/

        Coupon tmp = BeanUtils.copyBean(dto, Coupon.class);
        if (isBeginIssue) {
            tmp.setStatus(CouponStatus.ISSUING);
            tmp.setIssueBeginTime(now);
        } else {
            tmp.setStatus(CouponStatus.UN_ISSUE);
        }
        this.updateById(tmp);

        //5.如果优惠券是立刻发放 将优惠券信息(优惠券id 领券开始时间 结束时间 发行数量 限领数量) 才有HASH存入redis
        if (isBeginIssue) {
            //立刻发放
            String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + id; //prs:coupon:优惠券id
            //redisTemplate.opsForHash().put(key, "issueBeginTime", String.valueOf(DateUtils.toEpochMilli(now)));
            //redisTemplate.opsForHash().put(key, "issueEndTime",String.valueOf(DateUtils.toEpochMilli(dto.getIssueEndTime())));
            //redisTemplate.opsForHash().put(key, "totalNum",String.valueOf(coupon.getTotalNum()));
            //redisTemplate.opsForHash().put(key, "userLimit",String.valueOf(coupon.getUserLimit()));

            //使用map结构 减少与redis的交互次数
            Map<String, String> map = new HashMap<>();
            map.put("issueBeginTime", String.valueOf(DateUtils.toEpochMilli(now)));
            map.put("issueEndTime", String.valueOf(DateUtils.toEpochMilli(dto.getIssueEndTime())));
            map.put("totalNum", String.valueOf(coupon.getTotalNum()));
            map.put("userLimit", String.valueOf(coupon.getUserLimit()));
            redisTemplate.opsForHash().putAll(key, map);
        }


        //6.如果优惠券的 领取方式为指定发放 且优惠券之前的状态是待发放 需要生成兑换码
        if (coupon.getObtainWay() == ObtainType.ISSUE
                && coupon.getStatus() == CouponStatus.DRAFT) {
            //兑换码兑换的截止时间 就是优惠券领取的截止时间 该时间是从前端传的 封装到tmp中
            coupon.setIssueEndTime(tmp.getIssueEndTime());
            //异步生成兑换码
            exchangeCodeService.asyncGenerateExchangeCode(coupon);
        }
    }

    //查询正在发放中的优惠券
    @Override
    public List<CouponVO> queryIssuingCoupons() {
        //1.查询db coupon 条件 发放中 手动领取
        List<Coupon> couponList = this.lambdaQuery()
                .eq(Coupon::getStatus, CouponStatus.ISSUING)
                .eq(Coupon::getObtainWay, ObtainType.PUBLIC)
                .list();
        if (CollUtils.isEmpty(couponList)) {
            return CollUtils.emptyList();
        }
        //2.查询用户券表 user_coupon 条件 当前用户
        Set<Long> couponIds = couponList.stream().map(Coupon::getId)
                .collect(Collectors.toSet());
        List<UserCoupon> list = userCouponService.lambdaQuery()
                .eq(UserCoupon::getUserId, UserContext.getUser())
                .in(UserCoupon::getCouponId, couponIds)
                .list();
        //2.1统计当前用户 针对每一个券 的已领数量  map的键key是优惠券的id 值value是当前登录用户针对该券已领的数量
        /*//常规写法:
        Map<Long, Long> issueMap = new HashMap<>();//代表当前用户针对每一个优惠券 领取数量
        for (UserCoupon userCoupon : list) {
            Long num = issueMap.get(userCoupon.getCouponId()); //优惠券领取数量
            if (num == null) {
                issueMap.put(userCoupon.getUserId(), 1L);
            }else {
                issueMap.put(userCoupon.getCouponId(), num + 1);
            }
        }*/
        //stream流写法:
        Map<Long, Long> issueMap = list.stream()
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));

        //2.2统计当前用户 针对每一个券 的已领未使用的数量  map的键key是优惠券的id 值value是当前登录用户针对该券已领未使用的数量
        //stream流写法:
        Map<Long, Long> unUseMap = list.stream()
                .filter(c -> c.getStatus() == UserCouponStatus.UNUSED)
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));

        //2.po转vo
        List<CouponVO> voList = new ArrayList<>();
        for (Coupon coupon : couponList) {
            CouponVO vo = BeanUtils.copyBean(coupon, CouponVO.class);
            //优惠券还有剩余 (issue_name < total_num)
            // 且已领数量未超过限领数量 (统计用户券表user_coupon取出当前用户已领数量 < user_limit)
            Long issNum = issueMap.getOrDefault(coupon.getId(), 0L);
            boolean available = coupon.getIssueNum() < coupon.getTotalNum() && issNum.intValue() < coupon.getUserLimit();
            vo.setAvailable(available); //是否可以领取
            //统计用户券user_coupon取出当前用户已领未使用的券数量
            boolean received = unUseMap.getOrDefault(coupon.getId(), 0L) > 0;
            vo.setReceived(received); //是否可以使用
            voList.add(vo);
        }

        return voList;
    }
}














