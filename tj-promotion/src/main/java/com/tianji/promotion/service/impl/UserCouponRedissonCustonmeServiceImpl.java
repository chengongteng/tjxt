//package com.tianji.promotion.service.impl;
//
//import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
//import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
//import com.tianji.common.domain.dto.PageDTO;
//import com.tianji.common.exceptions.BadRequestException;
//import com.tianji.common.exceptions.BizIllegalException;
//import com.tianji.common.utils.CollUtils;
//import com.tianji.common.utils.StringUtils;
//import com.tianji.common.utils.UserContext;
//import com.tianji.promotion.domain.po.Coupon;
//import com.tianji.promotion.domain.po.ExchangeCode;
//import com.tianji.promotion.domain.po.UserCoupon;
//import com.tianji.promotion.domain.vo.CouponPageVO;
//import com.tianji.promotion.enums.CouponStatus;
//import com.tianji.promotion.enums.ExchangeCodeStatus;
//import com.tianji.promotion.mapper.CouponMapper;
//import com.tianji.promotion.mapper.UserCouponMapper;
//import com.tianji.promotion.query.UserCouponQuery;
//import com.tianji.promotion.service.IExchangeCodeService;
//import com.tianji.promotion.service.IUserCouponService;
//import com.tianji.promotion.utils.CodeUtil;
//import com.tianji.promotion.utils.MyLock;
//import com.tianji.promotion.utils.MyLockStrategy;
//import com.tianji.promotion.utils.MyLockType;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.redisson.api.RLock;
//import org.redisson.api.RedissonClient;
//import org.springframework.aop.framework.AopContext;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.time.LocalDateTime;
//import java.util.ArrayList;
//import java.util.List;
//
///**
// * <p>
// * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
// * </p>
// *
// * @author hercat
// * @since 2025-06-06
// */
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class UserCouponRedissonCustonmeServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {
//
//    private final CouponMapper couponMapper;
//    private final IExchangeCodeService exchangeCodeService;
//    private final RedissonClient redissonClient;
//    //private final @Lazy IUserCouponService userCouponService;
//
//    @Override
//    @Transactional
//    public void exchangeCoupon(String code) {
//        //1.校验code是否为空
//        if (StringUtils.isBlank(code)) {
//            throw new BadRequestException("非法参数");
//        }
//        //2.解析兑换码得到的自增id
//        long serialNum = CodeUtil.parseCode(code); //自增id
//        log.debug("自增id {}", serialNum);
//        //3.判断兑换码是否已兑换 采用redis的bitmap结构 setbit key offset 1 如果方法返回为true代表兑换码已兑换
//        boolean result = exchangeCodeService.updateExchangeCodeMark(serialNum, true);
//        if (result) {
//            //说明兑换码已经被兑换了
//            throw new BizIllegalException("兑换码已被使用");
//        }
//        try {
//            //4.判断兑换码是否存在 根据自增id查询 主键查询
//            ExchangeCode exchangeCode = exchangeCodeService.getById(serialNum);
//            if (exchangeCode == null) {
//                throw new BizIllegalException("兑换码不存在");
//            }
//            //5.判断是否过期
//            LocalDateTime now = LocalDateTime.now();
//            LocalDateTime expiredTime = exchangeCode.getExpiredTime();
//            if (now.isAfter(expiredTime)) {
//                throw new BizIllegalException("兑换码已过期");
//            }
//            //校验并生成用户券
//            Long userId = UserContext.getUser();
//            //查询优惠券信息
//            Coupon coupon = couponMapper.selectById(exchangeCode.getExchangeTargetId());
//            if (coupon == null) {
//                throw new BizIllegalException("优惠券不存在");
//            }
//            checkAndCreateUserCoupon(userId, coupon, serialNum);
//        } catch (Exception e) {
//            //10.将兑换码的状态重置
//            exchangeCodeService.updateExchangeCodeMark(serialNum, false);
//            throw e;
//        }
//    }
//
//    //领取优惠券
//    @Override
//    //@Transactional
//    public void receiveCoupon(Long id) {
//        //1.根据id查询优惠券信息 做相关校验
//        if (id == null) {
//            throw new BadRequestException("非法参数");
//        }
//        Coupon coupon = couponMapper.selectById(id);
//        if (coupon == null) {
//            throw new BadRequestException("优惠券不存在");
//        }
//        if (coupon.getStatus() != CouponStatus.ISSUING) {
//            throw new BadRequestException("该优惠券不是正在发放时期");
//        }
//        LocalDateTime now = LocalDateTime.now();
//        if (now.isBefore(coupon.getIssueBeginTime()) || now.isAfter(coupon.getIssueEndTime())) {
//            throw new BadRequestException("该优惠券已过期或未开始发放");
//        }
//        if (coupon.getTotalNum() <= 0 || coupon.getIssueNum() >= coupon.getTotalNum()) {
//            throw new BadRequestException("该优惠券库存不足");
//        }
//        Long userId = UserContext.getUser();
//        /*//获取当前用户 对该优惠券 已领数量  user_coupon 条件userid couponId 统计数量
//        Integer count = this.lambdaQuery()
//                .eq(UserCoupon::getUserId, userId)
//                .eq(UserCoupon::getCouponId, id)
//                .count();
//        if (count != null && count >= coupon.getUserLimit()) {
//            throw new BadRequestException("已达到领取上限");
//        }
//        //2.优惠券的已发放数量+1
//        couponMapper.incrIssueNum(id);
//        //3.生成优惠券
//        saveUserCoupon(userId, coupon);*/
//        //synchronized (userId.toString().intern()) {
//        //    checkAndCreateUserCoupon(userId, coupon, null);
//        //}
//        //通过synchronized获取JVM锁
//        //synchronized (userId.toString().intern()) {
//        //    //从app上下文中 获取当前类的代理对象
//        //    IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();
//        //    //checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用原代理对象的方法
//        //    userCouponServiceProxy.checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用代理对象的方法 方法是有事务处理的
//        //    //userCouponService.checkAndCreateUserCoupon(userId, coupon, null); //这种自身注入的代理仍然会触发循环依赖 使用@Lazy注解也没有用
//        //}
//
//        //通过Redisson获取分布式锁
//        //String key = "lock:coupon:uid:" + userId;
//        //RLock lock = redissonClient.getLock(key); //通过Redisson获取锁对象
//        //
//        //try {
//        //    boolean isLock = lock.tryLock(); //看门狗机制会生效 默认失效时间是30秒
//        //    if (!isLock) {
//        //        throw new BizIllegalException("操作太频繁了");
//        //    }
//        //    //从app上下文中 获取当前类的代理对象
//        //    IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();
//        //    //checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用原代理对象的方法
//        //    userCouponServiceProxy.checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用代理对象的方法 方法是有事务处理的
//        //} finally {
//        //    lock.unlock();
//        //}
//
//        //通过自定义注解
//        //String key = "lock:coupon:uid:" + userId;
//
//        //从app上下文中 获取当前类的代理对象
//        IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();
//        //checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用原代理对象的方法
//        userCouponServiceProxy.checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用代理对象的方法 方法是有事务处理的
//
//
//    }
//
//    @Override
//    @Transactional
//    @MyLock(name = "lock:coupon:#{userId}", lockType = MyLockType.RE_ENTRANT_LOCK, lockStrategy = MyLockStrategy.FAIL_FAST)
//    //@MyLock(name = "lock:coupon:#{userId}")
//    public void checkAndCreateUserCoupon(Long userId, Coupon coupon, Long serialNum) {
//        //Long类型 -128~127 之间是同一个对象  超过该区间则是不同的对象
//        //Long.toString() 方法底层是new String  所以还是不同对象
//        //Long.toString().intern() intern方法是强制从常量池中取字符串
//        //synchronized (userId.toString().intern()){
//        //1.获取当前用户 对该优惠券 已领数量 user_coupon 条件 userId couponId 统计数量
//        Integer count = this.lambdaQuery()
//                .eq(UserCoupon::getUserId, userId)
//                .eq(UserCoupon::getCouponId, coupon.getId())
//                .count();
//        if (count != null && count >= coupon.getUserLimit()) {
//            throw new BadRequestException("已达到领取上限");
//        }
//        //2.优惠券的已发放数量+1
//        couponMapper.incrIssueNum(coupon.getId());// 考虑并发控制 采用乐观锁
//        //3.生成用户券
//        saveUserCoupon(userId, coupon);
//        //4.更新兑换码的状态
//        if (serialNum != null) {
//            //修改兑换码的状态
//            exchangeCodeService.lambdaUpdate()
//                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
//                    .set(ExchangeCode::getUserId, userId)
//                    .eq(ExchangeCode::getId, serialNum)
//                    .update();
//        }
//        //}
//    }
//
//    //保存用户券
//    private void saveUserCoupon(Long userId, Coupon coupon) {
//        UserCoupon userCoupon = new UserCoupon();
//        userCoupon.setUserId(userId);
//        userCoupon.setCouponId(coupon.getId());
//        LocalDateTime termBeginTime = coupon.getTermBeginTime(); //优惠券使用 开始时间
//        LocalDateTime termEndTime = coupon.getTermEndTime(); //优惠券使用 截止时间
//        if (termEndTime == null || termBeginTime == null) {
//            termBeginTime = LocalDateTime.now();
//            termEndTime = termBeginTime.plusDays(coupon.getTermDays());
//        }
//        userCoupon.setTermBeginTime(termBeginTime);
//        userCoupon.setTermEndTime(termEndTime);
//
//        this.save(userCoupon);
//    }
//
//
//    //查询我的优惠券 分页查询
//    @Override
//    public PageDTO<CouponPageVO> queryMyCoupon(UserCouponQuery query) {
//        //1.获取登录用户的id
//        Long userId = UserContext.getUser();
//        //校验
//        if (userId == null || query == null) {
//            throw new BadRequestException("非法参数");
//        }
//        //2.根据用户id查询用户券表 user_coupon 分页查询
//        Page<UserCoupon> page = this.lambdaQuery()
//                .eq(UserCoupon::getUserId, userId)
//                .eq(UserCoupon::getStatus, CouponStatus.ISSUING) //3.筛选优惠券是否是 发放中的
//                .page(query.toMpPageDefaultSortByCreateTimeDesc());
//        List<UserCoupon> records = page.getRecords();
//        if (records == null) {
//            return PageDTO.empty(page);
//        }
//        //封装vo返回
//        /*//常规时尚代码写法
//        List<CouponPageVO> voList = new ArrayList<>();
//        for (UserCoupon record : records) {
//            Long couponId = record.getCouponId();
//            Coupon coupon = couponMapper.selectById(couponId);
//            CouponPageVO vo = new CouponPageVO();
//            vo.setId(couponId); //优惠券id
//            vo.setName(coupon.getName()); //优惠券名称
//            vo.setSpecific(coupon.getSpecific()); //是否限定范围
//            vo.setDiscountType(coupon.getDiscountType());
//            vo.setDiscountValue(coupon.getDiscountValue());
//            vo.setThresholdAmount(coupon.getThresholdAmount());
//            vo.setMaxDiscountAmount(coupon.getMaxDiscountAmount());
//            LocalDate termBegin = record.getTermBeginTime().toLocalDate();
//            LocalDate termEnd = record.getTermEndTime().toLocalDate();
//            long termDays = ChronoUnit.DAYS.between(termBegin, termEnd);
//            vo.setTermDays((int)termDays); //有效天数 terEndTime - terBeginTime
//            voList.add(vo);
//        }*/
//
//        List<Long> myCouponIds = new ArrayList<>();
//        for (UserCoupon record : records) {
//            Long cId = record.getCouponId();
//            if (cId != null) {
//                myCouponIds.add(cId);
//            }
//        }
//        List<Coupon> coupons = couponMapper.selectBatchIds(myCouponIds);
//        if (CollUtils.isEmpty(coupons)) {
//            return PageDTO.empty(page);
//        }
//        List<CouponPageVO> voList = new ArrayList<>();
//        for (Coupon coupon : coupons) {
//            if (coupon != null) {
//                CouponPageVO vo = new CouponPageVO();
//                vo.setId(coupon.getId()); //优惠券id
//                vo.setName(coupon.getName()); //优惠券名称
//                vo.setSpecific(coupon.getSpecific()); //是否限定范围
//                vo.setDiscountType(coupon.getDiscountType());
//                vo.setDiscountValue(coupon.getDiscountValue());
//                vo.setThresholdAmount(coupon.getThresholdAmount());
//                vo.setMaxDiscountAmount(coupon.getMaxDiscountAmount());
//                vo.setTermDays(coupon.getTermDays()); //可能为0 0表示指定有效期
//                voList.add(vo);
//            }
//        }
//        return PageDTO.of(page, voList);
//    }
//}
