# 分布式毫秒级别作业调度

- elastic-job原本通过quartz框架org.quartz.Scheduler#scheduleJob(JobDetail, Trigger)调度作业，且Trigger的实现类是org.quartz.impl.triggers.CronTriggerImpl

- CronTriggerImpl使用CronExpression配置作业调度时间

- 毫秒级别定时任务通过用org.quartz.impl.triggers.SimpleTriggerImpl替代CronTriggerImpl实现

- SimpleTriggerImpl支持间隔指定时间调度，且指定的时间可以设置为毫秒

- 具体配置
    - TriggerBuilder.newTrigger().startAt(new Date(Long.parseLong(arr[0]))).withIdentity(triggerIdentity).withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInMilliseconds(Long.parseLong(arr[1])).repeatForever().withMisfireHandlingInstructionNowWithExistingCount()).build();
    - 参数说明
        ```
        SimpleTriggerImpl st = new SimpleTriggerImpl();
        //重复执行的间隔时间 可设置为指定的毫秒数
        st.setRepeatInterval(this.interval);
        //重复次数  这里设置为永久
        st.setRepeatCount(this.repeatCount);
        //misfire策略 这里设置为立即执行
        st.setMisfireInstruction(this.misfireInstruction);
        ```
        

