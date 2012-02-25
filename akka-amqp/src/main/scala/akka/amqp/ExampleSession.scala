/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.lang.String
import akka.dispatch.Await
import akka.actor.{ ActorRef, Props, ActorSystem, Actor }
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.multiverse.api.latches.StandardLatch

object ExampleSession {

  val system = ActorSystem.create("ExampleSession", ConfigFactory.load.getConfig("example"))
  val amqp = system.actorOf(Props(new AMQPActor))

  val settings = Settings(system)
  implicit val timeout = Timeout(settings.Timeout)

  // defaults to amqp://guest:guest@localhost:5672/
  val connection = Await.result((amqp ? ConnectionRequest(ConnectionParameters())) mapTo manifest[ActorRef], timeout.duration)

  def main(args: Array[String]) = {

    try {
      printTopic("DIRECT")
      direct

      printTopic("FANOUT")
      fanout

      printTopic("TOPIC")
      topic

      printTopic("CALLBACK")
      callback

      printTopic("Happy hAkking :-)")

      // postStop everything the amqp tree except the main AMQP system
      // all connections/consumers/producers will be stopped
    } catch {
      case e: Exception ⇒ e.printStackTrace()
    } finally {
      //system.shutdown
      //System.exit(0)
    }
  }

  def printTopic(topic: String) {

    println("")
    println("==== " + topic + " ===")
    println("")
  }

  def direct = {

    val msgProcessed = new CountDownLatch(1)
    val consumerStartedLatch = new StandardLatch
    val producerStartedLatch = new StandardLatch

    val consumerChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(consumerStartedLatch)))
    val producerChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(producerStartedLatch)))

    val exchangeParameters = ExchangeParameters("my_direct_exchange", Direct)
    Await.result((connection ? ConsumerRequest(
      ConsumerParameters("some.routing", system.actorOf(Props(new Actor {
        def receive = {
          case Delivery(payload, _, _, _, _, _) ⇒ {
            println("@george_bush received message from: %s".format(new String(payload)))
            msgProcessed.countDown
          }
        }
      })), None, Some(exchangeParameters), channelParameters = Some(consumerChannelParameters)))) mapTo manifest[ActorRef], timeout.duration)

    val producer = Await.result((connection ? ProducerRequest(
      ProducerParameters(Some(exchangeParameters), channelParameters = Some(producerChannelParameters)))) mapTo manifest[ActorRef], timeout.duration)

    consumerStartedLatch.tryAwait(timeout.duration.toMillis, TimeUnit.MILLISECONDS)
    producerStartedLatch.tryAwait(timeout.duration.toMillis, TimeUnit.MILLISECONDS)

    producer ! Message("@jonas_boner: You sucked!!".getBytes, "some.routing")

    // just to make sure we receive before moving to next test
    if (!msgProcessed.await(timeout.duration.toMillis, TimeUnit.SECONDS)) throw new Exception("didn't get all messages.")
  }

  def fanout = {

    val msgProcessed = new CountDownLatch(2)
    val consumer1StartedLatch = new StandardLatch
    val consumer2StartedLatch = new StandardLatch
    val producerStartedLatch = new StandardLatch

    val consumer1ChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(consumer1StartedLatch)))
    val consumer2ChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(consumer2StartedLatch)))
    val producerChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(producerStartedLatch)))

    val exchangeParameters = ExchangeParameters("my_fanout_exchange", Fanout)

    Await.result((connection ? ConsumerRequest(
      ConsumerParameters("@george_bush", system.actorOf(Props(new Actor {
        def receive = {
          case Delivery(payload, _, _, _, _, _) ⇒ {
            println("@george_bush received message from: %s".format(new String(payload)))
            msgProcessed.countDown()
          }
        }
      })), None, Some(exchangeParameters), channelParameters = Some(consumer1ChannelParameters))))
      mapTo manifest[ActorRef], timeout.duration)

    Await.result((connection ? ConsumerRequest(
      ConsumerParameters("@barack_obama", system.actorOf(Props(new Actor {
        def receive = {
          case Delivery(payload, _, _, _, _, _) ⇒ {
            println("@barack_obama received message from: %s".format(new String(payload)))
            msgProcessed.countDown()
          }
        }
      })), None, Some(exchangeParameters), channelParameters = Some(consumer2ChannelParameters))))
      mapTo manifest[ActorRef], timeout.duration)

    val producer = Await.result((connection ? ProducerRequest(
      ProducerParameters(Some(exchangeParameters), channelParameters = Some(producerChannelParameters))))
      mapTo manifest[ActorRef], timeout.duration)

    consumer1StartedLatch.tryAwait(timeout.duration.toMillis, TimeUnit.MILLISECONDS)
    consumer2StartedLatch.tryAwait(timeout.duration.toMillis, TimeUnit.MILLISECONDS)
    producerStartedLatch.tryAwait(timeout.duration.toMillis, TimeUnit.MILLISECONDS)

    producer ! Message("@jonas_boner: I'm going surfing".getBytes, "")
    if (!msgProcessed.await(5, TimeUnit.SECONDS)) throw new Exception("didn't get all messages.")
  }

  def topic = {

    val msgProcessed = new CountDownLatch(2)
    val consumer1StartedLatch = new StandardLatch
    val consumer2StartedLatch = new StandardLatch
    val producerStartedLatch = new StandardLatch

    val consumer1ChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(consumer1StartedLatch)))
    val consumer2ChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(consumer2StartedLatch)))
    val producerChannelParameters = ChannelParameters(channelCallback = Some(channelCallback(producerStartedLatch)))

    val exchangeParameters = ExchangeParameters("my_topic_exchange", Topic)

    Await.result((connection ? ConsumerRequest(
      ConsumerParameters("@george_bush", system.actorOf(Props(new Actor {
        def receive = {
          case Delivery(payload, _, _, _, _, _) ⇒ {
            println("@george_bush received message from: %s".format(new String(payload)))
            msgProcessed.countDown()
          }
        }
      })), None, Some(exchangeParameters), channelParameters = Some(consumer1ChannelParameters))))
      mapTo manifest[ActorRef], timeout.duration)

    Await.result((connection ? ConsumerRequest(
      ConsumerParameters("@barack_obama", system.actorOf(Props(new Actor {
        def receive = {
          case Delivery(payload, _, _, _, _, _) ⇒ {
            println("@barack_obama received message from: %s".format(new String(payload)))
            msgProcessed.countDown()
          }
        }
      })), None, Some(exchangeParameters), channelParameters = Some(consumer2ChannelParameters))))
      mapTo manifest[ActorRef], timeout.duration)

    val producer = Await.result((connection ? ProducerRequest(
      ProducerParameters(Some(exchangeParameters), channelParameters = Some(producerChannelParameters))))
      mapTo manifest[ActorRef], timeout.duration)

    consumer1StartedLatch.tryAwait(timeout.duration.toMillis, TimeUnit.MILLISECONDS)
    consumer2StartedLatch.tryAwait(timeout.duration.toMillis, TimeUnit.MILLISECONDS)
    producerStartedLatch.tryAwait(timeout.duration.toMillis, TimeUnit.MILLISECONDS)

    producer ! Message("@jonas_boner: You still suck!!".getBytes, "@george_bush")
    producer ! Message("@jonas_boner: Yes I can!".getBytes, "@barack_obama")
    if (!msgProcessed.await(5, TimeUnit.SECONDS)) throw new Exception("didn't get all messages.")
  }

  def callback = {

    val channelCountdown = new CountDownLatch(2)
    val connectedLatch = new StandardLatch

    val connectionCallback = system.actorOf(Props(new Actor {
      def receive = {
        case Connected ⇒ {
          println("Connection callback: Connected!")
          connectedLatch.open
        }
        case Reconnecting ⇒ () // not used, sent when connection fails and initiates a reconnect
        case Disconnected ⇒ println("Connection callback: Disconnected!")
      }
    }))

    val callbackConnection = Await.result((amqp ? ConnectionRequest(
      ConnectionParameters(connectionCallback = Some(connectionCallback)))) mapTo manifest[ActorRef], timeout.duration)

    val channelCallback = system.actorOf(Props(new Actor {
      def receive = {
        case Started ⇒ {
          println("Channel callback: Started")
          channelCountdown.countDown
        }
        case Restarting ⇒ // not used, sent when channel or connection fails and initiates a restart
        case Stopped    ⇒ println("Channel callback: Stopped")
      }
    }))

    val exchangeParameters = ExchangeParameters("my_callback_exchange", Direct)
    val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

    connectedLatch.tryAwait(timeout.duration.toMillis, TimeUnit.MILLISECONDS)

    Await.result((callbackConnection ? ConsumerRequest(
      ConsumerParameters("callback.routing", system.actorOf(Props(new Actor {
        def receive = {
          case _ ⇒ () // not used
        }
      })), None, Some(exchangeParameters), channelParameters = Some(channelParameters))))
      mapTo manifest[ActorRef], timeout.duration)

    Await.result((callbackConnection ? ProducerRequest(
      ProducerParameters(Some(exchangeParameters), channelParameters = Some(channelParameters))))
      mapTo manifest[ActorRef], timeout.duration)

    // Wait until both channels (producer & consumer) are started before stopping the connection
    if (!channelCountdown.await(5, TimeUnit.SECONDS)) throw new Exception("didn't start all channels.")
  }

  /**
   * use this to confirm that the consumer/producer channels have been started prior to sending messages
   */

  def channelCallback(startedLatch: StandardLatch) = system.actorOf(Props(new Actor {
    def receive = {
      case Started ⇒ startedLatch.open
      case _       ⇒ ()
    }
  }))
}
