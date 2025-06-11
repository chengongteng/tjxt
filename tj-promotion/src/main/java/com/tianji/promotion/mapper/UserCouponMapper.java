package com.tianji.promotion.mapper;

import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.UserCoupon;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 Mapper 接口
 * </p>
 *
 * @author hercat
 * @since 2025-06-06
 */
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    @Select("select c.id, c.discount_type, c.`specific`, c.threshold_amount, c.discount_value, c.max_discount_amount, uc.id as creater\n" +
            "from coupon c\n" +
            "         inner join user_coupon uc on c.id = uc.coupon_id\n" +
            "where uc.user_id = #{userId}\n" +
            "  and uc.`status` = 1")
    List<Coupon> queryMyCoupons(Long userId);
}
