package org.apache.ibatis.plugin;

import org.apache.ibatis.executor.statement.StatementHandler;

import java.sql.Connection;
import java.util.Properties;

/**
 * @ClassName: MyInterceptor
 * @Description: TODO
 * @Author: zhangzhiqiang
 * @Date: 2020-03-28 20:38
 * @Company: www.luckyqiang.cn
 */

@Intercepts({@Signature(
  type = StatementHandler.class,
  method = "prepare",
  args = {Connection.class}
)})
public class MyInterceptor implements Interceptor {
  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    return invocation.proceed();
  }

  @Override
  public Object plugin(Object target) {
    return Plugin.wrap(target, this);
  }

  @Override
  public void setProperties(Properties properties) {

  }
}
