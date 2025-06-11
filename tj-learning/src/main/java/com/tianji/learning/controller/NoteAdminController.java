package com.tianji.learning.controller;


import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Api(tags = "管理端笔记相关接口")
@RestController
@RequestMapping("/admin-notes")
@RequiredArgsConstructor
public class NoteAdminController {
}
