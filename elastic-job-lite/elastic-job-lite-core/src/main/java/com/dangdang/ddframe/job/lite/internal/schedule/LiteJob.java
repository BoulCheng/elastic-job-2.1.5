package com.dangdang.ddframe.job.lite.internal.schedule;

import com.dangdang.ddframe.job.api.ElasticJob;
import com.dangdang.ddframe.job.executor.JobExecutorFactory;
import com.dangdang.ddframe.job.executor.JobFacade;
import com.dangdang.ddframe.job.lite.api.JobScheduler;
import lombok.Setter;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.simpl.PropertySettingJobFactory;

/**
 * Lite调度作业.
 *
 * @author zhangliang
 */

/**
 * quartz触发定时任务时 调用 {@link Job#execute(JobExecutionContext)}
 */
public final class LiteJob implements Job {

    @Setter
    private ElasticJob elasticJob;

    /**
     * setJobFacade:21, LiteJob (com.dangdang.ddframe.job.lite.internal.schedule)
     * invoke0:-1, NativeMethodAccessorImpl (sun.reflect)
     * invoke:62, NativeMethodAccessorImpl (sun.reflect)
     * invoke:43, DelegatingMethodAccessorImpl (sun.reflect)
     * invoke:498, Method (java.lang.reflect)
     * setBeanProps:194, PropertySettingJobFactory (org.quartz.simpl)
     * newJob:76, PropertySettingJobFactory (org.quartz.simpl)
     * initialize:127, JobRunShell (org.quartz.core)
     * run:375, QuartzSchedulerThread (org.quartz.core)
     */
    /**
     * 在 {@link PropertySettingJobFactory#setBeanProps(Object, JobDataMap)} 处理依赖注入
     * 依赖的对象在 {@link JobScheduler#createJobDetail(String)} 保存进 JobDataMap
     */
    @Setter
    private JobFacade jobFacade;
    
    @Override
    public void execute(final JobExecutionContext context) throws JobExecutionException {
        // TODO: 2020/7/29  
        JobExecutorFactory.getJobExecutor(elasticJob, jobFacade).execute();
    }
}
