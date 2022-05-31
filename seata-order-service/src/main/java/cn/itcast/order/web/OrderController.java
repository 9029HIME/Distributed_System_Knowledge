package cn.itcast.order.web;

import cn.itcast.order.entity.Order;
import cn.itcast.order.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * @author 虎哥
 */
@RestController
@RequestMapping("order")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/createOrder")
    public ResponseEntity<Long> createOrder(@RequestBody Order order){
        Long orderId = orderService.create(order);
        return ResponseEntity.status(HttpStatus.CREATED).body(orderId);
    }

    @GetMapping("/getOrder")
    public ResponseEntity<String> getOrder(@RequestParam("id") Long id){
        Order order = orderService.getOrder(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(order.toString());
    }
}
