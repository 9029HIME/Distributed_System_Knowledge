package cn.itcast.order.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import org.springframework.stereotype.Service;

@Service
public class SecondService {
    @SentinelResource("second")
    public void querySecond(){
        System.err.println("查询商品2");
    }
}
