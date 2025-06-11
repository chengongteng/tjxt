package com.tianji.promotion.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.query.CouponQuery;
import com.tianji.promotion.service.ICouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.constraints.Max;
import java.util.List;

/**
 * <p>
 * 优惠券的规则信息 前端控制器
 * </p>
 *
 * @author hercat
 * @since 2025-06-06
 */
@Api(tags = "优惠券相关接口")
@Slf4j
@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final ICouponService couponService;

    @ApiOperation("新增优惠券-管理端")
    @PostMapping
    public void saveCoupon(@RequestBody @Validated CouponFormDTO dto){
        couponService.saveCoupon(dto);
    }

    @ApiOperation("修改优惠券-管理端")
    @PutMapping("/{id}")
    public void modifyCoupon(@PathVariable Long id, @RequestBody @Validated CouponFormDTO dto){
        log.debug("修改优惠券-管理端 modifyCoupon");
        couponService.modifyCoupon(id, dto);
    }

    @ApiOperation("删除优惠券-管理端")
    @DeleteMapping("/{id}")
    public void deleteCoupon(@PathVariable Long id, @RequestBody @Validated CouponFormDTO dto){
        couponService.deleteCoupon(id, dto);
    }

    @ApiOperation("分页查询优惠券列表-管理端")
    @GetMapping("/page")
    public PageDTO<CouponPageVO> queryCouponPage(CouponQuery query){
       return couponService.queryCouponPage(query);
    }

    //@RequestBody @Validated CouponIssueFormDTO dto
    @ApiOperation("发放优惠券-管理端")
    @PutMapping("/{id}/issue")
    public void issueCoupon(@PathVariable Long id,
            @RequestBody @Validated CouponIssueFormDTO dto){
        log.debug("发放优惠券-管理端 issueCoupon");
         couponService.issueCoupon(id, dto);
    }

    @ApiOperation("查询发放中的优惠券列表-用户端")
    @GetMapping("/list")
    public List<CouponVO> queryIssuingCoupons(){
        return couponService.queryIssuingCoupons();
    }
}
