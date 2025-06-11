package com.tianji.promotion.service.impl;

import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.discount.Discount;
import com.tianji.promotion.discount.DiscountStrategy;
import com.tianji.promotion.domain.dto.CouponDiscountDTO;
import com.tianji.promotion.domain.dto.OrderCourseDTO;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.DiscountType;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.query.UserCouponQuery;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.utils.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.aop.aspectj.annotation.LazySingletonAspectInstanceFactoryDecorator;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
 * </p>
 *
 * @author hercat
 * @since 2025-06-06
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCouponMqServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {

    private final CouponMapper couponMapper;
    private final IExchangeCodeService exchangeCodeService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    //private final @Lazy IUserCouponService userCouponService;
    private final RabbitMqHelper mqHelper;
    private final ICouponScopeService couponScopeService;
    private final Executor calculateSolutionExecutor;

    @Override
    @Transactional
    public void exchangeCoupon(String code) {
        //1.校验code是否为空
        if (StringUtils.isBlank(code)) {
            throw new BadRequestException("非法参数");
        }
        //2.解析兑换码得到的自增id
        long serialNum = CodeUtil.parseCode(code); //自增id
        log.debug("自增id {}", serialNum);
        //3.判断兑换码是否已兑换 采用redis的bitmap结构 setbit key offset 1 如果方法返回为true代表兑换码已兑换
        boolean result = exchangeCodeService.updateExchangeCodeMark(serialNum, true);
        if (result) {
            //说明兑换码已经被兑换了
            throw new BizIllegalException("兑换码已被使用");
        }
        try {
            //4.判断兑换码是否存在 根据自增id查询 主键查询
            ExchangeCode exchangeCode = exchangeCodeService.getById(serialNum);
            if (exchangeCode == null) {
                throw new BizIllegalException("兑换码不存在");
            }
            //5.判断是否过期
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expiredTime = exchangeCode.getExpiredTime();
            if (now.isAfter(expiredTime)) {
                throw new BizIllegalException("兑换码已过期");
            }
            //校验并生成用户券
            Long userId = UserContext.getUser();
            //查询优惠券信息
            Coupon coupon = couponMapper.selectById(exchangeCode.getExchangeTargetId());
            if (coupon == null) {
                throw new BizIllegalException("优惠券不存在");
            }
            checkAndCreateUserCoupon(userId, coupon, serialNum);
        } catch (Exception e) {
            //10.将兑换码的状态重置
            exchangeCodeService.updateExchangeCodeMark(serialNum, false);
            throw e;
        }
    }

    //领取优惠券
    @Override
    //@Transactional
    //分布式锁可以 对 优惠券枷锁
    @MyLock(name = "lock:coupon:uid:#{id}")
    public void receiveCoupon(Long id) {
        //1.根据id查询优惠券信息 做相关校验
        if (id == null) {
            throw new BadRequestException("非法参数");
        }

        //Coupon coupon = couponMapper.selectById(id);
        //从redis中获取coupon优惠券信息
        Coupon coupon = queryCouponByCache(id);
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在");
        }
        //校验优惠券是否在发放时间内
        //if (coupon.getStatus() != CouponStatus.ISSUING) {
        //    throw new BadRequestException("该优惠券不是正在发放时期");
        //}
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getIssueBeginTime()) || now.isAfter(coupon.getIssueEndTime())) {
            throw new BadRequestException("该优惠券已过期或未开始发放");
        }
        //校验库存
        //if (coupon.getTotalNum() <= 0 || coupon.getIssueNum() >= coupon.getTotalNum()) {
        if (coupon.getTotalNum() <= 0) {
            throw new BadRequestException("该优惠券库存不足");
        }
        Long userId = UserContext.getUser();
        /*//获取当前用户 对该优惠券 已领数量  user_coupon 条件userid couponId 统计数量
        Integer count = this.lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getCouponId, id)
                .count();
        if (count != null && count >= coupon.getUserLimit()) {
            throw new BadRequestException("已达到领取上限");
        }
        //2.优惠券的已发放数量+1
        couponMapper.incrIssueNum(id);
        //3.生成优惠券
        saveUserCoupon(userId, coupon);*/
        //synchronized (userId.toString().intern()) {
        //    checkAndCreateUserCoupon(userId, coupon, null);
        //}
        //通过synchronized获取JVM锁
        //synchronized (userId.toString().intern()) {
        //    //从app上下文中 获取当前类的代理对象
        //    IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();
        //    //checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用原代理对象的方法
        //    userCouponServiceProxy.checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用代理对象的方法 方法是有事务处理的
        //    //userCouponService.checkAndCreateUserCoupon(userId, coupon, null); //这种自身注入的代理仍然会触发循环依赖 使用@Lazy注解也没有用
        //}

        //通过Redisson获取分布式锁
        //String key = "lock:coupon:uid:" + userId;
        //RLock lock = redissonClient.getLock(key); //通过Redisson获取锁对象
        //
        //try {
        //    boolean isLock = lock.tryLock(); //看门狗机制会生效 默认失效时间是30秒
        //    if (!isLock) {
        //        throw new BizIllegalException("操作太频繁了");
        //    }
        //    //从app上下文中 获取当前类的代理对象
        //    IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();
        //    //checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用原代理对象的方法
        //    userCouponServiceProxy.checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用代理对象的方法 方法是有事务处理的
        //} finally {
        //    lock.unlock();
        //}

        //通过自定义注解
        //String key = "lock:coupon:uid:" + userId;

        //从app上下文中 获取当前类的代理对象

        //统计已领取数量
        String key = PromotionConstants.USER_COUPON_CACHE_KEY_PREFIX + id;
        //increment 表示本次领取后的数量 已领数量
        Long increment = redisTemplate.opsForHash().increment(key, userId.toString(), 1);
        //校验是否超过限领数量
        if (increment > coupon.getUserLimit()) { //由于increment是+1之后的结果 所以此处只能判断大于 不能写等于
            throw new BizIllegalException("超出限领数量");
        }
        //修改优惠券的库存 -1
        String couponKey = PromotionConstants.COUPON_CACHE_KEY_PREFIX + id;
        redisTemplate.opsForHash().increment(couponKey, "totalNum", -1);

        //发送消息到mq 消息的内存为 userId couponId
        UserCouponDTO msg = new UserCouponDTO();
        msg.setUserId(userId);
        msg.setCouponId(id);
        mqHelper.send(MqConstants.Exchange.PROMOTION_EXCHANGE,
                MqConstants.Key.COUPON_RECEIVE,
                msg);

        //IUserCouponService userCouponServiceProxy = (IUserCouponService) AopContext.currentProxy();
        ////checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用原代理对象的方法
        //userCouponServiceProxy.checkAndCreateUserCoupon(userId, coupon, null);//这种写法是调用代理对象的方法 方法是有事务处理的


    }

    /**
     * 从redis中获取coupon对象
     *
     * @param id coupon的id
     * @return coupon 从redis中获取到的coupon优惠券对象
     */
    private Coupon queryCouponByCache(Long id) {
        //1.拼接key
        String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + id;
        //2.从redis获取数据
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        Coupon coupon = BeanUtils.mapToBean(entries, Coupon.class, false, CopyOptions.create());
        return coupon;
    }

    @Override
    @Transactional
    @MyLock(name = "lock:coupon:uid:#{userId}",
            waitTime = 1,
            leaseTime = 5,
            unit = TimeUnit.SECONDS,
            lockType = MyLockType.RE_ENTRANT_LOCK,
            lockStrategy = MyLockStrategy.FAIL_AFTER_RETRY_TIMEOUT)
    //@MyLock(name = "lock:coupon:#{userId}")
    public void checkAndCreateUserCoupon(Long userId, Coupon coupon, Long serialNum) {
        //Long类型 -128~127 之间是同一个对象  超过该区间则是不同的对象
        //Long.toString() 方法底层是new String  所以还是不同对象
        //Long.toString().intern() intern方法是强制从常量池中取字符串
        //synchronized (userId.toString().intern()){
        //1.获取当前用户 对该优惠券 已领数量 user_coupon 条件 userId couponId 统计数量
        Integer count = this.lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getCouponId, coupon.getId())
                .count();
        if (count != null && count >= coupon.getUserLimit()) {
            throw new BadRequestException("已达到领取上限");
        }
        //2.优惠券的已发放数量+1
        int num = couponMapper.incrIssueNum(coupon.getId());// 考虑并发控制 采用乐观锁
        if (num == 0) {
            throw new BizIllegalException("券已领完");
        }
        //3.生成用户券
        saveUserCoupon(userId, coupon);
        //4.更新兑换码的状态
        if (serialNum != null) {
            //修改兑换码的状态
            exchangeCodeService.lambdaUpdate()
                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
                    .set(ExchangeCode::getUserId, userId)
                    .eq(ExchangeCode::getId, serialNum)
                    .update();
        }
        //}
    }

    //保存用户券
    private void saveUserCoupon(Long userId, Coupon coupon) {
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setUserId(userId);
        userCoupon.setCouponId(coupon.getId());
        LocalDateTime termBeginTime = coupon.getTermBeginTime(); //优惠券使用 开始时间
        LocalDateTime termEndTime = coupon.getTermEndTime(); //优惠券使用 截止时间
        if (termEndTime == null || termBeginTime == null) {
            termBeginTime = LocalDateTime.now();
            termEndTime = termBeginTime.plusDays(coupon.getTermDays());
        }
        userCoupon.setTermBeginTime(termBeginTime);
        userCoupon.setTermEndTime(termEndTime);

        this.save(userCoupon);
    }


    //查询我的优惠券 分页查询
    @Override
    public PageDTO<CouponPageVO> queryMyCoupon(UserCouponQuery query) {
        //1.获取登录用户的id
        Long userId = UserContext.getUser();
        //校验
        if (userId == null || query == null) {
            throw new BadRequestException("非法参数");
        }
        //2.根据用户id查询用户券表 user_coupon 分页查询
        Page<UserCoupon> page = this.lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getStatus, CouponStatus.ISSUING) //3.筛选优惠券是否是 发放中的
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<UserCoupon> records = page.getRecords();
        if (records == null) {
            return PageDTO.empty(page);
        }
        //封装vo返回
        /*//常规时尚代码写法
        List<CouponPageVO> voList = new ArrayList<>();
        for (UserCoupon record : records) {
            Long couponId = record.getCouponId();
            Coupon coupon = couponMapper.selectById(couponId);
            CouponPageVO vo = new CouponPageVO();
            vo.setId(couponId); //优惠券id
            vo.setName(coupon.getName()); //优惠券名称
            vo.setSpecific(coupon.getSpecific()); //是否限定范围
            vo.setDiscountType(coupon.getDiscountType());
            vo.setDiscountValue(coupon.getDiscountValue());
            vo.setThresholdAmount(coupon.getThresholdAmount());
            vo.setMaxDiscountAmount(coupon.getMaxDiscountAmount());
            LocalDate termBegin = record.getTermBeginTime().toLocalDate();
            LocalDate termEnd = record.getTermEndTime().toLocalDate();
            long termDays = ChronoUnit.DAYS.between(termBegin, termEnd);
            vo.setTermDays((int)termDays); //有效天数 terEndTime - terBeginTime
            voList.add(vo);
        }*/

        List<Long> myCouponIds = new ArrayList<>();
        for (UserCoupon record : records) {
            Long cId = record.getCouponId();
            if (cId != null) {
                myCouponIds.add(cId);
            }
        }
        List<Coupon> coupons = couponMapper.selectBatchIds(myCouponIds);
        if (CollUtils.isEmpty(coupons)) {
            return PageDTO.empty(page);
        }
        List<CouponPageVO> voList = new ArrayList<>();
        for (Coupon coupon : coupons) {
            if (coupon != null) {
                CouponPageVO vo = new CouponPageVO();
                vo.setId(coupon.getId()); //优惠券id
                vo.setName(coupon.getName()); //优惠券名称
                vo.setSpecific(coupon.getSpecific()); //是否限定范围
                vo.setDiscountType(coupon.getDiscountType());
                vo.setDiscountValue(coupon.getDiscountValue());
                vo.setThresholdAmount(coupon.getThresholdAmount());
                vo.setMaxDiscountAmount(coupon.getMaxDiscountAmount());
                vo.setTermDays(coupon.getTermDays()); //可能为0 0表示指定有效期
                voList.add(vo);
            }
        }
        return PageDTO.of(page, voList);
    }


    @Override
    @Transactional
    //@MyLock(name = "lock:coupon:#{userId}",
    //        waitTime = 1,
    //        leaseTime = 5,
    //        unit = TimeUnit.SECONDS,
    //        lockType = MyLockType.RE_ENTRANT_LOCK,
    //        lockStrategy = MyLockStrategy.FAIL_AFTER_RETRY_TIMEOUT)
    //@MyLock(name = "lock:coupon:#{userId}")
    public void checkAndCreateUserCouponNew(UserCouponDTO msg) {
        //Long类型 -128~127 之间是同一个对象  超过该区间则是不同的对象
        //Long.toString() 方法底层是new String  所以还是不同对象
        //Long.toString().intern() intern方法是强制从常量池中取字符串
        //synchronized (userId.toString().intern()){
        //1.获取当前用户 对该优惠券 已领数量 user_coupon 条件 userId couponId 统计数量
        //Integer count = this.lambdaQuery()
        //        .eq(UserCoupon::getUserId, userId)
        //        .eq(UserCoupon::getCouponId, coupon.getId())
        //        .count();
        //if (count != null && count >= coupon.getUserLimit()) {
        //    throw new BadRequestException("已达到领取上限");
        //}

        //1.从db中查询优惠券信息
        Coupon coupon = couponMapper.selectById(msg.getCouponId());
        if (coupon == null) {
            return;
        }

        //2.优惠券的已发放数量+1
        int num = couponMapper.incrIssueNum(coupon.getId());// 考虑并发控制 采用乐观锁
        if (num == 0) {
            return;
        }
        //3.生成用户券
        saveUserCoupon(msg.getUserId(), coupon);
        //4.更新兑换码的状态
        //if (serialNum != null) {
        //    //修改兑换码的状态
        //    exchangeCodeService.lambdaUpdate()
        //            .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
        //            .set(ExchangeCode::getUserId, userId)
        //            .eq(ExchangeCode::getId, serialNum)
        //            .update();
        //}
        ////}
    }

    @Override
    public List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> courses) {
        //1.查询当前用户可用的优惠券 user_coupon 和 coupon
        //条件 userId status=1    查找字段 优惠券的规则 优惠券id 用户券id
        List<Coupon> coupons = getBaseMapper().queryMyCoupons(UserContext.getUser());
        if (CollUtils.isEmpty(coupons)) {
            return CollUtils.emptyList();
        }
        //2.初筛
        //2.1计算订单的总金额 对courses的price累加
        int totalAmount = courses.stream().mapToInt(OrderCourseDTO::getPrice).sum();

        //2.2校验优惠券是否可用
        //List<Coupon> availableCoupons = new ArrayList<>();
        //for (Coupon coupon : coupons) {
        //    boolean flag = DiscountStrategy.getDiscount(coupon.getDiscountType()).canUse(totalAmount, coupon);
        //    if (flag) {
        //        //值为true 表示该优惠券可用
        //        availableCoupons.add(coupon);
        //    }
        //}
        List<Coupon> availableCoupons = coupons.stream()
                .filter(coupon -> DiscountStrategy.getDiscount(coupon.getDiscountType())
                        .canUse(totalAmount, coupon))
                .collect(Collectors.toList());
        if (CollUtils.isEmpty(availableCoupons)) {
            return CollUtils.emptyList();
        }
        //3.细筛 (需要考虑优惠券的限定范围)
        Map<Coupon, List<OrderCourseDTO>> avaMap = findAvailableCoupons(availableCoupons, courses);
        if (avaMap.isEmpty()) {
            return CollUtils.emptyList();
        }
        Set<Map.Entry<Coupon, List<OrderCourseDTO>>> entries = avaMap.entrySet();
        for (Map.Entry<Coupon, List<OrderCourseDTO>> entry : entries) {
            log.debug("细筛之后的欧蕙荃：{}, {}",
                    DiscountStrategy.getDiscount(entry.getKey().getDiscountType()).getRule(entry.getKey()),
                    entry.getKey());
            List<OrderCourseDTO> value = entry.getValue();
            for (OrderCourseDTO courseDTO : value) {
                log.debug("可用课程 {}", courseDTO);
            }
        }

        availableCoupons = new ArrayList<>(avaMap.keySet()); //才是真正可用的优惠券集合
        log.debug("经过细筛之后的 优惠券个数 {}", availableCoupons);
        for (Coupon coupon : availableCoupons) {
            log.debug("优惠券：{}, {}",
                    DiscountStrategy.getDiscount(coupon.getDiscountType()).getRule(coupon),
                    coupon);
        }
        //排列组合
        List<List<Coupon>> solutions = PermuteUtil.permute(availableCoupons);
        for (Coupon availableCoupon : availableCoupons) {
            solutions.add(List.of(availableCoupon)); //添加单券到集合中
        }
        log.debug("排列组合");
        for (List<Coupon> solution : solutions) {
            List<Long> cIs = solution.stream().map(Coupon::getId).collect(Collectors.toList());
            log.debug("{}", cIs);
        }

        //4.计算每一种组合的优惠明细
        //log.debug("开始计算 每一种组合的优惠明细");
        //List<CouponDiscountDTO> dtos = new ArrayList<>();
        //for (List<Coupon> solution : solutions) {
        //    CouponDiscountDTO dto =  calculateSolutionDiscount(avaMap, courses, solution);
        //    log.debug("方案最终优惠 {} 方案中优惠券使用了 {} 规则 {} ", dto.getDiscountAmount(), dto.getIds(), dto.getRules());
        //    dtos.add(dto);
        //}

        //5.使用多线程改造第4步 并行计算每一种组合的优惠明细
        log.debug("多线程计算 每一种组合的优惠明细");
        //List<CouponDiscountDTO> dtos = new ArrayList<>(); //线程不安全
        List<CouponDiscountDTO> dtos = Collections.synchronizedList(new ArrayList<>(solutions.size()));//线程安全的集合
        CountDownLatch lath = new CountDownLatch(solutions.size());
        for (List<Coupon> solution : solutions) {
            CompletableFuture.supplyAsync(new Supplier<CouponDiscountDTO>() {
                @Override
                public CouponDiscountDTO get() {
                    log.debug("线程 {} 开始计算方案 {}", Thread.currentThread().getName(),
                            solution.stream().map(Coupon::getId).collect(Collectors.toSet()));
                    CouponDiscountDTO dto = calculateSolutionDiscount(avaMap, courses, solution);
                    return dto;
                }
            }, calculateSolutionExecutor).thenAccept(new Consumer<CouponDiscountDTO>() {
                @Override
                public void accept(CouponDiscountDTO dto) {
                    log.debug("方案族最终优惠 {} 方案种优惠券使用了 {}  规则 {}", dto.getDiscountAmount(), dto.getIds(), dto.getRules());
                    dtos.add(dto);
                    lath.countDown(); //计算器减一
                }
            });
        }

        try {
            lath.await(2, TimeUnit.SECONDS); //主线程最多会阻塞2秒
        } catch (InterruptedException e) {
            log.debug("多线程计算泽合优惠明细 报错了", e);
        }
        //6.筛选最优解
        return findBestSolution(dtos);
    }

    /**
     * 求最优解
     * - 用券相同时 优惠金额最高的方案
     * - 优惠金额相同时 用券最少的方案
     *
     * @param solutions
     * @return
     */
    private List<CouponDiscountDTO> findBestSolution(List<CouponDiscountDTO> solutions) {
        //1.创建两个map 分别记录涌泉相同 金额最高   金额相同 用券最少
        Map<String, CouponDiscountDTO> moreDiscountMap = new HashMap<>();
        Map<Integer, CouponDiscountDTO> lessCouponMap = new HashMap<>();
        //2.循环方案 向map种记录 用券相同 金额最高   金额相同 用券最少
        for (CouponDiscountDTO solution : solutions) {
            //2.1 对优惠券id转字符串 升序 转字符串 然后以逗号拼接
            String ids = solution.getIds().stream().sorted(Comparator.comparing(Long::longValue))
                    .map(String::valueOf).collect(Collectors.joining(","));
            //2.2 从moreDiscountMap中取 旧的记录 判断 如果当前方案的优惠金额 小于 旧的方案金额 当前方案忽略 处理下一个方案
            CouponDiscountDTO old = moreDiscountMap.get(ids);
            if (old != null && old.getDiscountAmount() >= solution.getDiscountAmount()) {
                continue;
            }
            //2.3 从lessCouponMap中取 旧的记录 判断 如果旧的涌泉方案 用券数量 小于 当前方案的用券数量 当前方案忽略 处理下一个方案
            old = lessCouponMap.get(solution.getDiscountAmount());
            if (old != null && solution.getIds().size() > 1 && old.getIds().size() <= solution.getIds().size()) {
                continue;
            }
            //2.4 添加更优方案到map中
            moreDiscountMap.put(ids, solution); //说明当前方案 更优
            lessCouponMap.put(solution.getDiscountAmount(), solution); //说明当前方案 更优
        }

        //3.求两个map的交集
        Collection<CouponDiscountDTO> bestSolution = CollUtils.intersection(moreDiscountMap.values(), lessCouponMap.values());

        //4.对最终的方案结果 按优惠金额 倒序
        List<CouponDiscountDTO> latestBestSolution = bestSolution.stream()
                .sorted(Comparator.comparing(CouponDiscountDTO::getDiscountAmount).reversed())
                .collect(Collectors.toList());
        return latestBestSolution;

    }

    /**
     * 计算每一个方案的 优惠信息
     *
     * @param avaMap   优惠券和可用课程的映射集合
     * @param courses  订单中所有的课程
     * @param solution 方案结果
     * @return
     */
    private CouponDiscountDTO calculateSolutionDiscount(Map<Coupon, List<OrderCourseDTO>> avaMap, List<OrderCourseDTO> courses, List<Coupon> solution) {
        //1.创建方案结果dto对象
        CouponDiscountDTO dto = new CouponDiscountDTO();
        //2.初始化商品id和商品折扣明细的映射 初始折扣明细全部设置为0 设置map结构 key为商品id value初始值都为0
        Map<Long, Integer> detailMap = courses.stream().collect(Collectors.toMap(OrderCourseDTO::getId, OrderCourseDTO -> 0));
        //3.计算方案的折扣信息
        //3.1循环方案中的优惠券
        for (Coupon coupon : solution) {
            //3.2取出该优惠券对应的可用课程
            List<OrderCourseDTO> availableCourses = avaMap.get(coupon);
            //3.3计算可用而课程的总金额(商品的价格 - 该商品的折扣明细)
            int totalAmount = availableCourses.stream()
                    .mapToInt(value -> value.getPrice() - detailMap.get(value.getId()))
                    .sum();
            //3.4判断优惠券是否可用
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (!discount.canUse(totalAmount, coupon)) {
                continue; //该券不可用 跳出循环 继续处理下一个券
            }
            //3.5计算该优惠券使用后的折扣值
            int discountAmount = discount.calculateDiscount(totalAmount, coupon);
            //3.6计算商品的折扣明细(更新商品id和对应商品折扣明细) 更新到detailMap
            calculateDetailDiscount(detailMap, availableCourses, totalAmount, discountAmount);
            //3.7累加每一个优惠券优惠的金额 赋值给方案结果dto对象
            dto.getIds().add(coupon.getId()); //只要执行到这一步 该优惠券生效
            dto.getRules().add(discount.getRule(coupon));
            dto.setDiscountAmount(discountAmount + dto.getDiscountAmount()); //不能覆盖 应该是所有生效的优惠券累加的结果

        }


        return null;
    }

    /**
     * 计算商品 折扣明细
     *
     * @param detailMap        商品id和商品的优惠明细 映射
     * @param availableCourses 当前优惠券的可用课程集合
     * @param totalAmount      可用课程的总金额
     * @param discountAmount   当前优惠券能优惠的金额
     */
    private void calculateDetailDiscount(Map<Long, Integer> detailMap, List<OrderCourseDTO> availableCourses, int totalAmount, int discountAmount) {
        //目的 本方法就是优惠券在使用后 计算每个商品的折扣明细
        //规则 前面的商品按比例计算 最后一个商品折扣明细 = 总的优惠金额 - 前面商品优惠的总额
        int times = 0; //代表已处理的商品个数
        int remainDiscount = discountAmount; //代表剩余的优惠金额
        int discount = 0;
        for (OrderCourseDTO c : availableCourses) {
            times++;
            if (times == availableCourses.size()) {
                //说明是最后一个课程 总优惠金额 - 前面商品的总优惠金额
                discount = remainDiscount;
            } else {
                //是前面的课程 按比例
                discount = c.getPrice() * discountAmount / totalAmount; //此处先乘再除 否则可能会为0
                remainDiscount = remainDiscount - discount;
            }
            //将商品的折扣明细添加到 detailMap
            detailMap.put(c.getId(), discount + detailMap.get(c.getId()));
        }
    }

    /**
     * 细筛 查询每一个优惠券 对应的可用课程
     *
     * @param coupons      初筛之后的优惠券集合
     * @param orderCourses 订单中的课程集合
     * @return
     */
    private Map<Coupon, List<OrderCourseDTO>> findAvailableCoupons(List<Coupon> coupons,
                                                                   List<OrderCourseDTO> orderCourses) {
        Map<Coupon, List<OrderCourseDTO>> map = new HashMap<>();
        //1.循环遍历初筛后的的优惠券集合
        for (Coupon coupon : coupons) {
            //2.找出每一个优惠券的可用课程
            //2.1判断优惠券是否限定了范围 coupon.specific为true
            List<OrderCourseDTO> availableCourses = orderCourses;
            if (coupon.getSpecific()) {
                //2.2查询限定范围 查询coupon_scope表 条件为coupon_id
                List<CouponScope> scopeList = couponScopeService.lambdaQuery()
                        .eq(CouponScope::getCouponId, coupon.getId())
                        .list();
                //2.3得到限定范围的id集合
                List<Long> scopeIds = scopeList.stream().map(CouponScope::getBizId)
                        .collect(Collectors.toList());
                //2.4从orderCourses 订单中所有的课程集合 筛选 该范围内的课程
                availableCourses = orderCourses.stream()
                        .filter(orderCourseDTO -> scopeIds.contains(orderCourseDTO.getCateId()))
                        .collect(Collectors.toList());
            }
            if (CollUtils.isEmpty(availableCourses)) {
                continue; //说明当前优惠券限定了范围 但是在订单的课程中没有找到可用课程 说明该券不可用 忽略该券 进行下一个券的过程
            }
            //3.计算该优惠券 可用课程的总金额
            int totalAmount = availableCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();

            //4.判断该优惠券是否可用 如果可用添加到map中
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (discount.canUse(totalAmount, coupon)) {
                map.put(coupon, availableCourses);
            }
        }
        return map;
    }
}
