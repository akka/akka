package akka.amqp

import com.rabbitmq.client._
import akka.actor.ActorSystem
import akka.dispatch.Await
import akka.testkit.{ AkkaSpec, TestLatch }
import akka.util.duration._

class PublisherSpec extends AkkaSpec {

  trait PublisherScope {
    def exchange: Exchange

    val system = ActorSystem("amqp")
    val durableConnection = new DurableConnection(ConnectionProperties(system))
    val publisher = durableConnection.newPublisher(exchange)
    publisher.awaitStart()

    def after() {
      publisher.stop()
      durableConnection.dispose()
    }
  }

  "Durable Publisher" should {

    implicit val system = ActorSystem("DurablePublisher")

    "publish message on default exchange" in new PublisherScope {
      def exchange = DefaultExchange

      try {
        val future = publisher.publish(Message("test".getBytes, "1.2.3"))
        Await.ready(future, 5 seconds).value must be === Some(Right(()))
      } finally { after() }
    }
    "kill channel when publishing on non existing exchange" in new PublisherScope {
      def exchange = UnmanagedExchange("does-not-exist")

      try {
        implicit val system = ActorSystem("amqp")
        val latch = TestLatch()
        publisher.onAvailable { channel ⇒
          channel.addShutdownListener(new ShutdownListener {
            def shutdownCompleted(cause: ShutdownSignalException) {
              latch.open()
            }
          })
        }
        publisher.publish(Message("test".getBytes, "1.2.3"))
        Await.ready(latch, 5 seconds).isOpen must be === true
      } finally { after() }
    }
    "get message returned when sending with immediate flag" in new PublisherScope {
      def exchange = DefaultExchange

      try {
        implicit val system = ActorSystem("amqp")
        val latch = TestLatch()
        publisher.onReturn { returnedMessage ⇒
          latch.open()
        }
        publisher.publish(Message("test".getBytes, "1.2.3", immediate = true))
        Await.ready(latch, 5 seconds).isOpen must be === true
      } finally { after() }
    }
    "get message returned when sending with mandatory flag" in new PublisherScope {
      def exchange = DefaultExchange
      try {
        implicit val system = ActorSystem("amqp")
        val latch = TestLatch()
        publisher.onReturn { returnedMessage ⇒
          latch.open()
        }
        publisher.publish(Message("test".getBytes, "1.2.3", mandatory = true))
        Await.ready(latch, 5 seconds).isOpen must be === true
      } finally { after() }
    }
    "get message publishing acknowledged when using confirming publiser" in {

      val system = ActorSystem("amqp")
      val durableConnection = new DurableConnection(ConnectionProperties(system))
      val confirmingPublisher = durableConnection.newConfirmingPublisher(DefaultExchange)
      try {
        confirmingPublisher.awaitStart()
        val future = confirmingPublisher.publishConfirmed(Message("test".getBytes, "1.2.3"))
        Await.ready(future, 5 seconds).value must be === Some(Right(Ack))
      } finally {
        confirmingPublisher.stop()
        durableConnection.dispose()
      }
    }
  }
}
