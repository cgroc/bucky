package com.itv.bucky.taskz

import com.itv.bucky
import com.itv.bucky.suite.{ChannelAmqpPurgeTest, RequeueStrategy, TestFixture}
import scalaz.concurrent.Task

class TaskChannelAmqpPurgeTest extends ChannelAmqpPurgeTest[Task] with TaskEffectVerification {

  override def withPublisher(testQueueName: bucky.QueueName,
                             requeueStrategy: RequeueStrategy[Task],
                             shouldDeclare: Boolean)(f: (TestFixture[Task]) => Unit): Unit =
    IntegrationUtils.withPublisher(testQueueName, requeueStrategy, shouldDeclare)(f)

}
