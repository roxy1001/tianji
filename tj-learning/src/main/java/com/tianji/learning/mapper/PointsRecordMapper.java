package com.tianji.learning.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.tianji.learning.domain.po.PointsRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 学习积分记录，每个月底清零 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-02-03
 */
public interface PointsRecordMapper extends BaseMapper<PointsRecord> {

    @Select("select sum(points) from points_record ${ew.customSqlsegment}")
    Integer queryUserPointsByTypeAndDate(@Param((Constants.WRAPPER)) QueryWrapper<PointsRecord> wrapper);

    @Select("select type,sum(points) as points from points_record ${ew.customSqlsegment} group by type")
    List<PointsRecord> queryUserPointsByDate(@Param((Constants.WRAPPER)) QueryWrapper<PointsRecord> wrapper);
}
