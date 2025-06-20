package com.tianji.api.config;

import com.tianji.api.client.learning.fallback.LearningClientFallback;
import com.tianji.api.client.promotion.fallback.PromotionClientFallback;
import com.tianji.api.client.remark.fallback.RemarkClientFallBack;
import com.tianji.api.client.trade.fallback.TradeClientFallback;
import com.tianji.api.client.user.fallback.UserClientFallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FallbackConfig {
    @Bean
    public LearningClientFallback learningClientFallback(){
        return new LearningClientFallback();
    }

    @Bean
    public TradeClientFallback tradeClientFallback(){
        return new TradeClientFallback();
    }

    @Bean
    public UserClientFallback userClientFallback(){
        return new UserClientFallback();
    }

    //remark服务降级对象
    @Bean
    public RemarkClientFallBack remarkClientFallBack(){
        return new RemarkClientFallBack();
    }

    //promotion服务降级对象
    @Bean
    public PromotionClientFallback promotionClientFallback(){
        return new PromotionClientFallback();
    }

}
