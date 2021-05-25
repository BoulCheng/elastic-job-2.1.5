

- 任务执行前先判断是否需要重新进行分片 通过判断是否存在节点 leader/sharding/necessary
- 该节点会在 任务启动、分片总数更新、servers和instances节点变化 时设置

- 主节点会执行重新分片 通过判断当前job是否是 leader/election/instance 节点下的job
- 获取 instances 节点下所有job实例和分片总数通过平均分配策略 进行分片 
- 更新节点 /sharding/{shardingItem}/instance 下的job实例



