package cn.itcast.order.mapper;

import cn.itcast.order.entity.Order;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * @author 虎哥
 */
public interface OrderMapper extends BaseMapper<Order> {
    @Select("select * from order_tbl where id = #{id} for update")
    Order selectForUpdate(@Param("id") Long id);
}
