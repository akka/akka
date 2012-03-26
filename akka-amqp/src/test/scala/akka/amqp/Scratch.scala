package akka.amqp

import java.util.concurrent.CountDownLatch
import akka.actor.{ ActorSystem, Actor, Props }

object Scratch extends App {

  val system = ActorSystem("amqp")
  val connection = new DurableConnection(ConnectionProperties(system))

  val nrOfMessages = 2000
  val latch = new CountDownLatch(nrOfMessages)

  val deliveryHandler = connection.connectionProperties.system.actorOf(Props(new Actor {
    protected def receive = {
      case delivery @ Delivery(payload, routingKey, deliveryTag, isRedeliver, properties, sender) ⇒
        println("D: " + new String(payload))

        delivery.acknowledge()
        latch.countDown()
    }
  }))
  val consumer = connection.newConsumer(ActiveQueue("test"), deliveryHandler, false)
  consumer.awaitStart()

  val publisher = connection.newStashingPublisher(DefaultExchange)

  for (i ← 1 to nrOfMessages) {
    publisher.publish(Message("Message[%s]".format(i).getBytes, "test"))
  }

  latch.await()
  connection.dispose()
}