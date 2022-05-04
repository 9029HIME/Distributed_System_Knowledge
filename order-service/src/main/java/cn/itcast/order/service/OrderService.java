package cn.itcast.order.service;

//import cn.itcast.feign.clients.UserClient;
//import cn.itcast.feign.pojo.User;
import cn.itcast.order.mapper.OrderMapper;
import cn.itcast.order.pojo.Order;
import cn.itcast.order.pojo.User;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class OrderService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserClient userClient;

//    @Autowired
//    private UserClient userClient;

    public Order queryOrderById(Long orderId) {
        // 1.查询订单
        Order order = orderMapper.findById(orderId);

        /*
         day01-1：远程调用的雏形-RestTemplate，属于是最基本的远程调用方式
         */
//        User user = restTemplate.getForObject(String.format("http://localhost:8081/user/%s", order.getUserId()), User.class);
//        order.setUser(user);

        /*
         day01-3：使用注册中心+restTemplate方式调用，需要在restTemplate上新增@LoadBalanced注解，并且url改为服务名，而不是主机+端口
         */
//        User user = restTemplate.getForObject(String.format("http://userservice/user/%s", order.getUserId()), User.class);

        /*
         day02-12：进阶版远程调用-使用OpenFeign进行远程调用
         */
        User user = userClient.queryById(order.getUserId());
        order.setUser(user);

        return order;
    }

    @SentinelResource("goods")
    public void queryGoods(){
        System.err.println("查询商品");
    }

    /*@Autowired
    private RestTemplate restTemplate;

    public Order queryOrderById(Long orderId) {
        // 1.查询订单
        Order order = orderMapper.findById(orderId);
        // 2.利用RestTemplate发起http请求，查询用户
        // 2.1.url路径
        String url = "http://userservice/user/" + order.getUserId();
        // 2.2.发送http请求，实现远程调用
        User user = restTemplate.getForObject(url, User.class);
        // 3.封装user到Order
        order.setUser(user);
        // 4.返回
        return order;
    }*/
}
