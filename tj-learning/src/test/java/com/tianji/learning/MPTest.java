package com.tianji.learning;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.service.ILearningLessonService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@SpringBootTest(classes = LearningApplication.class)
public class MPTest {

    @Autowired
    ILearningLessonService lessonService;

    /*
     * 通过LambdaQueryWrapper链式调用分页查询
     * */
    @Test
    public void Test1() {
        Page<LearningLesson> page = new Page<>(1, 2);
        LambdaQueryWrapper<LearningLesson> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LearningLesson::getUserId, 2);
        wrapper.orderByDesc(LearningLesson::getLatestLearnTime);
        lessonService.page(page, wrapper);
        System.out.println(page.getTotal());
        System.out.println(page.getPages());
        List<LearningLesson> records = page.getRecords();
        for (LearningLesson record : records) {
            System.out.println(record);
        }
    }


    /*
     * 排序规则不使用 wrapper 的 orderByDesc 避免创建未检查的泛型数组
     * 使用 myBatisPlus 的 addOrder 方法调用数据库，使用 OrderItem 设置的字段和排序规则
     * 使用 ArrayList 作为容器，装填数据库查询到的数据
     * */
    @Test
    public void Test2() {
        Page<LearningLesson> page = new Page<>(1, 2);
        List<OrderItem> itemList = new ArrayList<>();
        OrderItem item = new OrderItem();
        item.setColumn("latest_learn_time");
        item.setAsc(false);
        itemList.add(item);
        page.addOrder(itemList);

        LambdaQueryWrapper<LearningLesson> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LearningLesson::getUserId, 2);
        lessonService.page(page, wrapper);
        System.out.println(page.getTotal());
        System.out.println(page.getPages());
        List<LearningLesson> records = page.getRecords();
        for (LearningLesson record : records) {
            System.out.println(record);
        }
    }

    /*
     * 进一步简化Test2中的wrapper
     * 通过链式编程的方法简化代码
     * */
    @Test
    public void Test3() {
        Page<LearningLesson> page = new Page<>(1, 2);
        List<OrderItem> itemList = new ArrayList<>();
        OrderItem item = new OrderItem();
        item.setColumn("latest_learn_time");
        item.setAsc(false);
        itemList.add(item);
        page.addOrder(itemList);

        lessonService.lambdaQuery().eq(LearningLesson::getUserId, 2)
                .page(page);

        System.out.println(page.getTotal());
        System.out.println(page.getPages());
        List<LearningLesson> records = page.getRecords();
        for (LearningLesson record : records) {
            System.out.println(record);
        }
    }


    /*
     * 最终实现
     * */
    @Test
    public void Test4() {
        PageQuery query = new PageQuery();
        query.setPageNo(1);
        query.setPageSize(2);
        query.setIsAsc(false);
        query.setSortBy("latest_learn_time");

        Page<LearningLesson> page = lessonService.lambdaQuery().eq(LearningLesson::getUserId, 2)
                .page(query.toMpPage("latest_learn_time", false));

        System.out.println(page.getTotal());
        System.out.println(page.getPages());
        List<LearningLesson> records = page.getRecords();
        for (LearningLesson record : records) {
            System.out.println(record);
        }
    }


    /*
     * Stream流
     * */
    @Test
    public void Test5() {
        List<LearningLesson> list = new ArrayList<>();
        LearningLesson lesson1 = new LearningLesson();
        lesson1.setId(1L);
        lesson1.setCourseId(1L);
        list.add(lesson1);
        LearningLesson lesson2 = new LearningLesson();
        lesson2.setId(2L);
        lesson1.setCourseId(2L);
        list.add(lesson2);

        //转List
        List<Long> idsList = list.stream().map(LearningLesson::getUserId).collect(Collectors.toList());
        //转Set
        Set<Long> idsSet = list.stream().map(LearningLesson::getUserId).collect(Collectors.toSet());


        //转Map
        //LearningLesson的id作为map的键，将lesson对象作为map的值
        //c->c表示对象本身
        //(oldValue, newValue) -> newValue)表示如果有重复的键，则保留最新的键，即覆盖原有的重复键
        Map<Long, LearningLesson> idsMap = list.stream()
                .collect(Collectors.toMap(LearningLesson::getId, c -> c, (oldValue, newValue) -> newValue));


        System.out.println(idsList);
        System.out.println(idsSet);
        System.out.println(idsMap);
    }

/*
    @Test
    public void Test6(Long courseId){
        Long userId = UserContext.getUser();
        boolean isRemove = this.lambdaUpdate().eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId).remove();
        if (isRemove) {
            throw new BadRequestException("删除失败");
        }
    }
*/


}
