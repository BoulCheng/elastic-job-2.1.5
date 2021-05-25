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

package com.dangdang.ddframe.job.lite.internal.failover;

import com.dangdang.ddframe.job.lite.config.LiteJobConfiguration;
import com.dangdang.ddframe.job.lite.internal.config.ConfigurationNode;
import com.dangdang.ddframe.job.lite.internal.config.ConfigurationService;
import com.dangdang.ddframe.job.lite.internal.config.LiteJobConfigurationGsonFactory;
import com.dangdang.ddframe.job.lite.internal.instance.InstanceNode;
import com.dangdang.ddframe.job.lite.internal.listener.AbstractJobListener;
import com.dangdang.ddframe.job.lite.internal.listener.AbstractListenerManager;
import com.dangdang.ddframe.job.lite.internal.schedule.JobRegistry;
import com.dangdang.ddframe.job.lite.internal.sharding.ShardingService;
import com.dangdang.ddframe.job.reg.base.CoordinatorRegistryCenter;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent.Type;

import java.util.List;

/**
 * 失效转移监听管理器.
 * 
 * @author zhangliang
 */
public final class FailoverListenerManager extends AbstractListenerManager {
    
    private final String jobName;
    
    private final ConfigurationService configService;
    
    private final ShardingService shardingService;
    
    private final FailoverService failoverService;
    
    private final ConfigurationNode configNode;
    
    private final InstanceNode instanceNode;
    
    public FailoverListenerManager(final CoordinatorRegistryCenter regCenter, final String jobName) {
        super(regCenter, jobName);
        this.jobName = jobName;
        configService = new ConfigurationService(regCenter, jobName);
        shardingService = new ShardingService(regCenter, jobName);
        failoverService = new FailoverService(regCenter, jobName);
        configNode = new ConfigurationNode(jobName);
        instanceNode = new InstanceNode(jobName);
    }
    
    @Override
    public void start() {
        addDataListener(new JobCrashedJobListener());
        addDataListener(new FailoverSettingsChangedJobListener());
    }
    
    private boolean isFailoverEnabled() {
        LiteJobConfiguration jobConfig = configService.load(true);
        return null != jobConfig && jobConfig.isFailover();
    }
    
    class JobCrashedJobListener extends AbstractJobListener {
        /**
         *
         * 这种失效转移的意义在哪里？
         * 可能会造成多执行一次这些分片项，
         * 因为在作业触发调度执行时会先判断是否要重新分片 如果重新分片后所有作业实例都正常执行了所有的分片项 并且在下次作业调度前作业实例crash了 就会造成该作业实例原本处理的分片项被多执行一次
         * 这种处理方式只有当本次作业触发调度所有分片都未开始执行 才能保证是完全正确的 即没有任何分片被重复执行 也没有任何分片漏掉了执行
         * 现在这种处理策略是保证不漏掉执行，但能接受分片项重复执行(其实这种执行策略大概率分片项是要重复执行的)
         *
         * .分片项正在被执行的标示应该正在分片项被执行后就立即清除的修改后 在分片正在被处理的过程中发生作业实例crash才做失效转移 这种策略适用于尽量不漏掉执行但分片项一定不能重复执行的场景
         *
         *
         *
         *
         * 作业实例下线 会话失效 Znode 被删除
         *
         * 处理失效转移 即作业实例crash 该作业运行实例的分片项集合 转移给其他作业实例处理一次 注意只是处理一次(以后的处理由重新分片处理)
         * 通过新增节点 /leader/failover/items/{shardingItem} [PERSISTENT] 设置失效的分片项标记.
         *
         * 并执行作业失效转移操作{@link FailoverService#failoverIfNecessary()}
         *
         * @param path
         * @param eventType
         * @param data
         */
        @Override
        protected void dataChanged(final String path, final Type eventType, final String data) {
            // TODO: 2020/7/31
            if (isFailoverEnabled() && Type.NODE_REMOVED == eventType && instanceNode.isInstancePath(path)) {
                String jobInstanceId = path.substring(instanceNode.getInstanceFullPath().length() + 1);
                if (jobInstanceId.equals(JobRegistry.getInstance().getJobInstance(jobName).getJobInstanceId())) {
                    return;
                }
                List<Integer> failoverItems = failoverService.getFailoverItems(jobInstanceId);
                if (!failoverItems.isEmpty()) {
                    //  /sharding/{shardingItem}/failover (EPHEMERAL) 是临时节点 既然jobInstanceId作业实例会话失效了那么该临时节点也会被自动删除
                    // 所以这个处理没有太多意义 failoverItems 一定会为空
                    for (int each : failoverItems) {
                        failoverService.setCrashedFailoverFlag(each);
                        failoverService.failoverIfNecessary();
                    }
                } else {
                    // 提供一种策略 不重复执行  // TODO: 2020/8/4 获取正在执行的分片项
                    for (int each : shardingService.getShardingItems(jobInstanceId)) {
                        failoverService.setCrashedFailoverFlag(each);
                        failoverService.failoverIfNecessary();
                    }
                }
            }
        }
    }

    /**
     * 配置更新 failover 被更新为false
     * 删除作业失效转移信息
     */
    class FailoverSettingsChangedJobListener extends AbstractJobListener {
        
        @Override
        protected void dataChanged(final String path, final Type eventType, final String data) {
            if (configNode.isConfigPath(path) && Type.NODE_UPDATED == eventType && !LiteJobConfigurationGsonFactory.fromJson(data).isFailover()) {
                failoverService.removeFailoverInfo();
            }
        }
    }
}
