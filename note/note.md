
### quartz
- Scheduler(实现类StdScheduler)
- JobDetail(实现类JobDetailImpl)
    - Job
        - 实现类 LiteJob
    - Job的依赖
        - elasticJob
            - SimpleJob extends ElasticJob
        - jobFacade
            - LiteJobFacade implements JobFacade 
    - 会为Job对象自动注入Job的依赖
- Scheduler#scheduleJob(JobDetail, Trigger) 
    - Trigger
        - CronTriggerImpl implements CronTrigger extends Trigger
        - SimpleTriggerImpl implements SimpleTrigger extends Trigger
            - SimpleTriggerImpl#repeatInterval
            - SimpleTriggerImpl#startTime

- QuartzSchedulerThread
    - 线程死循环获取下一次要触发的 Trigger
        - now+idleWaitTime：这是一个现在的时刻加上了一个空闲时间，默认30秒，它和后面的timeWindow（默认0秒）组成了要获取的待触发的触发器的时间范围，即下次触发时间在now~now+30+0的时间范围内的触发器
        - 获取的触发器除了将要触发的触发器，还包括过期的触发器。但是这个过期有个时间范围，默认是60s，也就是过了下次触发时间60秒之内的触发器也算作合法的触发器。综上，获取的触发器就包括在将来一段时间内要触发的触发器，和刚过期一小会儿的触发器
        - 只会取获得的Trigger列表中触发时间离当前最近的一个Trigger 
    - 并循环一直判断 该Trigger的下次触发时间nextFireTime是否到达当前时间，到达则执行，否则一直循环判断
    - 通过Trigger获得JobDetail
    - 执行JobDetail中的Job
        - 调用Job#execute(JobExecutionContext)
        
- Misfire处理策略(misfireInstruction)        
    - Misfire：当一个作业在配置的规定时间没有运行（比如线程池里面没有可用的线程、作业被暂停等）并且作业配置的应该运行时刻为A，当前时间为B，如果B与A的时间间隔超过misfireThreshold配置的值（默认为60秒）则作业会被调度程序认为Misfire。
    - 当作业misfire后，调度程序会根据配置的Misfire策略进行处理
    - 对于SimpleTrigger的处理策略
        - MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_EXISTING_REPEAT_COUNT： 调度引擎重新调度该任务，repeat count 保持不变，按照原有制定的执行方案执行repeat count次，但是，如果当前时间，已经晚于 end-time，那么这个触发器将不会再被触发
    - 对于CronTrigger的处理策略
        - 超时后会被立即安排执行
        - 不会被立即触发，而是获取下一个被触发的时间
- misfireThreshold 
    - 即触发器超时的临界值，它可以在quartz.properties文件中配置。misfireThreshold是用来设置调度引擎对触发器超时的忍耐时间。假设misfireThreshold设置为60s，那么它的意思说当一个触发器超时时间大于misfireThreshold时，调度器引擎就认为这个触发器真正的超时(即Misfires)。换言之，如果一个触发器超时时间小于设定的misfireThreshold， 那么调度引擎则不认为触发器超时。也就是说这个job并没发生misfire       