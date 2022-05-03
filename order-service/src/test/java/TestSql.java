package cn.itcast.order;


import cn.itcast.order.service.OrderService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@SpringBootTest
@RunWith(SpringRunner.class)
public class TestSql {

    @Autowired
    private OrderService orderService;

    @Test
    public void testQuery(){
        System.out.println(orderService.queryOrderById(1L));
    }
}
