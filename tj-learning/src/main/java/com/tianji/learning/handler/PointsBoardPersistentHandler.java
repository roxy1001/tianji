package com.tianji.learning.handler;

import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.tianji.learning.service.IPointsBoardService;
import com.tianji.learning.utils.TableInfoContext;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PointsBoardPersistentHandler {

    private final IPointsBoardSeasonService seasonService;

    private final IPointsBoardService pointsBoardService;

    private final StringRedisTemplate redisTemplate;

    @XxlJob("createTableJob")
    public void createPointsBoardTableOfLastSeason(){
        //1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        //2.查询赛季id
        Integer season = seasonService.querySeasonByTime(time);
        if (season == null){
            //赛季不存在
            return;
        }
        //3.创建表
        pointsBoardService.createPointsBoardTableByseason(season);
    }

    @XxlJob("savePointsBoard2DB")
    public void savePointsBoard2DB(){
        //1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        //2.计算动态表名
        //2.1.查询赛季信息
        Integer season = seasonService.querySeasonByTime(time);
        //2.2存入ThreadLocal
        TableInfoContext.setInfo("points_board_" + season);
        //3.查询榜单数据
        //3.1.拼接KEY
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + time.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        //3.2.查询数据
        int index = XxlJobHelper.getShardIndex();
        int total = XxlJobHelper.getShardTotal();
        Integer pageNo = index + 1;
        Integer pageSize = 1000;
        while (true){
            List<PointsBoard> boardList = pointsBoardService.queryCurrentBoardList(key, pageNo, pageSize);
            if (CollUtils.isEmpty(boardList)){
                break;
            }
            //4.持久化到数据库
            //4.1.把排名信息写入id中
            boardList.forEach(board ->{
                board.setId(board.getRank().longValue());
                board.setRank(null);
            });
            //4.2.持久化
            pointsBoardService.saveBatch(boardList);
            //5.翻页
            pageNo += total;
        }
        TableInfoContext.remove();
    }

    @XxlJob("clearPointsBoardFromRedis")
    public void clearPointsBoardFromRedis(){
        //1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        //2.计算key
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + time.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        //3.删除
        redisTemplate.unlink(key);
    }
}
