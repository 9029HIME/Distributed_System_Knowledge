package cn.itcast.user;


import cn.itcast.user.service.UserService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;


@SpringBootTest
@RunWith(SpringRunner.class)
public class TestSql {

    @Autowired
    private UserService userService;



    @Test
    public void testTest(){
        System.out.println(userService.queryById(1L));
    }
}
