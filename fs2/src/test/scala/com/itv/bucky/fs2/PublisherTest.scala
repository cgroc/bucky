package com.itv.bucky.fs2

import java.io.IOException
import java.util.concurrent.TimeoutException

import cats.effect.IO
import com.itv.bucky._
import com.rabbitmq.client.AMQP.Basic.Publish
import com.rabbitmq.client.AMQP.Confirm.Select
import com.rabbitmq.client._
import com.rabbitmq.client.impl.AMQImpl
import org.scalatest.FunSuite
import org.scalatest.Matchers._
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.Eventually.eventually

import scala.concurrent.duration._

class PublisherTest extends FunSuite {
  implicit val eventuallyPatienceConfig = Eventually.PatienceConfig(5.seconds, 100.millis)

  import TestIOExt._

  test("Publishing only returns success once publication is acknowledged with Id") {
    withPublisher() { publisher =>
      import publisher._
      channel.transmittedCommands should have size 1
      channel.transmittedCommands.last shouldBe an[AMQP.Confirm.Select]

      val task = publish(Any.publishCommand()).status

      eventually {
        channel.transmittedCommands should have size 2
        channel.transmittedCommands.last shouldBe an[AMQP.Basic.Publish]
      }

      task shouldBe 'running

      channel.replyWith(new AMQImpl.Basic.Ack(1L, false))

      eventually {
        task shouldBe 'success
      }
    }
  }

  test("Publishing only returns success once publication is acknowledged") {
    withPublisher() { publisher =>
      import publisher._
      channel.transmittedCommands should have size 1
      channel.transmittedCommands.last shouldBe an[Select]

      val task = publish(Any.publishCommand()).status

      eventually {
        channel.transmittedCommands should have size 2
        channel.transmittedCommands.last shouldBe an[Publish]
      }

      task shouldBe 'running

      channel.replyWith(new AMQImpl.Basic.Ack(1L, false))

      eventually {
        task shouldBe 'success
      }
    }
  }

  test("Negative acknowledgements result in failed future") {
    withPublisher() { publisher =>
      import publisher._
      val task = publish(Any.publishCommand()).status

      task shouldBe 'running

      channel.replyWith(new AMQImpl.Basic.Nack(1L, false, false))

      eventually {
        task.failure.getMessage should include("Nack")
      }
    }
  }

  test("Only futures corresponding to acknowledged publications are completed") {
    withPublisher() { publisher =>
      import publisher._

      val tasks = (1 to 3).map(_ => publish(Any.publishCommand()).status)

      atLeast(3, tasks) shouldBe 'running

      channel.replyWith(new AMQImpl.Basic.Ack(2L, true))

      eventually {
        atLeast(2, tasks) shouldBe 'completed
      }

      atLeast(1, tasks) should not be 'completed

      channel.replyWith(new AMQImpl.Basic.Ack(3L, false))

      atLeast(3, tasks) shouldBe 'completed
    }
  }

  test("Only futures corresponding to acknowledged publications are completed: negative acknowledgment (nack)") {
    withPublisher() { publisher =>
      import publisher._
      val tasks = (1 to 3).map(_ => publish(Any.publishCommand()).status)

      atLeast(3, tasks) shouldBe 'running

      channel.replyWith(new AMQImpl.Basic.Nack(2L, true, false))

      eventually {
        atLeast(2, tasks) shouldBe 'completed
      }

      atLeast(1, tasks) should not be 'completed

      channel.replyWith(new AMQImpl.Basic.Ack(3L, false))

      atLeast(3, tasks) shouldBe 'completed
    }
  }

  test("Cannot publish when there is a IOException") {
    val expectedMsg = "There is a network problem"
    val mockCannel = new StubChannel {
      override def basicPublish(exchange: String,
                                routingKey: String,
                                mandatory: Boolean,
                                immediate: Boolean,
                                props: AMQP.BasicProperties,
                                body: Array[Byte]): Unit =
        throw new IOException(expectedMsg)

    }
    withPublisher(None, channel = mockCannel) { publisher =>
      import publisher._
      channel.transmittedCommands should have size 1
      channel.transmittedCommands.last shouldBe an[AMQP.Confirm.Select]

      val status = publish(Any.publishCommand()).status

      eventually {
        status shouldBe 'failure
      }
      status.failure.getMessage should ===(expectedMsg)
    }
  }

  test("Publisher times out if configured to do so") {

    withPublisher(timeout = Some(10.millis)) { publisher =>
      import publisher._
      val result = publish(Any.publishCommand()).status

      eventually {
        result.failure shouldBe a[TimeoutException]
      }
    }
  }

  case class TestPublisher(channel: StubChannel, publish: Publisher[IO, PublishCommand])

  def withPublisher(timeout: Option[FiniteDuration] = None, channel: StubChannel = new StubChannel)(
      f: TestPublisher => Unit): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    val client  = IOAmqpClient(channel)
    val publish = timeout.fold(client.publisher())(client.publisher)
    f(TestPublisher(channel, publish))
  }

}
