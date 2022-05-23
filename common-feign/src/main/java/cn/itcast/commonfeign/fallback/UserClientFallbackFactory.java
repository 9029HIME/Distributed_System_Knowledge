package cn.itcast.commonfeign.fallback;

import cn.itcast.commonfeign.client.UserClient;
import cn.itcast.commonfeign.pojo.User;
import feign.hystrix.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class UserClientFallbackFactory implements FallbackFactory<UserClient> {
    @Override
    public UserClient create(Throwable throwable) {
        return new UserClient() {
            @Override
            public User queryById(Long id) {
                User user = new User();
                user.setUsername("sentinel fallback");
                user.setAddress("sentinel fallback");
                return user;
            }
        };
    }
}
