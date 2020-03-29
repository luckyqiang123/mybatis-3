/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.cache;

import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.cache.decorators.TransactionalCache;

/**
 * @author Clinton Begin
 * 用于管理CachingExecutor使用的二级缓存对象，只定义了一个transactionalCaches字段
 */
public class TransactionalCacheManager {

  /**
   *   二级缓存map，key和value与一级缓存map一样
   */
  private final Map<Cache, TransactionalCache> transactionalCaches = new HashMap<>();

  public void clear(Cache cache) {
    getTransactionalCache(cache).clear();
  }

  /**
   * CachingExecutor调用此方法，用key查询返回value，
   * @param cache
   * @param key
   * @return
   */
  public Object getObject(Cache cache, CacheKey key) {
    return getTransactionalCache(cache).getObject(key);
  }

  public void putObject(Cache cache, CacheKey key, Object value) {
    getTransactionalCache(cache).putObject(key, value);
  }

  public void commit() {
    for (TransactionalCache txCache : transactionalCaches.values()) {
      txCache.commit();
    }
  }

  public void rollback() {
    for (TransactionalCache txCache : transactionalCaches.values()) {
      txCache.rollback();
    }
  }

  /**
   * map.computeIfAbsent(K key,
   *             Function<? super K, ? extends V> mappingFunction)
   * key 就是我们要用来做映射的key
   * mappingFunction 就是我们的映射函数
   * 这个方法返回当前与key关联的值（无论是之前已经存在的，或者是刚刚计算出来的），可能为null。
   * TransactionalCache::new, 调用TransactionalCache的构造方法
   * @param cache
   * @return
   */
  private TransactionalCache getTransactionalCache(Cache cache) {
    return transactionalCaches.computeIfAbsent(cache, TransactionalCache::new);
  }

}
