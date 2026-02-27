package com.tianji.promotion.service.impl;

import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.autoconfigure.redisson.annotations.Lock;
import com.tianji.common.autoconfigure.redisson.enums.LockStrategy;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.strategy.discount.DiscountStrategy;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-02-06
 */
@Service
@RequiredArgsConstructor
public class UserCouponServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {

    private final CouponMapper couponMapper;

    private final IExchangeCodeService codeService;

    private final StringRedisTemplate redisTemplate;

    private final RabbitMqHelper mqHelper;

    @Override
    @Lock(name = "lock:coupon:#{couponId}")
    public void receiveCoupon(Long couponId) {
        //1.查询优惠劵
        Coupon coupon = queryCouponByCache(couponId);
        if (coupon == null){
            //优惠劵不存在
            throw new BadRequestException("优惠劵不存在");
        }
        //2.校验发放时间
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getIssueBeginTime())|| now.isAfter(coupon.getIssueEndTime())){
            throw new BadRequestException("优惠劵已经结束或尚未开始");
        }
        //3.校验库存
        if (coupon.getTotalNum()<= 0){
            throw new BadRequestException("优惠劵库不足");
        }
        Long userId = UserContext.getUser();
        //4.校验并生成用户券
      /*  synchronized(userId.toString().intern()) {//获取字符串的值
            IUserCouponService userCouponService = (IUserCouponService) AopContext.currentProxy();
            userCouponService.checkAndCreateUserCoupon(coupon, userId);
        }*/
/*        String key = "lock:coupon:uid:" + userId;
        //4.1.创建锁对象
        RLock lock = redissonClient.getLock(key);
        //4.2.尝试获取锁
        boolean isLock = lock.tryLock();
        //4.3.判断是否获取成功
        if (!isLock){
            throw new BizIllegalException("请求太频繁!");
        }
        try {
            //4.4.获取成功,执行业务
            IUserCouponService userCouponService = (IUserCouponService) AopContext.currentProxy();
            userCouponService.checkAndCreateUserCoupon(coupon, userId);
        } finally {
            //4.5.释放锁
            lock.unlock();
        }*/
        //4.1.查询领取数量
        String key = PromotionConstants.USER_COUPON_CACHE_KEY_PREFIX + couponId;
        Long count = redisTemplate.opsForHash().increment(key, userId.toString(), 1);
        //4.2.校验限领数量
        if (count > coupon.getUserLimit()){
            throw new BadRequestException("超出领取数量");
        }
        //5.扣减优惠劵库存
        redisTemplate.opsForHash().increment(PromotionConstants.COUPON_CACHE_KEY_PREFIX + couponId, "totalNum", -1);
        //6.发送MQ消息
        UserCouponDTO uc = new UserCouponDTO();
        uc.setUserId(userId);
        uc.setCouponId(couponId);
        mqHelper.send(MqConstants.Exchange.PROMOTION_EXCHANGE, MqConstants.Key.COUPON_RECEIVE, uc);
    }

    private Coupon queryCouponByCache(Long couponId) {
        //1.准备KEY
        String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + couponId;
        //2.查询
        Map<Object, Object> objectMap = redisTemplate.opsForHash().entries(key);
        if (objectMap.isEmpty()){
            return null;
        }
        //3.数据反序列化
        return BeanUtils.mapToBean(objectMap, Coupon.class,false, CopyOptions.create());
    }

    // 移除了锁，这里不需要加锁了
    @Transactional
    @Override
    public void checkAndCreateUserCoupon(UserCouponDTO uc) {
        // 1.查询优惠券
        Coupon coupon = couponMapper.selectById(uc.getCouponId());
        if (coupon == null) {
            throw new BizIllegalException("优惠券不存在！");
        }
        // 2.更新优惠券的已经发放的数量 + 1
        int r = couponMapper.incrIssueNum(coupon.getId());
        if (r == 0) {
            throw new BizIllegalException("优惠券库存不足！");
        }
        // 3.新增一个用户券
        saveUserCoupon(coupon, uc.getUserId());
        // 4.更新兑换码状态
        if (uc.getSerialNum()!= null) {
            codeService.lambdaUpdate()
                    .set(ExchangeCode::getUserId, uc.getUserId())
                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
                    .eq(ExchangeCode::getId, uc.getSerialNum())
                    .update();
        }
    }

    private void saveUserCoupon(Coupon coupon, Long userId) {
        // 1.基本信息
        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setCouponId(coupon.getId());
        // 2.有效期信息
        LocalDateTime termBeginTime = coupon.getTermBeginTime();
        LocalDateTime termEndTime = coupon.getTermEndTime();
        if (termBeginTime == null) {
            termBeginTime = LocalDateTime.now();
            termEndTime = termBeginTime.plusDays(coupon.getTermDays());
        }
        uc.setTermBeginTime(termBeginTime);
        uc.setTermEndTime(termEndTime);
        // 3.保存
        save(uc);
    }

    @Override
    @Lock(name = "lock:coupon:#{userId}", lockStrategy = LockStrategy.SKIP_AFTER_RETRY_TIMEOUT)
    public void exchangeCoupon(String code) {
        // 1.校验并解析兑换码
        long serialNum = CodeUtil.parseCode(code);
        // 2.校验是否已经兑换 SETBIT KEY 4 1
        boolean exchanged = codeService.updateExchangeMark(serialNum, true);
        if (exchanged) {
            throw new BizIllegalException("兑换码已经被兑换过了");
        }
        try {
            // 3.查询兑换码对应的优惠券id
            Long couponId = codeService.exchangeTargetId(serialNum);
            if (couponId == null) {
                throw new BizIllegalException("兑换码不存在！");
            }
            Coupon coupon = queryCouponByCache(couponId);
            // 4.是否过期
            LocalDateTime now = LocalDateTime.now();
            if (now.isAfter(coupon.getIssueEndTime()) || now.isBefore(coupon.getIssueBeginTime())) {
                throw new BizIllegalException("优惠券活动未开始或已经结束");
            }

            // 5.校验每人限领数量
            Long userId = UserContext.getUser();
            // 5.1.查询领取数量
            String key = PromotionConstants.USER_COUPON_CACHE_KEY_PREFIX + couponId;
            Long count = redisTemplate.opsForHash().increment(key, userId.toString(), 1);
            // 5.2.校验限领数量
            if(count > coupon.getUserLimit()){
                throw new BadRequestException("超出领取数量");
            }

            // 6.发送MQ消息通知
            UserCouponDTO uc = new UserCouponDTO();
            uc.setUserId(userId);
            uc.setCouponId(couponId);
            uc.setSerialNum((int) serialNum);
            mqHelper.send(MqConstants.Exchange.PROMOTION_EXCHANGE, MqConstants.Key.COUPON_RECEIVE, uc);
        } catch (Exception e) {
            // 重置兑换的标记 0
            codeService.updateExchangeMark(serialNum, false);
            throw e;
        }
    }

    @Override
    public PageDTO<CouponVO> queryMyCouponPage(UserCouponQuery query) {
        // 1.获取当前用户
        Long userId = UserContext.getUser();
        // 2.分页查询用户券
        Page<UserCoupon> page = lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getStatus, query.getStatus())
                .page(query.toMpPage(new OrderItem("term_end_time", true)));
        List<UserCoupon> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        // 3.获取优惠券详细信息
        // 3.1.获取用户券关联的优惠券id
        Set<Long> couponIds = records.stream().map(UserCoupon::getCouponId).collect(Collectors.toSet());
        // 3.2.查询
        List<Coupon> coupons = couponMapper.selectBatchIds(couponIds);

        // 4.封装VO
        return PageDTO.of(page, BeanUtils.copyList(coupons, CouponVO.class));
    }

    @Override
    @Transactional
    public void writeOffCoupon(List<Long> userCouponIds) {
        // 1.查询优惠券
        List<UserCoupon> userCoupons = listByIds(userCouponIds);
        if (CollUtils.isEmpty(userCoupons)) {
            return;
        }
        // 2.处理数据
        List<UserCoupon> list = userCoupons.stream()
                // 过滤无效券
                .filter(coupon -> {
                    if (coupon == null) {
                        return false;
                    }
                    if (UserCouponStatus.UNUSED != coupon.getStatus()) {
                        return false;
                    }
                    LocalDateTime now = LocalDateTime.now();
                    return !now.isBefore(coupon.getTermBeginTime()) && !now.isAfter(coupon.getTermEndTime());
                })
                // 组织新增数据
                .map(coupon -> {
                    UserCoupon c = new UserCoupon();
                    c.setId(coupon.getId());
                    c.setStatus(UserCouponStatus.USED);
                    return c;
                })
                .collect(Collectors.toList());

        // 4.核销，修改优惠券状态
        boolean success = updateBatchById(list);
        if (!success) {
            return;
        }
        // 5.更新已使用数量
        List<Long> couponIds = userCoupons.stream().map(UserCoupon::getCouponId).collect(Collectors.toList());
        int c = couponMapper.incrUsedNum(couponIds, 1);
        if (c < 1) {
            throw new DbException("更新优惠券使用数量失败！");
        }
    }

    @Override
    @Transactional
    public void refundCoupon(List<Long> userCouponIds) {
        // 1.查询优惠券
        List<UserCoupon> userCoupons = listByIds(userCouponIds);
        if (CollUtils.isEmpty(userCoupons)) {
            return;
        }
        // 2.处理优惠券数据
        List<UserCoupon> list = userCoupons.stream()
                // 过滤无效券
                .filter(coupon -> coupon != null && UserCouponStatus.USED == coupon.getStatus())
                // 更新状态字段
                .map(coupon -> {
                    UserCoupon c = new UserCoupon();
                    c.setId(coupon.getId());
                    // 3.判断有效期，是否已经过期，如果过期，则状态为 已过期，否则状态为 未使用
                    LocalDateTime now = LocalDateTime.now();
                    UserCouponStatus status = now.isAfter(coupon.getTermEndTime()) ?
                            UserCouponStatus.EXPIRED : UserCouponStatus.UNUSED;
                    c.setStatus(status);
                    return c;
                }).collect(Collectors.toList());

        // 4.修改优惠券状态
        boolean success = updateBatchById(list);
        if (!success) {
            return;
        }
        // 5.更新已使用数量
        List<Long> couponIds = userCoupons.stream().map(UserCoupon::getCouponId).collect(Collectors.toList());
        int c = couponMapper.incrUsedNum(couponIds, -1);
        if (c < 1) {
            throw new DbException("更新优惠券使用数量失败！");
        }
    }

    @Override
    public List<String> queryDiscountRules(List<Long> userCouponIds) {
        // 1.查询优惠券信息
        List<Coupon> coupons = baseMapper.queryCouponByUserCouponIds(userCouponIds, UserCouponStatus.USED);
        if (CollUtils.isEmpty(coupons)) {
            return CollUtils.emptyList();
        }
        // 2.转换规则
        return coupons.stream()
                .map(c -> DiscountStrategy.getDiscount(c.getDiscountType()).getRule(c))
                .collect(Collectors.toList());
    }
}
