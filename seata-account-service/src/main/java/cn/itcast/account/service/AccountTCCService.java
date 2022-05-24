package cn.itcast.account.service;


public interface AccountTCCService {

    void deduct(String userId,
                int money);

//    boolean confirm(BusinessActionContext ctx);
//
//    boolean cancel(BusinessActionContext ctx);
}
