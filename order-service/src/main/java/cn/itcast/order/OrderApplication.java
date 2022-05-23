package cn.itcast.order;


import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.web.client.RestTemplate;

@MapperScan("cn.itcast.order.mapper")
@SpringBootApplication
//day02-12
//day02-14.3
@EnableFeignClients(basePackages = {"cn.itcast.commonfeign.client"})
@ComponentScan(basePackages = {"cn.itcast.commonfeign","cn.itcast.order"})
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }

    /**
     * 创建RestTemplate并注入Spring容器
     */
    //day01-3：在restTemplate上面加上@LoadBalanced注解，配合Eureka注册中心，实现负载均衡
    @LoadBalanced
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

   /* @Bean
    public IRule randomRule() {
        return new RandomRule()
    }*/
}