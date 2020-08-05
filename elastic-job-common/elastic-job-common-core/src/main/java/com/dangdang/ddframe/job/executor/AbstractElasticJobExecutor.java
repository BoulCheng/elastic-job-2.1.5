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

package com.dangdang.ddframe.job.executor;

import com.dangdang.ddframe.job.api.ShardingContext;
import com.dangdang.ddframe.job.config.JobRootConfiguration;
import com.dangdang.ddframe.job.event.type.JobExecutionEvent;
import com.dangdang.ddframe.job.event.type.JobStatusTraceEvent.State;
import com.dangdang.ddframe.job.exception.ExceptionUtil;
import com.dangdang.ddframe.job.exception.JobExecutionEnvironmentException;
import com.dangdang.ddframe.job.exception.JobSystemException;
import com.dangdang.ddframe.job.executor.handler.ExecutorServiceHandler;
import com.dangdang.ddframe.job.executor.handler.ExecutorServiceHandlerRegistry;
import com.dangdang.ddframe.job.executor.handler.JobExceptionHandler;
import com.dangdang.ddframe.job.executor.handler.JobProperties;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/**
 * 弹性化分布式作业执行器.
 *
 * @author zhangliang
 */
@Slf4j
public abstract class AbstractElasticJobExecutor {
    
    @Getter(AccessLevel.PROTECTED)
    private final JobFacade jobFacade;
    
    @Getter(AccessLevel.PROTECTED)
    private final JobRootConfiguration jobRootConfig;
    
    private final String jobName;
    
    private final ExecutorService executorService;
    
    private final JobExceptionHandler jobExceptionHandler;
    
    private final Map<Integer, String> itemErrorMessages;
    
    protected AbstractElasticJobExecutor(final JobFacade jobFacade) {
        this.jobFacade = jobFacade;
        jobRootConfig = jobFacade.loadJobRootConfiguration(true);
        jobName = jobRootConfig.getTypeConfig().getCoreConfig().getJobName();
        executorService = ExecutorServiceHandlerRegistry.getExecutorServiceHandler(jobName, (ExecutorServiceHandler) getHandler(JobProperties.JobPropertiesEnum.EXECUTOR_SERVICE_HANDLER));
        jobExceptionHandler = (JobExceptionHandler) getHandler(JobProperties.JobPropertiesEnum.JOB_EXCEPTION_HANDLER);
        itemErrorMessages = new ConcurrentHashMap<>(jobRootConfig.getTypeConfig().getCoreConfig().getShardingTotalCount(), 1);
    }

    /**
     * 可以自定义分片任务执行的线程池和异常处理器
     * @param jobPropertiesEnum
     * @return
     */
    private Object getHandler(final JobProperties.JobPropertiesEnum jobPropertiesEnum) {
        // TODO: 2020/7/31
        String handlerClassName = jobRootConfig.getTypeConfig().getCoreConfig().getJobProperties().get(jobPropertiesEnum);
        try {
            Class<?> handlerClass = Class.forName(handlerClassName);
            if (jobPropertiesEnum.getClassType().isAssignableFrom(handlerClass)) {
                return handlerClass.newInstance();
            }
            return getDefaultHandler(jobPropertiesEnum, handlerClassName);
        } catch (final ReflectiveOperationException ex) {
            return getDefaultHandler(jobPropertiesEnum, handlerClassName);
        }
    }
    
    private Object getDefaultHandler(final JobProperties.JobPropertiesEnum jobPropertiesEnum, final String handlerClassName) {
        log.warn("Cannot instantiation class '{}', use default '{}' class.", handlerClassName, jobPropertiesEnum.getKey());
        try {
            return Class.forName(jobPropertiesEnum.getDefaultValue()).newInstance();
        } catch (final ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            throw new JobSystemException(e);
        }
    }
    
    /**
     * 执行作业.
     *
     * 任务执行  通过 /sharding/{shardingItem}/instance/{jobInstanceId} 获取当前任务的作业分片
     */
    public final void execute() {
        try {
            // 检查本机与注册中心的时间误差秒数是否在允许范围
            jobFacade.checkJobExecutionEnvironment();
        } catch (final JobExecutionEnvironmentException cause) {
            jobExceptionHandler.handleException(jobName, cause);
        }
        ShardingContexts shardingContexts = jobFacade.getShardingContexts();
        if (shardingContexts.isAllowSendJobEvent()) {
            jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_STAGING, String.format("Job '%s' execute begin.", jobName));
        }

        // 如果当前有任一分片项正在运行则设置该任务被错过执行的标记
        // 为每个分片项创建Znode /sharding/{shardingItem}/misfire
        if (jobFacade.misfireIfRunning(shardingContexts.getShardingItemParameters().keySet())) { // monitorExecution 和 misfire的关系
            if (shardingContexts.isAllowSendJobEvent()) {
                jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_FINISHED, String.format(
                        "Previous job '%s' - shardingItems '%s' is still running, misfired job will start after previous job completed.", jobName, 
                        shardingContexts.getShardingItemParameters().keySet()));
            }
            return;
        }
        try {
            // 执行监听器
            jobFacade.beforeJobExecuted(shardingContexts);
            //CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            //CHECKSTYLE:ON
            jobExceptionHandler.handleException(jobName, cause);
        }
        //作业执行 123 -> 12
        execute(shardingContexts, JobExecutionEvent.ExecutionSource.NORMAL_TRIGGER);

        // TODO: 2020/7/31
        // 执行被错过执行的分片项 /sharding/{shardingItem}/misfire

        // 需要考虑重新分片的情况 会导致重复执行 !!!
        // 在shardingContexts生成之后 /sharding/{shardingItem}/running 节点生成之前，执行了重新分片，且分片项变少了的情况下
        while (jobFacade.isExecuteMisfired(shardingContexts.getShardingItemParameters().keySet())) {
            jobFacade.clearMisfire(shardingContexts.getShardingItemParameters().keySet());
            execute(shardingContexts, JobExecutionEvent.ExecutionSource.MISFIRE);
        }
        // 立刻触发一次作业执行一个失效转移的分片项
        // /leader/failover/items 存在且存在子节点且作业实例没有正在执行
        jobFacade.failoverIfNecessary();
        try {
            jobFacade.afterJobExecuted(shardingContexts);
            //CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            //CHECKSTYLE:ON
            jobExceptionHandler.handleException(jobName, cause);
        }
    }
    
    private void execute(final ShardingContexts shardingContexts, final JobExecutionEvent.ExecutionSource executionSource) {
        if (shardingContexts.getShardingItemParameters().isEmpty()) {
            if (shardingContexts.isAllowSendJobEvent()) {
                jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_FINISHED, String.format("Sharding item for job '%s' is empty.", jobName));
            }
            return;
        }
        // 本地内存标示作业正在运行
        // monitorExecution 为true 则添加Znode /sharding/{shardingItem}/running 标示 分片正在运行
        jobFacade.registerJobBegin(shardingContexts);
        String taskId = shardingContexts.getTaskId();
        if (shardingContexts.isAllowSendJobEvent()) {
            jobFacade.postJobStatusTraceEvent(taskId, State.TASK_RUNNING, "");
        }
        try {
            process(shardingContexts, executionSource);
        } finally {
            // TODO 考虑增加作业失败的状态，并且考虑如何处理作业失败的整体回路
            // registerJobBegin 反向操作
            // 并 标示已经执行完 需要运行在本作业实例的失效转移分片项集合， 移除节点 sharding/{shardingItem}/failover
            jobFacade.registerJobCompleted(shardingContexts);
            // TODO: 2020/7/31 具体分片项 /sharding/{shardingItem}/running 正在运行的标示应该分开清除 在分片项执行完就立即清除 而不是所有分片项都处理完一起清除 这样可以更细粒度的控制分片的执行中的状态
            if (itemErrorMessages.isEmpty()) {
                if (shardingContexts.isAllowSendJobEvent()) {
                    jobFacade.postJobStatusTraceEvent(taskId, State.TASK_FINISHED, "");
                }
            } else {
                if (shardingContexts.isAllowSendJobEvent()) {
                    jobFacade.postJobStatusTraceEvent(taskId, State.TASK_ERROR, itemErrorMessages.toString());
                }
            }
        }
    }
    
    private void process(final ShardingContexts shardingContexts, final JobExecutionEvent.ExecutionSource executionSource) {
        Collection<Integer> items = shardingContexts.getShardingItemParameters().keySet();
        // 作业实例处理单个分片项
        if (1 == items.size()) {
            int item = shardingContexts.getShardingItemParameters().keySet().iterator().next();
            JobExecutionEvent jobExecutionEvent =  new JobExecutionEvent(shardingContexts.getTaskId(), jobName, executionSource, item);
            process(shardingContexts, item, jobExecutionEvent);
            // TODO: 2020/7/31 在这里清除当前分片项正在被执行的标示  /sharding/{shardingItem}/running
            return;
        }
        final CountDownLatch latch = new CountDownLatch(items.size());
        // 作业实例处理多个分片项时 开启多线程处理
        for (final int each : items) {
            final JobExecutionEvent jobExecutionEvent = new JobExecutionEvent(shardingContexts.getTaskId(), jobName, executionSource, each);
            if (executorService.isShutdown()) {// TODO: 2020/8/5   应该处理
                return;
            }
            executorService.submit(new Runnable() {
                
                @Override
                public void run() {
                    try {
                        process(shardingContexts, each, jobExecutionEvent);
                        // TODO: 2020/7/31 在这里清除当前分片项正在被执行的标示  /sharding/{shardingItem}/running
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try {
            latch.await();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void process(final ShardingContexts shardingContexts, final int item, final JobExecutionEvent startEvent) {
        if (shardingContexts.isAllowSendJobEvent()) {
            jobFacade.postJobExecutionEvent(startEvent);
        }
        log.trace("Job '{}' executing, item is: '{}'.", jobName, item);
        JobExecutionEvent completeEvent;
        try {
            // 真正执行job
            process(new ShardingContext(shardingContexts, item));
            completeEvent = startEvent.executionSuccess();
            log.trace("Job '{}' executed, item is: '{}'.", jobName, item);
            if (shardingContexts.isAllowSendJobEvent()) {
                // TODO: 2020/7/31  
                jobFacade.postJobExecutionEvent(completeEvent);
            }
            // CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            // CHECKSTYLE:ON
            completeEvent = startEvent.executionFailure(cause);
            jobFacade.postJobExecutionEvent(completeEvent);
            itemErrorMessages.put(item, ExceptionUtil.transform(cause));
            jobExceptionHandler.handleException(jobName, cause);
        }
    }
    
    protected abstract void process(ShardingContext shardingContext);
}
