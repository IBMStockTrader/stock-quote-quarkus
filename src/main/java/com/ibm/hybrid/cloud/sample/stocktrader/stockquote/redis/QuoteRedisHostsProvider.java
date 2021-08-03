package com.ibm.hybrid.cloud.sample.stocktrader.stockquote.redis;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

import io.quarkus.redis.client.RedisHostsProvider;

@Named("QuoteRedisHostsProvider")
@ApplicationScoped
/**
 * This class is used to initialize Quarkus Redis client, for backward compatibility with Liberty version.
 * It reads the Redis location from REDIS_URL environment setting.
 * To use it add quarkus.redis.hosts-provider-name=QuoteRedisHostsProvider to application.properties.
 * If you don't want to use this class you can put redis url directly via quarkus.redis.hosts in application.properties
 * 
 */
public class QuoteRedisHostsProvider implements RedisHostsProvider {

    @Override
    public Set<URI> getHosts() {
        System.out.println("In QuoteRedisHostsProvider");
        String redis_url = System.getenv("REDIS_URL");
        URI redisURI = null;
        try {
            redisURI = new URI(redis_url);
        }
        catch(Exception e) {
            e.printStackTrace();
            try {
                redisURI = new URI("redis://localhost:6379");
            } catch (URISyntaxException ignored) {
            }

        }
        return Set.of(redisURI);
    }
    
}
