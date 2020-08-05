/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.job.lite.internal.sharding;

import com.dangdang.ddframe.job.lite.api.strategy.JobInstance;
import com.dangdang.ddframe.job.lite.api.strategy.JobShardingStrategy;
import com.dangdang.ddframe.job.lite.api.strategy.JobShardingStrategyFactory;
import com.dangdang.ddframe.job.lite.config.LiteJobConfiguration;
import com.dangdang.ddframe.job.lite.internal.config.ConfigurationService;
import com.dangdang.ddframe.job.lite.internal.election.LeaderService;
import com.dangdang.ddframe.job.lite.internal.instance.InstanceNode;
import com.dangdang.ddframe.job.lite.internal.instance.InstanceService;
import com.dangdang.ddframe.job.lite.internal.schedule.JobRegistry;
import com.dangdang.ddframe.job.lite.internal.server.ServerService;
import com.dangdang.ddframe.job.lite.internal.storage.JobNodePath;
import com.dangdang.ddframe.job.lite.internal.storage.JobNodeStorage;
import com.dangdang.ddframe.job.lite.internal.storage.TransactionExecutionCallback;
import com.dangdang.ddframe.job.reg.base.CoordinatorRegistryCenter;
import com.dangdang.ddframe.job.util.concurrent.BlockUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 作业分片服务.
 * 
 * @author zhangliang
 */
@Slf4j
public final class ShardingService {
    
    private final String jobName;
    
    private final JobNodeStorage jobNodeStorage;
    
    private final LeaderService leaderService;
    
    private final ConfigurationService configService;
    
    private final InstanceService instanceService;
    
    private final ServerService serverService;
    
    private final ExecutionService executionService;

    private final JobNodePath jobNodePath;
    
    public ShardingService(final CoordinatorRegistryCenter regCenter, final String jobName) {
        this.jobName = jobName;
        jobNodeStorage = new JobNodeStorage(regCenter, jobName);
        leaderService = new LeaderService(regCenter, jobName);
        configService = new ConfigurationService(regCenter, jobName);
        instanceService = new InstanceService(regCenter, jobName);
        serverService = new ServerService(regCenter, jobName);
        executionService = new ExecutionService(regCenter, jobName);
        jobNodePath = new JobNodePath(jobName);
    }
    
    /**
     * 设置需要重新分片的标记.
     * 如果存在这个节点 /{jobName}/leader/sharding/necessary 则需要进行重新分片 但只有当前节点是主节点才能进行 节点 = 任务运行应用实例
     * CreateMode.PERSISTENT
     * value: ""
     */
    /**
     * 1 在任务启动时即 {@link JobScheduler#init()}
     * 2 分片总数更新 {@link ShardingTotalCountChangedJobListener}
     * 3 服务变化 {@link ListenServersChangedJobListener} path以/instances开头-节点变动 或 path为 /servers/{ip}-服务器id变动 而 /elastic-job-demo/demoSimpleJob/instances/192.168.1.180@-@1131 是临时节点 {@link InstanceService#persistOnline()}
     *
     */
    public void setReshardingFlag() {
        jobNodeStorage.createJobNodeIfNeeded(ShardingNode.NECESSARY);
    }
    
    /**
     * 判断是否需要重分片.
     * 
     * @return 是否需要重分片
     */
    public boolean isNeedSharding() {
        return jobNodeStorage.isJobNodeExisted(ShardingNode.NECESSARY);
    }
    
    /**
     * 如果需要分片且当前节点为主节点, 则作业分片.
     * 
     * <p>
     * 如果当前无可用节点则不分片.
     * </p>
     *
     *
     * 核心操作: 更新分片节点信息Znode /sharding/{shardingItem}/instance  节点数据内容 value: jobInstanceId = {ip}@-@{pid}
     */
    public void shardingIfNecessary() {
        List<JobInstance> availableJobInstances = instanceService.getAvailableJobInstances();
        // 需要分片标示存在/{jobName}/leader/sharding/necessary 且 存在作业运行实例
        if (!isNeedSharding() || availableJobInstances.isEmpty()) {
            return;
        }

        // 如果没有主作业实例节点则进行选择

        // 如果当前作业实例不是主作业实例节点 则等待分片操作执行完 (/leader/sharding/necessary /leader/sharding/processing 节点都不存在)
        if (!leaderService.isLeaderUntilBlock()) {
            blockUntilShardingCompleted();
            return;
        }
        // 如果当前作业实例是主作业实例节点 则执行分片操作

        //判断所有分片项中是否还有执行中的作业. /{jobName}/sharding/%s/running 有则等待其他作业实例执行完
        waitingOtherJobCompleted();
        LiteJobConfiguration liteJobConfig = configService.load(false);
        int shardingTotalCount = liteJobConfig.getTypeConfig().getCoreConfig().getShardingTotalCount();
        log.debug("Job '{}' sharding begin.", jobName);

        //标示正在进行执行分片操作  创建节点 /leader/sharding/processing  (CreateMode.EPHEMERAL)
        jobNodeStorage.fillEphemeralJobNode(ShardingNode.PROCESSING, "");

        // 重置分片节点信息 /sharding/{shardingItem}/instance value: jobInstanceId = {ip}@-@{pid}  CreateMode.PERSISTENT;

        // 1先删除原来的分片节点Znode作业实例信息(/sharding/{shardingItem}/instance);
        // 如果分片数增加则新增分片节点Znode  /sharding/{shardingItem}
        // 如果分片数减少则删除多余的分片节点Znode  /sharding/{shardingItem}
        resetShardingInfo(shardingTotalCount);
        JobShardingStrategy jobShardingStrategy = JobShardingStrategyFactory.getStrategy(liteJobConfig.getJobShardingStrategyClass());// TODO: 2020/7/30
        // 2执行分片策略
        // 3保存分片节点作业实例信息
        // 保存分片节点信息Znode, 新增 Znode: /sharding/{shardingItem}/instance  value: jobInstanceId = {ip}@-@{pid}  CreateMode.PERSISTENT;

        // 删除 正在进行执行分片操作的标示 和 需要执行分片操作的 标示 ，完成分片操作的执行
        // 删除 /leader/sharding/necessary
        // 删除 /leader/sharding/processing
        jobNodeStorage.executeInTransaction(new PersistShardingInfoTransactionExecutionCallback(jobShardingStrategy.sharding(availableJobInstances, jobName, shardingTotalCount)));
        log.debug("Job '{}' sharding complete.", jobName);
    }
    
    private void blockUntilShardingCompleted() {
        while (!leaderService.isLeaderUntilBlock() && (jobNodeStorage.isJobNodeExisted(ShardingNode.NECESSARY) || jobNodeStorage.isJobNodeExisted(ShardingNode.PROCESSING))) {
            log.debug("Job '{}' sleep short time until sharding completed.", jobName);
            BlockUtils.waitingShortTime();
        }
    }
    
    private void waitingOtherJobCompleted() {
        while (executionService.hasRunningItems()) {
            log.debug("Job '{}' sleep short time until other job completed.", jobName);
            BlockUtils.waitingShortTime();
        }
    }
    
    private void resetShardingInfo(final int shardingTotalCount) {
        // 先删除原来的分片Znode节点信息(/sharding/{shardingItem}/instance);
        for (int i = 0; i < shardingTotalCount; i++) {
            // 删除节点 /sharding/{shardingItem}/instance
            jobNodeStorage.removeJobNodeIfExisted(ShardingNode.getInstanceNode(i));

            // 不存在则新增节点 /sharding/{shardingItem}  CreateMode.PERSISTENT
            jobNodeStorage.createJobNodeIfNeeded(ShardingNode.ROOT + "/" + i);
        }
        // 如果分片数减少则删除多余的分片节点Znode
        int actualShardingTotalCount = jobNodeStorage.getJobNodeChildrenKeys(ShardingNode.ROOT).size();
        if (actualShardingTotalCount > shardingTotalCount) {
            for (int i = shardingTotalCount; i < actualShardingTotalCount; i++) {
                jobNodeStorage.removeJobNodeIfExisted(ShardingNode.ROOT + "/" + i);
            }
        }
    }
    
    /**
     * 获取作业运行实例的分片项集合.
     *
     * /sharding/{shardingItem}/instance 节点数据内容等于 jobInstanceId 的shardingItem集合
     *
     * @param jobInstanceId 作业运行实例主键
     * @return 作业运行实例的分片项集合
     */
    public List<Integer> getShardingItems(final String jobInstanceId) {
        JobInstance jobInstance = new JobInstance(jobInstanceId);
        if (!serverService.isAvailableServer(jobInstance.getIp())) {
            return Collections.emptyList();
        }
        List<Integer> result = new LinkedList<>();
        int shardingTotalCount = configService.load(true).getTypeConfig().getCoreConfig().getShardingTotalCount();
        for (int i = 0; i < shardingTotalCount; i++) {
            if (jobInstance.getJobInstanceId().equals(jobNodeStorage.getJobNodeData(ShardingNode.getInstanceNode(i)))) {
                result.add(i);
            }
        }
        return result;
    }
    
    /**
     * 获取运行在本作业实例的分片项集合.
     *
     * @return 运行在本作业实例的分片项集合
     */
    public List<Integer> getLocalShardingItems() {
        if (JobRegistry.getInstance().isShutdown(jobName) || !serverService.isAvailableServer(JobRegistry.getInstance().getJobInstance(jobName).getIp())) {
            return Collections.emptyList();
        }
        return getShardingItems(JobRegistry.getInstance().getJobInstance(jobName).getJobInstanceId());
    }
    
    /**
     * 查询是包含有分片节点的不在线服务器.
     * 
     * @return 是包含有分片节点的不在线服务器
     */
    public boolean hasShardingInfoInOfflineServers() {
        List<String> onlineInstances = jobNodeStorage.getJobNodeChildrenKeys(InstanceNode.ROOT);
        int shardingTotalCount = configService.load(true).getTypeConfig().getCoreConfig().getShardingTotalCount();
        for (int i = 0; i < shardingTotalCount; i++) {
            if (!onlineInstances.contains(jobNodeStorage.getJobNodeData(ShardingNode.getInstanceNode(i)))) {
                return true;
            }
        }
        return false;
    }

    @RequiredArgsConstructor
    class PersistShardingInfoTransactionExecutionCallback implements TransactionExecutionCallback {
        
        private final Map<JobInstance, List<Integer>> shardingResults;

        /**
         *             // 保存分片节点信息Znode, 新增 Znode: /sharding/{shardingItem}/instance  value: jobInstanceId = {ip}@-@{pid}
         *             // 删除 /leader/sharding/necessary
         *             // 删除 /leader/sharding/processing
         * @param curatorTransactionFinal 执行事务的上下文
         * @throws Exception
         */
        @Override
        public void execute(final CuratorTransactionFinal curatorTransactionFinal) throws Exception {
            for (Map.Entry<JobInstance, List<Integer>> entry : shardingResults.entrySet()) {
                for (int shardingItem : entry.getValue()) {
                    //新增 Znode: /sharding/{shardingItem}/instance  value: jobInstanceId = {ip}@-@{pid}  CreateMode.PERSISTENT;
                    /**
                     * {@link CreateBuilderImpl}
                     */
                    curatorTransactionFinal.create().forPath(jobNodePath.getFullPath(ShardingNode.getInstanceNode(shardingItem)), entry.getKey().getJobInstanceId().getBytes()).and();
                }
            }
            curatorTransactionFinal.delete().forPath(jobNodePath.getFullPath(ShardingNode.NECESSARY)).and();
            curatorTransactionFinal.delete().forPath(jobNodePath.getFullPath(ShardingNode.PROCESSING)).and();
        }
    }
}
