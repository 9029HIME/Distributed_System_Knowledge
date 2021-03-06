package cn.itcast.order.web;

import cn.itcast.order.pojo.Order;
import cn.itcast.order.service.OrderService;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Value("${orderConfig}")
    private String orderConfig;

    //day02-19
    @GetMapping("/testAddHeader")
    public void testAddHeader(@RequestHeader("test") String test){
        System.out.println("test:"+test);
    }


    //day02-19
    @GetMapping("/testDefaultAddHeader")
    public void testDefaultAddHeader(@RequestHeader("origin") String origin){
        System.out.println("origin:"+origin);
    }

//    @SentinelResource("hot")
    @GetMapping("{orderId}")
    public Order queryOrderByUserId(@PathVariable("orderId") Long orderId) {
        // 根据id查询订单并返回
        return orderService.queryOrderById(orderId);
    }


    //day02-10
    @GetMapping("/testNacosConfig")
    public String testNacosConfig() {
        return orderConfig;
    }

    @GetMapping("/query")
    public String queryOrder() {
        // 查询商品
        orderService.queryGoods();
        // 查询订单
        System.err.println("查询订单");
        return "查询订单成功";
    }

    @GetMapping("/save")
    public String saveOrder() {
        // 查询商品
        orderService.queryGoods();
        // 查询订单
        System.err.println("新增订单");
        return "新增订单成功";
    }

    @GetMapping("/update")
    public String updateOrder() {
        return "更新订单成功";
    }

    @GetMapping("/customOrder")
    public Order customOrder(){
        return orderService.customOrder();
    }
}
