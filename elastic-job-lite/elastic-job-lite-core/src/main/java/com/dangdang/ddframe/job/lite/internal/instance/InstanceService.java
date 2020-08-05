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

package com.dangdang.ddframe.job.lite.internal.instance;

import com.dangdang.ddframe.job.lite.api.strategy.JobInstance;
import com.dangdang.ddframe.job.lite.internal.server.ServerService;
import com.dangdang.ddframe.job.lite.internal.storage.JobNodeStorage;
import com.dangdang.ddframe.job.reg.base.CoordinatorRegistryCenter;
import org.apache.zookeeper.CreateMode;

import java.util.LinkedList;
import java.util.List;

/**
 * 作业运行实例服务.
 * 
 * @author zhangliang
 */
public final class InstanceService {
    
    private final JobNodeStorage jobNodeStorage;
    
    private final InstanceNode instanceNode;
    
    private final ServerService serverService;
    
    public InstanceService(final CoordinatorRegistryCenter regCenter, final String jobName) {
        jobNodeStorage = new JobNodeStorage(regCenter, jobName);
        instanceNode = new InstanceNode(jobName);
        serverService = new ServerService(regCenter, jobName);
    }

    /**
     * job作业(服务应用)实例 上线信息
     * 节点 /{jobName}/instances/{@link JobInstance#jobInstanceId}
     * 节点 /{jobName}/instances/{ip}@-@{pid}
     * 值 ""
     * 节点类型 {@link CreateMode.EPHEMERAL}
     *
     * 持久化作业运行实例上线相关信息.
     */
    public void persistOnline() {
        jobNodeStorage.fillEphemeralJobNode(instanceNode.getLocalInstanceNode(), "");
    }
    
    /**
     * 删除作业运行状态.
     */
    public void removeInstance() {
        jobNodeStorage.removeJobNodeIfExisted(instanceNode.getLocalInstanceNode());
    }
    
    /**
     * 清理作业触发标记.
     */
    public void clearTriggerFlag() {
        jobNodeStorage.updateJobNode(instanceNode.getLocalInstanceNode(), "");
    }
    
    /**
     * 返回作业节点所在机器状态是可用的所有作业节点JobInstance， 即任务作业未下线且所在的机器也未下线的所有未下线的所有节点
     * 获取可分片的作业运行实例.
     *
     * /{jobName}/instances/{jobInstanceId} 存在
     * /{jobName}/servers/{ip} value不等于 ServerStatus.DISABLED.name()
     *
     * 作业节点JobInstance 在{@link ZookeeperRegistryCenter#getChildrenKeys} 做了排序
     * 按 jobInstanceId 从大到小排序
     *
     * @return 可分片的作业运行实例
     */
    public List<JobInstance> getAvailableJobInstances() {
        List<JobInstance> result = new LinkedList<>();
        for (String each : jobNodeStorage.getJobNodeChildrenKeys(InstanceNode.ROOT)) {
            JobInstance jobInstance = new JobInstance(each);
            if (serverService.isEnableServer(jobInstance.getIp())) {
                result.add(new JobInstance(each));
            }
        }
        return result;
    }
    
    /**
     * 判断当前作业运行实例的节点是否仍然存在.
     * 
     * @return 当前作业运行实例的节点是否仍然存在
     */
    public boolean isLocalJobInstanceExisted() {
        return jobNodeStorage.isJobNodeExisted(instanceNode.getLocalInstanceNode());
    }
}
