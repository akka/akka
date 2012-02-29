/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp.test

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll
import akka.testkit.{ TestLatch, TestKit, ImplicitSender }
import akka.amqp._
import akka.pattern.ask
import akka.actor._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import akka.dispatch.{ Future, Await }
import akka.event.Logging
import com.rabbitmq.client.{ ReturnListener, ShutdownSignalException, Address }
import com.rabbitmq.client.AMQP.BasicProperties
import java.nio.charset.Charset

class AmqpIntegrationTests(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpec with MustMatchers with BeforeAndAfterAll {

  //implicit val system = ActorSystem.create("ExampleSession", ConfigFactory.load.getConfig("example"))
  def this() = this(ActorSystem.create("ExampleSession", ConfigFactory.load.getConfig("testing")))

  val amqp = system.actorOf(Props[AMQPActor])
  val settings = Settings(system)
  implicit val timeout = Timeout(settings.Timeout)
  implicit val log = Logging(system, self)
  val utf8Charset = Charset.forName("UTF-8")

  override def afterAll {
    system.shutdown()
  }

  "An AMQP connection must" must {

    "recover from a connection failure" in {
      val latches = ConnectionLatches()

      // second address is default local rabbitmq instance, tests multiple address connection
      val localAddresses = Array(new Address("localhost", 9999), new Address("localhost", 5672))
      val connection = (amqp ? ConnectionRequest(ConnectionParameters(addresses = localAddresses,
        initReconnectDelay = 50,
        connectionCallback = Some(system.actorOf(Props(new ConnectionCallbackActor(latches))))))) mapTo manifest[ActorRef]

      try {
        Await result (latches.connected, timeout.duration)
        connection map (_ ! new ConnectionShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef")))

        Await result (latches.reconnecting, timeout.duration)
        Await result (latches.reconnected, timeout.duration)
      } finally {
        connection map (_ ! PoisonPill)
        Await result (latches.disconnected, timeout.duration)
        latches.reconnecting.isOpen must be(true)
        latches.reconnected.isOpen must be(true)
        latches.disconnected.isOpen must be(true)
      }
    }

    "cleanly shut down a connection when requested" in {
      val latches = ConnectionLatches()

      // second address is default local rabbitmq instance, tests multiple address connection
      val localAddresses = Array(new Address("localhost", 9999), new Address("localhost", 5672))
      val connection = (amqp ? ConnectionRequest(ConnectionParameters(addresses = localAddresses,
        initReconnectDelay = 50,
        connectionCallback = Some(system.actorOf(Props(new ConnectionCallbackActor(latches))))))) mapTo manifest[ActorRef]

      Await result (latches.connected, timeout.duration)
      connection map (_ ! PoisonPill)
      Await result (latches.disconnected, timeout.duration)
      connection map (_.isTerminated must be(true))
    }

    "recover when a consumer channel fails" in {
      val connectionLatches = ConnectionLatches()
      val consumerLatches = ChannelLatches()
      val producerLatches = ChannelLatches()
      val payloadLatch = new TestLatch

      val localAddress = Array(new Address("localhost", 5672))

      val connection = (amqp ? ConnectionRequest(ConnectionParameters(addresses = localAddress,
        connectionCallback =
          Some(system.actorOf(Props(new ConnectionCallbackActor(connectionLatches))))))) mapTo manifest[ActorRef]

      try {
        val exchangeParameters = ExchangeParameters("text_exchange")

        val consumerChannelParameters = ChannelParameters(channelCallback =
          Some(system.actorOf(Props(new ChannelCallbackActor(consumerLatches)))))

        val producerChannelParameters = ChannelParameters(channelCallback =
          Some(system.actorOf(Props(new ChannelCallbackActor(producerLatches)))))

        val consumer: Future[ActorRef] = for {
          conn ← connection
          cr ← (conn ? ConsumerRequest(
            ConsumerParameters("non.interesting.routing.key", system.actorOf(Props(new Actor {
              def receive = {
                case Delivery(payload, _, _, _, _, _) ⇒ payloadLatch.open
              }
            })), exchangeParameters = Some(exchangeParameters),
              channelParameters = Some(consumerChannelParameters)))) mapTo manifest[ActorRef]
        } yield cr

        val producer = for {
          conn ← connection
          pr ← (conn ? ProducerRequest(ProducerParameters(Some(exchangeParameters),
            channelParameters = Some(producerChannelParameters)))) mapTo manifest[ActorRef]
        } yield pr

        Await result (consumerLatches.started, timeout.duration)
        Await result (producerLatches.started, timeout.duration)

        consumer map (_ ! new ChannelShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef")))

        Await.result(consumerLatches.restarted, timeout.duration)

        producer map (_ ! Message("some_payload".getBytes(utf8Charset).toSeq, "non.interesting.routing.key"))

        Await.result(payloadLatch, timeout.duration)
      } finally {
        payloadLatch.isOpen must be(true)
        connection map (_ ! PoisonPill)
      }
    }

    "recover when a producer channel fails" in {
      val connectionLatches = ConnectionLatches()
      val consumerLatches = ChannelLatches()
      val producerLatches = ChannelLatches()
      val payloadLatch = new TestLatch

      val localAddress = Array(new Address("localhost", 5672))

      val connection = (amqp ? ConnectionRequest(ConnectionParameters(addresses = localAddress,
        connectionCallback =
          Some(system.actorOf(Props(new ConnectionCallbackActor(connectionLatches))))))) mapTo manifest[ActorRef]

      try {
        val exchangeParameters = ExchangeParameters("text_exchange")

        val consumerChannelParameters = ChannelParameters(channelCallback =
          Some(system.actorOf(Props(new ChannelCallbackActor(consumerLatches)))))

        val producerChannelParameters = ChannelParameters(channelCallback =
          Some(system.actorOf(Props(new ChannelCallbackActor(producerLatches)))))

        val consumer: Future[ActorRef] = for {
          conn ← connection
          cr ← (conn ? ConsumerRequest(
            ConsumerParameters("non.interesting.routing.key", system.actorOf(Props(new Actor {
              def receive = {
                case Delivery(payload, _, _, _, _, _) ⇒ payloadLatch.open
              }
            })), exchangeParameters = Some(exchangeParameters),
              channelParameters = Some(consumerChannelParameters)))) mapTo manifest[ActorRef]
        } yield cr

        val producer = for {
          conn ← connection
          pr ← (conn ? ProducerRequest(ProducerParameters(Some(exchangeParameters),
            channelParameters = Some(producerChannelParameters)))) mapTo manifest[ActorRef]
        } yield pr

        Await result (consumerLatches.started, timeout.duration)
        Await result (producerLatches.started, timeout.duration)

        producer map (_ ! new ChannelShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef")))

        Await.result(producerLatches.restarted, timeout.duration)

        producer map (_ ! Message("some_payload".getBytes(utf8Charset).toSeq, "non.interesting.routing.key"))

        Await.result(payloadLatch, timeout.duration)
      } finally {
        payloadLatch.isOpen must be(true)
        connection map (_ ! PoisonPill)
      }
    }

    "resume consumers and producers after a connection recovery" in {
      val connectionLatches = ConnectionLatches()
      val consumerLatches = ChannelLatches()
      val producerLatches = ChannelLatches()
      val payloadLatch = new TestLatch

      val localAddress = Array(new Address("localhost", 5672))

      val connection = (amqp ? ConnectionRequest(ConnectionParameters(addresses = localAddress,
        connectionCallback =
          Some(system.actorOf(Props(new ConnectionCallbackActor(connectionLatches))))))) mapTo manifest[ActorRef]

      try {
        val exchangeParameters = ExchangeParameters("text_exchange")

        val consumerChannelParameters = ChannelParameters(channelCallback =
          Some(system.actorOf(Props(new ChannelCallbackActor(consumerLatches)))))

        val producerChannelParameters = ChannelParameters(channelCallback =
          Some(system.actorOf(Props(new ChannelCallbackActor(producerLatches)))))

        val consumer: Future[ActorRef] = for {
          conn ← connection
          cr ← (conn ? ConsumerRequest(
            ConsumerParameters("non.interesting.routing.key", system.actorOf(Props(new Actor {
              def receive = {
                case Delivery(payload, _, _, _, _, _) ⇒ payloadLatch.open
              }
            })), exchangeParameters = Some(exchangeParameters),
              channelParameters = Some(consumerChannelParameters)))) mapTo manifest[ActorRef]
        } yield cr

        val producer = for {
          conn ← connection
          pr ← (conn ? ProducerRequest(ProducerParameters(Some(exchangeParameters),
            channelParameters = Some(producerChannelParameters)))) mapTo manifest[ActorRef]
        } yield pr

        Await result (consumerLatches.started, timeout.duration)
        Await result (producerLatches.started, timeout.duration)

        connection map (_ ! new ConnectionShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef")))

        Await.result(consumerLatches.restarted, timeout.duration)
        Await.result(producerLatches.restarted, timeout.duration)

        producer map (_ ! Message("some_payload".getBytes(utf8Charset).toSeq, "non.interesting.routing.key"))

        Await.result(payloadLatch, timeout.duration)
      } finally {
        payloadLatch.isOpen must be(true)
        connection map (_ ! PoisonPill)
      }
    }

    "support consumer manual ack of messages" in {
      val connectionLatches = ConnectionLatches()
      val consumerLatches = ChannelLatches()
      val producerLatches = ChannelLatches()
      val acknowledgeLatch = new TestLatch
      var deliveryTagCheck: Long = -1

      val localAddress = Array(new Address("localhost", 5672))

      val connection = (amqp ? ConnectionRequest(ConnectionParameters(addresses = localAddress,
        connectionCallback =
          Some(system.actorOf(Props(new ConnectionCallbackActor(connectionLatches))))))) mapTo manifest[ActorRef]

      try {
        val exchangeParameters = ExchangeParameters("text_exchange")

        val consumerChannelParameters = ChannelParameters(channelCallback =
          Some(system.actorOf(Props(new ChannelCallbackActor(consumerLatches)))))

        val producerChannelParameters = ChannelParameters(channelCallback =
          Some(system.actorOf(Props(new ChannelCallbackActor(producerLatches)))))

        val consumer: Future[ActorRef] = for {
          conn ← connection
          cr ← (conn ? ConsumerRequest(
            ConsumerParameters("manual.ack.this", system.actorOf(Props(new Actor {
              def receive = {
                case Delivery(payload, _, deliveryTag, _, _, sender) ⇒ {
                  deliveryTagCheck = deliveryTag
                  sender.foreach(_ ! Acknowledge(deliveryTag))
                }
                case Acknowledged(deliveryTag) ⇒ {
                  if (deliveryTagCheck == deliveryTag) acknowledgeLatch.open
                }
                case _ ⇒ ()
              }
            })), queueName = Some("self.ack.queue"), exchangeParameters = Some(exchangeParameters),
              selfAcknowledging = false, channelParameters = Some(consumerChannelParameters),
              queueDeclaration = ActiveDeclaration(autoDelete = false)))) mapTo manifest[ActorRef]
        } yield cr

        val producer = for {
          conn ← connection
          pr ← (conn ? ProducerRequest(ProducerParameters(Some(exchangeParameters),
            channelParameters = Some(producerChannelParameters)))) mapTo manifest[ActorRef]
        } yield pr

        Await result (consumerLatches.started, timeout.duration)
        Await result (producerLatches.started, timeout.duration)

        producer map (_ ! Message("some_payload".getBytes(utf8Charset).toSeq, "manual.ack.this"))

        Await.result(acknowledgeLatch, timeout.duration)

      } finally {
        acknowledgeLatch.isOpen must be(true)
        connection map (_ ! PoisonPill)
      }
    }

    "support consumer manual reject of messages" in {
      val connectionLatches = ConnectionLatches()
      val consumerLatches = ChannelLatches()
      val producerLatches = ChannelLatches()
      val rejectedLatch = new TestLatch

      val localAddress = Array(new Address("localhost", 5672))

      val connection = (amqp ? ConnectionRequest(ConnectionParameters(addresses = localAddress,
        connectionCallback =
          Some(system.actorOf(Props(new ConnectionCallbackActor(connectionLatches))))))) mapTo manifest[ActorRef]

      try {
        val exchangeParameters = ExchangeParameters("text_exchange")

        val consumerChannelParameters = ChannelParameters(channelCallback =
          Some(system.actorOf(Props(new ChannelCallbackActor(consumerLatches)))))

        val producerChannelParameters = ChannelParameters(channelCallback =
          Some(system.actorOf(Props(new ChannelCallbackActor(producerLatches)))))

        val consumer: Future[ActorRef] = for {
          conn ← connection
          cr ← (conn ? ConsumerRequest(
            ConsumerParameters("manual.reject.this", system.actorOf(Props(new Actor {
              def receive = {
                case Delivery(payload, _, deliveryTag, _, _, sender) ⇒ sender.foreach(_ ! Reject(deliveryTag))
                case Rejected(deliveryTag)                           ⇒ rejectedLatch.open
              }
            })), queueName = Some("self.reject.queue"), exchangeParameters = Some(exchangeParameters),
              selfAcknowledging = false, channelParameters = Some(consumerChannelParameters)))) mapTo manifest[ActorRef]
        } yield cr

        val producer = for {
          conn ← connection
          pr ← (conn ? ProducerRequest(ProducerParameters(Some(exchangeParameters),
            channelParameters = Some(producerChannelParameters)))) mapTo manifest[ActorRef]
        } yield pr

        Await result (consumerLatches.started, timeout.duration)
        Await result (producerLatches.started, timeout.duration)

        producer map (_ ! Message("some_payload".getBytes(utf8Charset).toSeq, "manual.reject.this"))

        Await.result(rejectedLatch, timeout.duration)

      } finally {
        rejectedLatch.isOpen must be(true)
        connection map (_ ! PoisonPill)
      }
    }

    "support receiving messages on a producer return listener" in {

      val connectionLatches = ConnectionLatches()
      val consumerLatches = ChannelLatches()
      val producerLatches = ChannelLatches()
      val returnLatch = new TestLatch

      val localAddress = Array(new Address("localhost", 5672))

      val connection = (amqp ? ConnectionRequest(ConnectionParameters(addresses = localAddress,
        connectionCallback =
          Some(system.actorOf(Props(new ConnectionCallbackActor(connectionLatches))))))) mapTo manifest[ActorRef]

      try {
        val exchangeParameters = ExchangeParameters("text_exchange")

        val producerChannelParameters = ChannelParameters(channelCallback =
          Some(system.actorOf(Props(new ChannelCallbackActor(producerLatches)))))

        val returnListener = new ReturnListener {
          def handleReturn(replyCode: Int, replyText: String, exchange: String, routingKey: String, properties: BasicProperties, body: Array[Byte]) = {
            returnLatch.open
          }
        }

        val producerParameters = ProducerParameters(
          Some(exchangeParameters), returnListener = Some(returnListener), channelParameters = Some(producerChannelParameters))

        val producer = for {
          conn ← connection
          pr ← (conn ? ProducerRequest(producerParameters)) mapTo manifest[ActorRef]
        } yield pr

        Await result (producerLatches.started, timeout.duration)

        producer map (_ ! new Message("some_payload".getBytes(utf8Charset).toSeq, "non.interesing.routing.key", mandatory = true))

        Await.result(returnLatch, timeout.duration)

      } finally {
        returnLatch.isOpen must be(true)
        connection map (_ ! PoisonPill)
      }
    }
  }

  case class ConnectionLatches(connected: TestLatch = new TestLatch, reconnecting: TestLatch = new TestLatch,
                               reconnected: TestLatch = new TestLatch, disconnected: TestLatch = new TestLatch)

  case class ChannelLatches(started: TestLatch = new TestLatch, restarting: TestLatch = new TestLatch,
                            restarted: TestLatch = new TestLatch, stopped: TestLatch = new TestLatch)

  class ConnectionCallbackActor(latches: ConnectionLatches) extends Actor {

    def receive = {
      case Connected ⇒ if (!latches.connected.isOpen) {
        latches.connected.open
      } else {
        latches.reconnected.open
      }
      case Reconnecting ⇒ latches.reconnecting.open
      case Disconnected ⇒ latches.disconnected.open
    }
  }

  class ChannelCallbackActor(latches: ChannelLatches) extends Actor {

    def receive = {
      case Started ⇒ if (!latches.started.isOpen) {
        latches.started.open
      } else {
        latches.restarted.open
      }
      case Restarting ⇒ latches.restarting.open
      case Stopped    ⇒ latches.stopped.open
    }
  }
}

