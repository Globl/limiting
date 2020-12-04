package cn.syh.core.observer;

import cn.syh.core.exception.LimitingException;
import cn.syh.commoon.entity.RateLimiterRule;
import cn.syh.commoon.enums.LimiterModel;
import cn.syh.core.config.RateLimiterConfig;
import cn.syh.core.limiter.RateLimiter;
import cn.syh.monitor.entity.MonitorBean;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 观察者模式（一对多）
 */
public class RateLimiterObserver {
    private static Map<String, RateLimiter> map = new ConcurrentHashMap<>();
    private static Logger logger = LoggerFactory.getLogger(RateLimiterObserver.class);

    /**
     * 注册限流器
     */
    public static void registered(RateLimiter limiter, RateLimiterConfig config) {
        if (map.containsKey(limiter.getId())) {
            throw new LimitingException("Repeat registration for current limiting rules:" + limiter.getId());
        }
        map.put(limiter.getId(), limiter);
        if (!limiter.getRule().getLimiterModel().equals(LimiterModel.CLOUD)){
            //本地限流只注册
            return;
        }
        update(limiter, config);
        monitor(limiter, config);
    }

    public static Map<String, RateLimiter> getMap() {
        return map;
    }

    /**
     * 发送心跳并更新限流规则
     */
    private static void update(RateLimiter limiter, RateLimiterConfig config) {
        config.getScheduledThreadExecutor().scheduleWithFixedDelay(() -> {
            String rules = config.getTicketServer().connect(RateLimiterConfig.http_heart, JSON.toJSONString(limiter.getRule()));
            if (rules == null) { //TicketServer挂掉
                logger.debug("update limiter fail, automatically switch to local current limit");
                RateLimiterRule rule = limiter.getRule();
                rule.setLimiterModel(LimiterModel.POINT);
                limiter.init(rule);
                return;
            }
            RateLimiterRule rateLimiterRule = JSON.parseObject(rules, RateLimiterRule.class);
            if (rateLimiterRule.getVersion() > limiter.getRule().getVersion()) { //版本升级
                logger.info("update rule version: {} -> {}", limiter.getRule().getVersion(), rateLimiterRule.getVersion());
                map.get(limiter.getId()).init(rateLimiterRule);
            } else if (rateLimiterRule.getLimiterModel().equals(LimiterModel.POINT)) { //本地/分布式切换
                rateLimiterRule.setLimiterModel(LimiterModel.CLOUD);
                map.get(limiter.getId()).init(rateLimiterRule);
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * 监控数据上报
     */
    private static void monitor(RateLimiter limiter, RateLimiterConfig config) {
        config.getScheduledThreadExecutor().scheduleWithFixedDelay(() -> {
            if (limiter.getRule().getMonitor() == 0) {
                //监控功能已关闭
                return;
            }
            List<MonitorBean> monitorBeans = limiter.getMonitorService().getAndDelete();
            if (monitorBeans.size() < 1) {
                return;
            }
            String result = config.getTicketServer().connect(RateLimiterConfig.http_monitor, JSON.toJSONString(monitorBeans));
            if (result == null) {
                logger.debug("http_monitor data update fail");
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

}
