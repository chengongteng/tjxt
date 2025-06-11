package com.tianji.promotion.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.query.CouponQuery;

import java.util.List;

/**
 * <p>
 * 优惠券的规则信息 服务类
 * </p>
 *
 * @author hercat
 * @since 2025-06-06
 */
public interface ICouponService extends IService<Coupon> {

    void saveCoupon(CouponFormDTO dto);

    PageDTO<CouponPageVO> queryCouponPage(CouponQuery query);

    void modifyCoupon(Long id, CouponFormDTO dto);

    void deleteCoupon(Long id, CouponFormDTO dto);

    void issueCoupon(Long id, CouponIssueFormDTO dto);

    List<CouponVO> queryIssuingCoupons();
}
