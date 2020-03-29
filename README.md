
**测试main方法**
```
public static void main(String[] args) {
        Reader reader = null;
        try {
            reader = Resources.getResourceAsReader("org/apache/ibatis/submitted/associationtype/mybatis-config.xml");
        } catch (IOException e) {
            e.printStackTrace();
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
        SqlSession session = sqlSessionFactory.openSession();
        //通过动态代理获取dao
        CodeSystemDAO codeSystemDAO = session.getMapper(CodeSystemDAO.class);
        CodeSystemPO codeSystemPO = codeSystemDAO.selectByPrimaryKey(3L);
        System.out.println("sql执行结果为：" + codeSystemPO.toString());
    }
```
**SqlSessionFactoryBuilder 中调用的build方法**
```
/**
     * 根据入参不同，调用不同方法
     * 这个方法第一个入参是 Reader reader
     * 还有另一个方法入参是 InputStream inputStream
     *
     * @param inputStream
     * @param environment
     * @param properties
     * @return
     */
    public SqlSessionFactory build(Reader reader, String environment, Properties properties) {
        try {
            XMLConfigBuilder parser = new XMLConfigBuilder(reader, environment, properties);
            return build(parser.parse());
        } catch (Exception e) {
            throw ExceptionFactory.wrapException("Error building SqlSession.", e);
        } finally {
            ErrorContext.instance().reset();
            try {
                reader.close();
            } catch (IOException e) {
                // Intentionally ignore. Prefer previous error.
            }
        }
    }

    /**
     * 根据配置文件，生成SqlSessionFactory
     *
     * @param config
     * @return
     */
    public SqlSessionFactory build(Configuration config) {
        return new DefaultSqlSessionFactory(config);
    }
```

**XMLConfigBuilder调用的parse方法**

```
 /**
     * 读取xml配置文件，初始化全局变量configuration
     *
     * @return
     */
    public Configuration parse() {
        if (parsed) {
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        parsed = true;
        parseConfiguration(parser.evalNode("/configuration"));
        return configuration;
    }
```

**DefaultSqlSessionFactory调用openSession方法创建会话**

```
  /**
     * 根据入参调用不同的openSession方法
     * 入参有：ExecutorType、TransactionIsolationLevel、Connection和boolean autoCommit
     *
     * @return
     */
    @Override
    public SqlSession openSession() {
        return openSessionFromDataSource(configuration.getDefaultExecutorType(), null, false);
    }

    private SqlSession openSessionFromDataSource(ExecutorType execType, TransactionIsolationLevel level, boolean autoCommit) {
        Transaction tx = null;
        try {
            //包含获取数据库连接信息（DataSource）、事务工厂（TransactionFactory）
            final Environment environment = configuration.getEnvironment();
            /**
             * 提供了两种事务实现：JdbcTransactionFactory、ManagedTransactionFactory
             * JdbcTransaction 类中封装了 DataSource 对象和 Connection 对象，依赖 JDBC Connection 控制事务的提交和回滚。
             * ManagedTransaction 类中同样封装了 DataSource 对象和 Connection 对象，但其 commit()、rollback() 方法都是空实现。
             *  MyBatis 加载配置文件的时候，会解析配置文件，根据 transactionManager 节点配置的内容生成相应的工厂类对象。
             */
            final TransactionFactory transactionFactory = getTransactionFactoryFromEnvironment(environment);
            tx = transactionFactory.newTransaction(environment.getDataSource(), level, autoCommit);
            /**
             * ExecutorType 可以配置三种执行器
             * SIMPLE：对应SimpleExecutor，一种常规执行器，每次执行都会创建一个statement，用完后关闭
             * REUSE：可重用执行器，将statement存入map中，操作map中的statement而不会重复创建statement
             * BATCH：对应BatchExecutor，批处理型执行器，doUpdate预处理存储过程或批处理操作，doQuery提交并执行过程，不会像上面两者返回执行行数
             */
            final Executor executor = configuration.newExecutor(tx, execType);
            return new DefaultSqlSession(configuration, executor, autoCommit);
        } catch (Exception e) {
            closeTransaction(tx); // may have fetched a connection so lets call close()
            throw ExceptionFactory.wrapException("Error opening session.  Cause: " + e, e);
        } finally {
            ErrorContext.instance().reset();
        }
    }
  
  
```
**Configuration的newExecutor方法，使用了装饰器模式**
```
/**
     * CachingExecutor 二级缓存执行器，为装饰类
     *
     * @param transaction
     * @param executorType
     * @return
     */
    public Executor newExecutor(Transaction transaction, ExecutorType executorType) {
        executorType = executorType == null ? defaultExecutorType : executorType;
        executorType = executorType == null ? ExecutorType.SIMPLE : executorType;
        Executor executor;
        if (ExecutorType.BATCH == executorType) {
            executor = new BatchExecutor(this, transaction);
        } else if (ExecutorType.REUSE == executorType) {
            executor = new ReuseExecutor(this, transaction);
        } else {
            executor = new SimpleExecutor(this, transaction);
        }
        //如果开了二级缓存，再装饰原先的executor
        if (cacheEnabled) {
            executor = new CachingExecutor(executor);
        }
        executor = (Executor) interceptorChain.pluginAll(executor);
        return executor;
    }
```


**SqlSession的getMapper方法**

```
//DefaultSqiSession类中
    public <T> T getMapper(Class<T> type) {
        return configuration.getMapper(type, this);
    }

    //Configuration类中
    public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
        return mapperRegistry.getMapper(type, sqlSession);
    }

//MapperRegistry类中

    /**
     * 从缓存集合knownMappers中获取对应的Mapper接口
     * 生成Mapper接口代理对象
     */
    public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
        final MapperProxyFactory<T> mapperProxyFactory = (MapperProxyFactory<T>) knownMappers.get(type);
        if (mapperProxyFactory == null) {
            throw new BindingException("Type " + type + " is not known to the MapperRegistry.");
        }
        try {
            return mapperProxyFactory.newInstance(sqlSession);
        } catch (Exception e) {
            throw new BindingException("Error getting mapper instance. Cause: " + e, e);
        }
    }

//MapperProxyFactory类中

    /**
     * jdk动态代理生成mapper代理对象
     */
    protected T newInstance(MapperProxy<T> mapperProxy) {
        return (T) Proxy.newProxyInstance(mapperInterface.getClassLoader(), new Class[]{mapperInterface}, mapperProxy);
    }

    //MapperProxy implements InvocationHandler
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            //如果代理对象是一个类，则执行原有方法
            if (Object.class.equals(method.getDeclaringClass())) {
                return method.invoke(this, args);
            } else if (method.isDefault()) {
                //默认的增强
                return invokeDefaultMethod(proxy, method, args);
            }
        } catch (Throwable t) {
            throw ExceptionUtil.unwrapThrowable(t);
        }
        //生成一个MapperMethod对象
        final MapperMethod mapperMethod = cachedMapperMethod(method);
        //调用其execute方法，把sqlSession和参数传递进去
        return mapperMethod.execute(sqlSession, args);
    }

```

**MapperMethod类**
```
public class MapperMethod {
    /**
   * 增删改查
   * @param sqlSession
   * @param args
   * @return
   */
  public Object execute(SqlSession sqlSession, Object[] args) {
    Object result;
    switch (command.getType()) {
      case INSERT: {
        
      }
      case UPDATE: {
        
      }
      case DELETE: {
        
      }
      case SELECT:
        if (method.returnsVoid() && method.hasResultHandler()) {
          executeWithResultHandler(sqlSession, args);
          result = null;
        } else if (method.returnsMany()) {
          /**
           * 根据mapper.xml中配置的returnType，如最常见的列表查询
           * this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();
           */
          result = executeForMany(sqlSession, args);
        } else if (method.returnsMap()) {
          result = executeForMap(sqlSession, args);
        } else if (method.returnsCursor()) {
          result = executeForCursor(sqlSession, args);
        } else {
          Object param = method.convertArgsToSqlCommandParam(args);
          result = sqlSession.selectOne(command.getName(), param);
          if (method.returnsOptional()
              && (result == null || !method.getReturnType().equals(result.getClass()))) {
            result = Optional.ofNullable(result);
          }
        }
        break;
      case FLUSH:
        result = sqlSession.flushStatements();
        break;
      default:
        throw new BindingException("Unknown execution method for: " + command.getName());
    }
    if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
      throw new BindingException("Mapper method '" + command.getName()
          + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
    }
    return result;
  }
  
  private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
    List<E> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      //逻辑分页，把数据全部查询到ResultSet，然后从ResultSet中取出offset和limit之间的数据，实现了分页查询
      RowBounds rowBounds = method.extractRowBounds(args);
      //executor 执行 调用executor.query()方法
      result = sqlSession.selectList(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.selectList(command.getName(), param);
    }
    // issue #510 Collections & arrays support
    if (!method.getReturnType().isAssignableFrom(result.getClass())) {
      if (method.getReturnType().isArray()) {
        return convertToArray(result);
      } else {
        return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
      }
    }
    return result;
}
```

Executor类(BaseExecutor和CachingExecutor)

```
public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        //查询的时候一般清除一级缓存，但是可以通过 xml配置或者注解强制清除，queryStack == 0 是为了防止递归调用
        if (queryStack == 0 && ms.isFlushCacheRequired()) {
            clearLocalCache();
        }
        List<E> list;
        try {
            queryStack++;
            //从一级缓存中查询
            list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
            if (list != null) {
                handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
            } else {
                //一级缓存中没有，查数据库
                list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
            }
        } finally {
            queryStack--;
        }
        if (queryStack == 0) {
            //延迟加载队列
            for (DeferredLoad deferredLoad : deferredLoads) {
                deferredLoad.load();
            }
            // issue #601
            deferredLoads.clear();
            // 一级缓存本身不能关闭，但是可以设置作用范围 STATEMENT，每次都清除缓存
            if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
                // issue #482
                clearLocalCache();
            }
        }
        return list;
    }
    
    
    private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        List<E> list;
        localCache.putObject(key, EXECUTION_PLACEHOLDER);
        try {
            //调用simple、reuse、batch、close四种执行器重写的方法
            list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
        } finally {
            localCache.removeObject(key);
        }
        //查询结果放入一级缓存
        localCache.putObject(key, list);
        if (ms.getStatementType() == StatementType.CALLABLE) {
            localOutputParameterCache.putObject(key, parameter);
        }
        return list;
    }
```
**SimpleExecutor重写的doQuery方法**

```
public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        Statement stmt = null;
        try {
            Configuration configuration = ms.getConfiguration();
            StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
            //预编译Statement
            stmt = prepareStatement(handler, ms.getStatementLog());
            /**
             * SimpleStatementHandler:最简单的StatementHandler，处理不带参数运行的SQL
             * PreparedStatementHandler：预处理Statement的handler，处理带参数允许的SQL
             * CallableStatementHandler：存储过程的Statement的handler，处理存储过程SQL
             */
            return handler.query(stmt, resultHandler);
        } finally {
            closeStatement(stmt);
        }
    }
```
**PreparedStatementHandler中的query方法**

```
public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {
        PreparedStatement ps = (PreparedStatement) statement;
        //原生jdbc执行
        ps.execute();
        //反射，处理查询结果
        return resultSetHandler.handleResultSets(ps);
    }
```

**DefalutResultSetHandler中处理结果方法**

```
/**
     * 处理结果集的方法，在StatementHandler里面的query方法会调用该方法
     * PS：该方法可以处理Statement，PreparedStatement和存储过程的CallableStatement返回的结果集。
     * 而CallableStatement是支持返回多结果集的，普通查询一般是单个结果集
     * */
    @Override
    public List<Object> handleResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling results").object(mappedStatement.getId());
        //保存结果集对象。普通的查询，实际就一个ResultSet，也就是说，multipleResults最多就一个元素。
        //在多ResultSet的结果集合一般在存储过程出现，此时每个ResultSet对应一个Object对象，每个Object是 List<Object>对象
        final List<Object> multipleResults = new ArrayList<>();

        int resultSetCount = 0;
        //Statement可能返回多个结果集对象，先处理第一个结果集，封装成ResultSetWrapper对象
        ResultSetWrapper rsw = getFirstResultSet(stmt);
        //将结果集转换需要知道转换规则，而记录和JavaBean的转换规则都在resultMap里面
        //mapper.xml中select的查询中配置的不是resultMap而是resultType，也是按照resultMap进行存储的
        //注意获取的是全部的resultMap，是一个list，但是对于普通查询，resultMaps只有一个元素(普通查询一个响应只有一个ResultSet，存储过程可能有多个)
        List<ResultMap> resultMaps = mappedStatement.getResultMaps();
        //一般来说，配置的resultMap只有一个，size=1
        int resultMapCount = resultMaps.size();
        //结果集和转换规则均不能为空，否则抛异常
        validateResultMapsCount(rsw, resultMapCount);
        //遍历处理结果集，普通查询其实就处理一次
        while (rsw != null && resultMapCount > resultSetCount) {
            //获取当前结果集对应的resultMap(注意前面是全部的resultMap，这里是获取到自己需要的，普通查询就是获取一个，一般也就只有一个)
            ResultMap resultMap = resultMaps.get(resultSetCount);
            /**
             * 根据resultMap处理rsw生成java对象
             * 按照parentMapping和resultHandler分成了3种情况，但最终都进入了handleRowValues方法
             */
            handleResultSet(rsw, resultMap, multipleResults, null);
            //获得结果集的下一个结果
            rsw = getNextResultSet(stmt);
            cleanUpAfterHandlingResultSet();
            resultSetCount++;
        }

        String[] resultSets = mappedStatement.getResultSets();
        if (resultSets != null) {
            //和resultMaps的遍历处理类似
            while (rsw != null && resultSetCount < resultSets.length) {
                ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
                if (parentMapping != null) {
                    String nestedResultMapId = parentMapping.getNestedResultMapId();
                    ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
                    handleResultSet(rsw, resultMap, null, parentMapping);
                }
                rsw = getNextResultSet(stmt);
                cleanUpAfterHandlingResultSet();
                resultSetCount++;
            }
        }
        //如果multipleResults只有一个object，则直接返回object，否则返回list<object>
        return collapseSingleResultList(multipleResults);
    }
    
    
    /**
     * 根据规则(resultMap)处理 ResultSet，将结果集转换为Object列表,并保存到multipleResults
     * @param rsw
     * @param resultMap
     * @param multipleResults
     * @param parentMapping
     * @throws SQLException
     */
    private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
        try {
            //非存储过程的情况，parentMapping为null。handleResultSets方法的第一个while循环传参就是null
            if (parentMapping != null) {
                handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
            } else {
                if (resultHandler == null) {
                    //如果没有自定义的resultHandler，则使用默认的DefaultResultHandler对象
                    DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
                    //(核心方法)处理ResultSet返回的每一行Row，里面会循环处理全部的结果集
                    handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
                    //将defaultResultHandler的处理结果，添加到multipleResults中
                    multipleResults.add(defaultResultHandler.getResultList());
                } else {
                    handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
                }
            }
        } finally {
            // issue #228 (close resultsets)
            closeResultSet(rsw.getResultSet());
        }
    }
    
    
    public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
        //处理嵌套映射的情况
        if (resultMap.hasNestedResultMaps()) {
            //校验RowBounds
            ensureNoRowBounds();
            //校验自定义resultHandler
            checkResultHandler();
            //处理嵌套映射的结果集
            handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        } else {
            //简单映射情况
            handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        }
    }
    
    
    /**
     * 简单ResultMap映射的情况下处理结果行
     * @param rsw
     * @param resultMap
     * @param resultHandler
     * @param rowBounds
     * @param parentMapping
     * @throws SQLException
     */
    private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
            throws SQLException {
        //创建DefaultResultContext
        DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
        //获得ResultSet对象，并跳到 rowBounds 指定的开始位置
        ResultSet resultSet = rsw.getResultSet();
        skipRows(resultSet, rowBounds);
        //循环处理结果集(shouldProcessMoreRows校验context是否已经关闭和是否达到limit，rsw获取下一条记录)
        while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
            //根据该行记录和ResultMap.discriminator ，决定映射使用的 ResultMap 对象
            ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
            //根据确定的ResultMap将ResultSet中的该行记录映射为Java对象(处理一行)
            Object rowValue = getRowValue(rsw, discriminatedResultMap, null);
            //将映射得到的Java对象添加到ResultHandler.resultList中
            storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
    }
    
    
    /**
     * 根据确定的ResultMap将ResultSet中的记录映射为Java对象(处理一行)
     * @param rsw
     * @param resultMap
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
        //创建ResultLoaderMap，和懒加载相关
        final ResultLoaderMap lazyLoader = new ResultLoaderMap();
        //创建结果对象，类型为resultMap.getType(),最终调用了ObjectFactory.create()方法
        Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
        //如果hasTypeHandlerForResultObject(rsw, resultMap.getType())返回 true ，意味着rowValue是基本类型，无需执行下列逻辑。
        if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            //创建MetaObject对象，用于访问rowValue对象，设置rowValue对象属性
            final MetaObject metaObject = configuration.newMetaObject(rowValue);
            //foundValues代表，是否成功映射任一属性。若成功，则为true ，若失败，则为false
            boolean foundValues = this.useConstructorMappings;
            //判断是否开启自动映射功能
            if (shouldApplyAutomaticMappings(resultMap, false)) {
                //自动映射未明确的列
                foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
            }
            //映射ResultMap中明确映射的列,至此，ResultSet的该行记录的数据已经完全映射到结果对象resultObject的对应属性中
            foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
            foundValues = lazyLoader.size() > 0 || foundValues;
            //如果映射属性失败，则置空 resultObject 对象。
            rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
        }
        return rowValue;
    }
    
    
    //创建结果对象，类型为resultMap.getType(),最终调用了ObjectFactory.create()方法
    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
        this.useConstructorMappings = false; // reset previous mapping result
        //构造方法中的参数类型
        final List<Class<?>> constructorArgTypes = new ArrayList<>();
        //构造方法中参数具体值
        final List<Object> constructorArgs = new ArrayList<>();
        //根据构造方法生成对象
        Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
        if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
            for (ResultMapping propertyMapping : propertyMappings) {
                // issue gcode #109 && issue #149
                if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
                    resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
                    break;
                }
            }
        }
        this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
        return resultObject;
    }

    //根据构造方法生成对象
    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
            throws SQLException {
        final Class<?> resultType = resultMap.getType();
        final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
        //resultMap配置中的construnctor节点
        final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
        if (hasTypeHandlerForResultObject(rsw, resultType)) {
            return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
        } else if (!constructorMappings.isEmpty()) {
            //construnctor节点有配置
            return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
        } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
            //construnctor节点没有配置，调用无参的构造方法
            return objectFactory.create(resultType);
        } else if (shouldApplyAutomaticMappings(resultMap, false)) {
            return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs);
        }
        throw new ExecutorException("Do not know how to create an instance of " + resultType);
    }

    //construnctor节点有配置
    Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                           List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
        boolean foundValues = false;
        for (ResultMapping constructorMapping : constructorMappings) {
            final Class<?> parameterType = constructorMapping.getJavaType();
            final String column = constructorMapping.getColumn();
            final Object value;
            try {
                //取出参数和具体数值
                if (constructorMapping.getNestedQueryId() != null) {
                    value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
                } else if (constructorMapping.getNestedResultMapId() != null) {
                    final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
                    value = getRowValue(rsw, resultMap, getColumnPrefix(columnPrefix, constructorMapping));
                } else {
                    final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
                    value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
                }
            } catch (ResultMapException | SQLException e) {
                throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
            }
            constructorArgTypes.add(parameterType);
            constructorArgs.add(value);
            foundValues = value != null || foundValues;
        }
        //创建对象
        return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
    }
```

### 总结
1. SqlSessionFactoryBuilder.build()方法，读取配置文件，创建sqlSessionFactory
2. sqlSessionFactory.openSession()，根据全局配置变量Configuration和执行器Executor创建DefaultSqlSession
3. session.getMapper(Object.class),jdk动态代理生成mapper接口代理对象mapperProxy
4. mapperProxy中生成一个MapperMethod对象，调用mapperMethod.execute()方法
5. mapperMethod.execute()方法最终调用执行器executor.query()方法
6. executor调用StatementHandler子类query方法
7. jdbc执行PreparedStatement.execute()，查询结果交给DefaultResultSetHandler处理
8. getRowValue()方法里，对ResultSet进行映射到java bean
9. type.getDeclaredConstructor().newInstance()反射生成po对象
10. applyAutomaticMappings自动映射(结果集有但在resultMap里没有配置的字段)
11. applyPropertyMappings映射resultMap里配置的字段
12. 返回po



**ps：spring中配置transactionManager和executorType**

```
<!-- 配置事务管理  -->
<bean name="transactionManager" class="org.springframework.jdbc.datasource.DataSourceTransactionManager">
		<property name="dataSource" ref="dataSource"></property>
</bean>

<!--配置一个可以进行批量执行的sqlSession  -->
<bean id="sqlSession" class="org.mybatis.spring.SqlSessionTemplate">
    <constructor-arg name="sqlSessionFactory" ref="sqlSessionFactoryBean"></constructor-arg>
    <constructor-arg name="executorType" value="BATCH"></constructor-arg>
</bean>
```
