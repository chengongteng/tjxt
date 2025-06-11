package com.tianji.api.client.remark;

import com.tianji.api.client.remark.fallback.RemarkClientFallBack;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

@FeignClient(value = "remark-service", fallbackFactory = RemarkClientFallBack.class) //被调用的服务名
public interface RemarkClient {

    @GetMapping("/likes/list")
    Set<Long> getLikesStatusByBizIds(@RequestParam("bizIds") List<Long> bizIds);
}
