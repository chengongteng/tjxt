package com.tianji.learning.mq;

import com.tianji.api.dto.trade.OrderBasicDTO;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.utils.CollUtils;
import com.tianji.learning.service.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor    //使用构造器 Lombok实在编译期生成相应的方法
public class LessonChangeListener {

    final ILearningLessonService lessonService;

    @RabbitListener(bindings = @QueueBinding(value = @Queue(value = "learning.lesson.pay.queue", durable = "true"),
            exchange = @Exchange(value = MqConstants.Exchange.ORDER_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_PAY_KEY))
    public void onMsg(OrderBasicDTO dto) {
        log.info("RabbitListener接收到了消息");
        if (dto.getUserId() == null || dto.getOrderId() == null
                || CollUtils.isEmpty(dto.getCourseIds())) {
            //不能抛异常，否则RabbitMQ会一直重试，直到异常上限
            return;
        }
        lessonService.addUserLesson(dto.getUserId(), dto.getCourseIds());
    }
}
