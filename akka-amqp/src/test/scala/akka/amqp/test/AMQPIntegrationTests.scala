package akka.amqp.test

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

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
import akka.util.duration._
import com.eaio.uuid.UUID

class AMQPIntegrationTests(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpec with MustMatchers with BeforeAndAfterAll {

  def this() = this(ActorSystem.create("AMQPIntegrationTests", ConfigFactory.load.getConfig("testing")))

  val settings = AMQP(system)
  implicit val timeout = Timeout(settings.Timeout)
  implicit val log = Logging(system, self)
  val utf8Charset = Charset.forName("UTF-8")

  override def afterAll {
    system.scheduler.scheduleOnce(10 seconds)(system.shutdown())
  }

  "An AMQP connection must" must {

    "recover from a connection failure" in {
      val latches = ConnectionLatches()

      // second address is default local rabbitmq instance, tests multiple address connection
      val localAddresses = Seq(new Address("localhost", 9999), new Address("localhost", 5672))
      /*val conn = AMQP.getConnection(ConnectionParameters(addresses = localAddresses,
        initReconnectDelay = Option(50 milliseconds),
        connectionCallback = Some(system.actorOf(Props(new ConnectionCallbackActor(latches))))))*/

      val connection = system.actorOf(Props(new FaultTolerantConnectionActor(ConnectionParameters(addresses = localAddresses,
        initReconnectDelay = Option(50 milliseconds),
        connectionCallback = Some(system.actorOf(Props(new ConnectionCallbackActor(latches))))))), "conn-" + (new UUID()).toString)

      Await result (latches.connected, timeout.duration)

      connection ! new ConnectionShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef"))

      Await result (latches.reconnected, timeout.duration)

      connection ! PoisonPill

      Await result (latches.disconnected, timeout.duration)
      latches.connected.isOpen must be(true)
      latches.reconnecting.isOpen must be(true)
      latches.reconnected.isOpen must be(true)
      latches.disconnected.isOpen must be(true)
    }

    "cleanly shut down a connection when requested" in {
      val latches = ConnectionLatches()

      // second address is default local rabbitmq instance, tests multiple address connection
      val localAddresses = Seq(new Address("localhost", 9999), new Address("localhost", 5672))
      val connection = system.actorOf(Props(new FaultTolerantConnectionActor(ConnectionParameters(addresses = localAddresses,
        initReconnectDelay = Option(50 milliseconds),
        connectionCallback = Some(system.actorOf(Props(new ConnectionCallbackActor(latches))))))), "conn-" + (new UUID()).toString)

      Await result (latches.connected, timeout.duration)

      connection ! PoisonPill

      Await result (latches.disconnected, timeout.duration)

      connection.isTerminated must be(true)
    }

    "send a message from a producer to a consumer" in {

      val localAddress = Seq(new Address("localhost", 5672))

      val connection = system.actorOf(Props(new FaultTolerantConnectionActor(ConnectionParameters(addresses = localAddress))),
        "conn-" + (new UUID()).toString)

      val exchangeParameters = ExchangeParameters("text_exchange")

      val cf: Future[ActorRef] = connection ? ConsumerRequest(
        ConsumerParameters("non.interesting.routing.key", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, routingKey, _, _, _, _) ⇒ testActor forward Message(payload, routingKey)
          }
        })), exchangeParameters = Some(exchangeParameters))) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = connection ? ProducerRequest(
        ProducerParameters(Some(exchangeParameters))) mapTo manifest[ActorRef]

      for (consumer ← cf; producer ← pf)
        producer ! Message("some_payload".getBytes(utf8Charset).toSeq, "non.interesting.routing.key")

      expectMsgClass(timeout.duration, classOf[Message])

      connection ! PoisonPill
    }

    "recover from a consumer channel failure" in {

      val consumerLatches = ChannelLatches()
      val producerLatches = ChannelLatches()

      val localAddress = Seq(new Address("localhost", 5672))

      val connection = system.actorOf(Props(new FaultTolerantConnectionActor(ConnectionParameters(addresses = localAddress))),
        "conn-" + (new UUID()).toString)

      val exchangeParameters = ExchangeParameters("text_exchange")

      val consumerChannelParameters = ChannelParameters(channelCallback =
        Some(system.actorOf(Props(new ChannelCallbackActor(consumerLatches)))))

      val producerChannelParameters = ChannelParameters(channelCallback =
        Some(system.actorOf(Props(new ChannelCallbackActor(producerLatches)))))

      val cf: Future[ActorRef] = connection ? ConsumerRequest(
        ConsumerParameters("non.interesting.routing.key", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, routingKey, _, _, _, _) ⇒ {
              log.debug("***** consumer got a delivery")
              testActor forward Message(payload, routingKey)
            }
          }
        })), exchangeParameters = Some(exchangeParameters),
          channelParameters = Some(consumerChannelParameters))) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = connection ? ProducerRequest(ProducerParameters(Some(exchangeParameters),
        channelParameters = Some(producerChannelParameters))) mapTo manifest[ActorRef]

      for (consumer ← cf; producer ← pf) {
        // send a test message before killing the consumer channel
        producer ! Message("some_payload".getBytes(utf8Charset).toSeq, "non.interesting.routing.key")

        consumer ! new ChannelShutdown(new ShutdownSignalException(false, false, "TestException", "TestRef"))

        Await.result(consumerLatches.restarted, timeout.duration)
        consumerLatches.restarted.isOpen must be(true)

        producer ! Message("some_payload".getBytes(utf8Charset).toSeq, "non.interesting.routing.key")
      }

      expectMsgClass(timeout.duration, classOf[Message])
      expectMsgClass(timeout.duration, classOf[Message])

      connection ! PoisonPill
    }

    "recover from a producer channel failure" in {

      val consumerLatches = ChannelLatches()
      val producerLatches = ChannelLatches()

      val localAddress = Seq(new Address("localhost", 5672))

      val connection = system.actorOf(Props(new FaultTolerantConnectionActor(ConnectionParameters(addresses = localAddress))),
        "conn-" + (new UUID()).toString)

      val exchangeParameters = ExchangeParameters("text_exchange")

      val consumerChannelParameters = ChannelParameters(channelCallback =
        Some(system.actorOf(Props(new ChannelCallbackActor(consumerLatches)))))

      val producerChannelParameters = ChannelParameters(channelCallback =
        Some(system.actorOf(Props(new ChannelCallbackActor(producerLatches)))))

      val cf: Future[ActorRef] = connection ? ConsumerRequest(
        ConsumerParameters("non.interesting.routing.key", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, routingKey, _, _, _, _) ⇒ {
              log.debug("***** consumer got a delivery")
              testActor forward Message(payload, routingKey)
            }
          }
        })), exchangeParameters = Some(exchangeParameters),
          channelParameters = Some(consumerChannelParameters))) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = connection ? ProducerRequest(ProducerParameters(Some(exchangeParameters),
        channelParameters = Some(producerChannelParameters))) mapTo manifest[ActorRef]

      for (consumer ← cf; producer ← pf) {
        // send a test message before killing the consumer channel
        producer ! Message("some_payload".getBytes(utf8Charset).toSeq, "non.interesting.routing.key")

        system.scheduler.scheduleOnce(timeout.duration / 4, producer, new ChannelShutdown(new ShutdownSignalException(false, false, "TestException", "TestRef")))

        Await.result(producerLatches.restarted, timeout.duration)
        producerLatches.restarted.isOpen must be(true)

        producer ! Message("some_payload".getBytes(utf8Charset).toSeq, "non.interesting.routing.key")
      }

      expectMsgClass(timeout.duration, classOf[Message])
      expectMsgClass(timeout.duration, classOf[Message])

      connection ! PoisonPill
    }

    "resume consumers and producers after a connection recovery" in {

      val connectionLatches = ConnectionLatches()
      val consumerLatches = ChannelLatches()
      val producerLatches = ChannelLatches()

      val localAddress = Seq(new Address("localhost", 5672))

      val connection = system.actorOf(Props(new FaultTolerantConnectionActor(ConnectionParameters(addresses = localAddress,
        connectionCallback = Some(system.actorOf(Props(new ConnectionCallbackActor(connectionLatches))))))),
        "conn-" + (new UUID()).toString)

      val exchangeParameters = ExchangeParameters("text_exchange")

      val consumerChannelParameters = ChannelParameters(channelCallback =
        Some(system.actorOf(Props(new ChannelCallbackActor(consumerLatches)))))

      val producerChannelParameters = ChannelParameters(channelCallback =
        Some(system.actorOf(Props(new ChannelCallbackActor(producerLatches)))))

      val cf: Future[ActorRef] = connection ? ConsumerRequest(
        ConsumerParameters("non.interesting.routing.key", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, routingKey, _, _, _, _) ⇒ {
              log.debug("***** consumer got a delivery")
              testActor forward Message(payload, routingKey)
            }
          }
        })), exchangeParameters = Some(exchangeParameters),
          channelParameters = Some(consumerChannelParameters))) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = connection ? ProducerRequest(ProducerParameters(Some(exchangeParameters),
        channelParameters = Some(producerChannelParameters))) mapTo manifest[ActorRef]

      for (consumer ← cf; producer ← pf) {
        // send a test message before killing the consumer channel
        producer ! Message("some_payload".getBytes(utf8Charset).toSeq, "non.interesting.routing.key")

        system.scheduler.scheduleOnce(timeout.duration / 4, connection, new ConnectionShutdown(
          new ShutdownSignalException(true, false, "TestException", "TestRef")))

        Await.result(connectionLatches.reconnected, timeout.duration)
        Await.result(consumerLatches.restarted, timeout.duration)
        Await.result(producerLatches.restarted, timeout.duration)
        connectionLatches.reconnected.isOpen must be(true)
        consumerLatches.restarted.isOpen must be(true)
        producerLatches.restarted.isOpen must be(true)

        producer ! Message("some_payload".getBytes(utf8Charset).toSeq, "non.interesting.routing.key")
      }

      expectMsgClass(timeout.duration, classOf[Message])
      expectMsgClass(timeout.duration, classOf[Message])

      connection ! PoisonPill
    }

    "support consumer manual ack of messages" in {

      var deliveryTagCheck: Long = -1

      val localAddress = Seq(new Address("localhost", 5672))

      val connection = system.actorOf(Props(new FaultTolerantConnectionActor(ConnectionParameters(addresses = localAddress))),
        "conn-" + (new UUID()).toString)

      val exchangeParameters = ExchangeParameters("text_exchange")

      val cf: Future[ActorRef] = connection ? ConsumerRequest(
        ConsumerParameters("manual.ack.this", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, deliveryTag, _, _, sender) ⇒ {
              deliveryTagCheck = deliveryTag
              sender.foreach(_ ! Acknowledge(deliveryTag))
            }
            case Acknowledged(deliveryTag) ⇒ {
              if (deliveryTagCheck == deliveryTag) {
                log.debug("***** Acknowledging message")
                testActor forward Acknowledged(deliveryTag)
              }
            }
            case _ ⇒ ()
          }
        })), queueName = Some("self.ack.queue"), exchangeParameters = Some(exchangeParameters),
          selfAcknowledging = false,
          queueDeclaration = ActiveDeclaration(autoDelete = false))) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = connection ? ProducerRequest(ProducerParameters(Some(exchangeParameters))) mapTo manifest[ActorRef]

      for (consumer ← cf; producer ← pf)
        producer ! Message("some_payload".getBytes(utf8Charset).toSeq, "manual.ack.this")

      expectMsgClass(timeout.duration, classOf[Acknowledged])

      connection ! PoisonPill
    }

    "support consumer manual reject of messages" in {

      val localAddress = Seq(new Address("localhost", 5672))

      val connection = system.actorOf(Props(new FaultTolerantConnectionActor(ConnectionParameters(addresses = localAddress))),
        "conn-" + (new UUID()).toString)

      val exchangeParameters = ExchangeParameters("text_exchange")

      val cf: Future[ActorRef] = connection ? ConsumerRequest(
        ConsumerParameters("manual.reject.this", system.actorOf(Props(new Actor {
          def receive = {
            case Delivery(payload, _, deliveryTag, _, _, sender) ⇒ sender.foreach(_ ! Reject(deliveryTag))
            case msg: Rejected ⇒ {
              log.debug("***** Rejecting message")
              testActor forward msg
            }
          }
        })), queueName = Some("self.reject.queue"), exchangeParameters = Some(exchangeParameters),
          selfAcknowledging = false)) mapTo manifest[ActorRef]

      val pf: Future[ActorRef] = connection ? ProducerRequest(ProducerParameters(Some(exchangeParameters))) mapTo manifest[ActorRef]

      for (consumer ← cf; producer ← pf)
        producer ! Message("some_payload".getBytes(utf8Charset).toSeq, "manual.reject.this")

      expectMsgClass(timeout.duration, classOf[Rejected])

      connection ! PoisonPill
    }

    "support receiving messages on a producer return listener" in {

      val returnLatch = new TestLatch

      val localAddress = Seq(new Address("localhost", 5672))

      val connection = system.actorOf(Props(new FaultTolerantConnectionActor(ConnectionParameters(addresses = localAddress))),
        "conn-" + (new UUID()).toString)

      val exchangeParameters = ExchangeParameters("text_exchange")

      val returnListener = new ReturnListener {
        def handleReturn(replyCode: Int, replyText: String, exchange: String, routingKey: String, properties: BasicProperties, body: Array[Byte]) = {
          log.debug("***** Return received")
          returnLatch.open
        }
      }

      val producerParameters = ProducerParameters(
        Some(exchangeParameters), returnListener = Some(returnListener))

      val pf: Future[ActorRef] = connection ? ProducerRequest(producerParameters) mapTo manifest[ActorRef]

      for (producer ← pf)
        producer ! new Message("some_payload".getBytes(utf8Charset).toSeq, "non.interesing.routing.key", mandatory = true)

      Await.result(returnLatch, timeout.duration)
      returnLatch.isOpen must be(true)

      connection ! PoisonPill
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

