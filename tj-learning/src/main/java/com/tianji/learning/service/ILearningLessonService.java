package com.tianji.learning.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.learning.domain.po.LearningLesson;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.vo.LearningLessonVO;

import java.util.List;

/**
 * <p>
 * 学生课程表 服务类
 * </p>
 *
 * @author author
 * @since 2026-01-28
 */
public interface ILearningLessonService extends IService<LearningLesson> {

    void addUserLessons(Long userId, List<Long> courseIds);

    PageDTO<LearningLessonVO> queyMyLessons(PageQuery query);

    LearningLessonVO queryMyCurrentLesson();

    void deleteCourseFromLesson(Long userId, Long courseId);

    LearningLessonVO queryLessonByCourseId(Long courseId);

    Long isLessonValid(Long courseId);

    Integer countLearningLessonByCourse(Long courseId);
}
