package com.hmdp.config;

import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.redisson.config.SingleServerConfig;

@Configuration
public class RedissonConfig {
    @Value("${spring.redis.password}")
    String password ;
    @Value("${spring.redis.host}")
    String host ;
    @Value("${spring.redis.port:6379}")
    int port;
    @Bean(destroyMethod = "shutdown")
    // ensure RedissonClient is shutdown when context closes
    public RedissonClient redisson() {
        // 创建 Redisson 配置对象
        Config config = new Config();
        // 配置单节点模式，指定 Redis 服务器地址（需要带协议和端口）
        String address = String.format("redis://%s:%d", host, port);
        SingleServerConfig serverConfig = config.useSingleServer().setAddress(address);
        // 只有在 password 非空时设置密码（避免空密码导致问题）
        if (StringUtils.hasText(password)) {
            serverConfig.setPassword(password);
        }
        // 创建 RedissonClient 实例
        return org.redisson.Redisson.create(config);
    }
}
