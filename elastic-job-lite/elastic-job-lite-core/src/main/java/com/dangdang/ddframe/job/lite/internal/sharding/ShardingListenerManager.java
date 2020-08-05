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

import com.dangdang.ddframe.job.lite.internal.config.ConfigurationNode;
import com.dangdang.ddframe.job.lite.internal.config.LiteJobConfigurationGsonFactory;
import com.dangdang.ddframe.job.lite.internal.instance.InstanceNode;
import com.dangdang.ddframe.job.lite.internal.listener.AbstractJobListener;
import com.dangdang.ddframe.job.lite.internal.listener.AbstractListenerManager;
import com.dangdang.ddframe.job.lite.internal.schedule.JobRegistry;
import com.dangdang.ddframe.job.lite.internal.server.ServerNode;
import com.dangdang.ddframe.job.reg.base.CoordinatorRegistryCenter;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent.Type;

/**
 * 分片监听管理器.
 * 
 * @author zhangliang
 */
public final class ShardingListenerManager extends AbstractListenerManager {
    
    private final String jobName;
    
    private final ConfigurationNode configNode;
    
    private final InstanceNode instanceNode;
    
    private final ServerNode serverNode;
    
    private final ShardingService shardingService;
    
    public ShardingListenerManager(final CoordinatorRegistryCenter regCenter, final String jobName) {
        super(regCenter, jobName);
        this.jobName = jobName;
        configNode = new ConfigurationNode(jobName);
        instanceNode = new InstanceNode(jobName);
        serverNode = new ServerNode(jobName);
        shardingService = new ShardingService(regCenter, jobName);
    }
    
    @Override
    public void start() {
        addDataListener(new ShardingTotalCountChangedJobListener());
        addDataListener(new ListenServersChangedJobListener());
    }

    /**
     * 作业总分片数变更 标示需要重新进行分片(创建Znode /leader/sharding/necessary CreateMode.PERSISTENT)
     */
    class ShardingTotalCountChangedJobListener extends AbstractJobListener {
        
        @Override
        protected void dataChanged(final String path, final Type eventType, final String data) {
            if (configNode.isConfigPath(path) && 0 != JobRegistry.getInstance().getCurrentShardingTotalCount(jobName)) {// TODO: 2020/8/3  此处有bug
                int newShardingTotalCount = LiteJobConfigurationGsonFactory.fromJson(data).getTypeConfig().getCoreConfig().getShardingTotalCount();
                if (newShardingTotalCount != JobRegistry.getInstance().getCurrentShardingTotalCount(jobName)) {
                    shardingService.setReshardingFlag();
                    //变更本地内存作业的总分片数
                    JobRegistry.getInstance().setCurrentShardingTotalCount(jobName, newShardingTotalCount);
                }
            }
        }
    }

    /**
     * 作业实例上下线 (/jobName/jobName/instances 开头的节点新增或删除) 或者 作业实例所在的服务器机器变更 (Znode /jobName/servers/{ip} )
     *
     * 则设置需要重新分片的标示
     */
    class ListenServersChangedJobListener extends AbstractJobListener {
        
        @Override
        protected void dataChanged(final String path, final Type eventType, final String data) {
            if (!JobRegistry.getInstance().isShutdown(jobName) && (isInstanceChange(eventType, path) || isServerChange(path))) {
                shardingService.setReshardingFlag();
            }
        }

        /**
         * /jobName/jobName/instances 开头的节点新增或删除
         * @param eventType
         * @param path
         * @return
         */
        private boolean isInstanceChange(final Type eventType, final String path) {
            return instanceNode.isInstancePath(path) && Type.NODE_UPDATED != eventType;
        }

        /**
         * Znode /jobName/servers/{ip}
         */
        private boolean isServerChange(final String path) {
            return serverNode.isServerPath(path);
        }
    }
}
