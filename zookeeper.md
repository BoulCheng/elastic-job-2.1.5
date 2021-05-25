

```
zhenglubiaodeMacBook-Pro:bin zlb$ zkCli.sh
[zk: localhost:2181(CONNECTED) 22] ls /
[elastic-job-demo-2, elastic-job-demo, zookeeper]
[zk: localhost:2181(CONNECTED) 23]
[zk: localhost:2181(CONNECTED) 23] ls /elastic-job-demo
[demoSimpleJob]
[zk: localhost:2181(CONNECTED) 24] ls /elastic-job-demo/demoSimpleJob
[leader, servers, config, instances, sharding]
[zk: localhost:2181(CONNECTED) 25] ls /elastic-job-demo/demoSimpleJob/leader
[election, sharding]
[zk: localhost:2181(CONNECTED) 26] ls /elastic-job-demo/demoSimpleJob/leader/election
[latch, instance]
[zk: localhost:2181(CONNECTED) 27] ls /elastic-job-demo/demoSimpleJob/leader/election/latch
[]
[zk: localhost:2181(CONNECTED) 28] ls /elastic-job-demo/demoSimpleJob/leader/election/instance
[]
[zk: localhost:2181(CONNECTED) 29]
[zk: localhost:2181(CONNECTED) 29]
[zk: localhost:2181(CONNECTED) 29] ls /elastic-job-demo/demoSimpleJob/servers
[192.168.1.180, 192.168.31.135, 192.168.1.181]
[zk: localhost:2181(CONNECTED) 30]
[zk: localhost:2181(CONNECTED) 30] ls /elastic-job-demo/demoSimpleJob/servers/192.168.1.180
[]
[zk: localhost:2181(CONNECTED) 31] ls /elastic-job-demo/demoSimpleJob/servers/192.168.31.135
[]
[zk: localhost:2181(CONNECTED) 32] ls /elastic-job-demo/demoSimpleJob/servers/192.168.1.181
[]
[zk: localhost:2181(CONNECTED) 33]
[zk: localhost:2181(CONNECTED) 33]
[zk: localhost:2181(CONNECTED) 33]
[zk: localhost:2181(CONNECTED) 33] ls /elastic-job-demo/demoSimpleJob/config
[]
[zk: localhost:2181(CONNECTED) 34]
[zk: localhost:2181(CONNECTED) 34]
[zk: localhost:2181(CONNECTED) 34] ls /elastic-job-demo/demoSimpleJob/instances
[192.168.1.180@-@1131]
[zk: localhost:2181(CONNECTED) 35] ls /elastic-job-demo/demoSimpleJob/instances/192.168.1.180@-@1131
[]
[zk: localhost:2181(CONNECTED) 36]
[zk: localhost:2181(CONNECTED) 36]
[zk: localhost:2181(CONNECTED) 36] ls /elastic-job-demo/demoSimpleJob/sharding
[0, 1]
[zk: localhost:2181(CONNECTED) 37]
[zk: localhost:2181(CONNECTED) 37] ls /elastic-job-demo/demoSimpleJob/sharding/0
[instance]
[zk: localhost:2181(CONNECTED) 38] ls /elastic-job-demo/demoSimpleJob/sharding/0/instance
[]
[zk: localhost:2181(CONNECTED) 39]
[zk: localhost:2181(CONNECTED) 39] ls /elastic-job-demo/demoSimpleJob/sharding/1
[instance]
[zk: localhost:2181(CONNECTED) 40] ls /elastic-job-demo/demoSimpleJob/sharding/1/instance
[]
[zk: localhost:2181(CONNECTED) 41]
[zk: localhost:2181(CONNECTED) 41]
```

```
zhenglubiaodeMacBook-Pro:~ zlb$ echo dump | nc localhost 2181
SessionTracker dump:
Session Sets (11):
0 expire at Fri Jan 09 00:37:24 CST 1970:
0 expire at Fri Jan 09 00:37:26 CST 1970:
0 expire at Fri Jan 09 00:37:30 CST 1970:
0 expire at Fri Jan 09 00:37:34 CST 1970:
0 expire at Fri Jan 09 00:37:36 CST 1970:
0 expire at Fri Jan 09 00:37:40 CST 1970:
0 expire at Fri Jan 09 00:37:44 CST 1970:
1 expire at Fri Jan 09 00:37:46 CST 1970:
	0x1002784c1460004
1 expire at Fri Jan 09 00:37:50 CST 1970:
	0x1002784c1460006
0 expire at Fri Jan 09 00:37:54 CST 1970:
2 expire at Fri Jan 09 00:38:04 CST 1970:
	0x1002784c1460005
	0x1002784c1460003
ephemeral nodes dump:
Sessions with Ephemerals (2):
0x1002784c1460003:
	/elastic-job-demo/demoSimpleJob/leader/election/instance
	/elastic-job-demo/demoSimpleJob/instances/192.168.1.180@-@1131
0x1002784c1460005:
	/elastic-job-demo/demoSimpleJob/instances/192.168.1.180@-@1199
zhenglubiaodeMacBook-Pro:~ zlb$
zhenglubiaodeMacBook-Pro:~ zlb$
```

- 在shell终端输入：echo xxxx | nc localhost 2181

