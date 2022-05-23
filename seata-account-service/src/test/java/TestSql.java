package cn.itcast.account;

import cn.itcast.account.mapper.AccountMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@SpringBootTest
@RunWith(SpringRunner.class)
public class TestSql {

    @Autowired
    private AccountMapper accountMapper;
    @Test
    public void testSelect(){
        accountMapper.deduct("1",2);
    }
}
