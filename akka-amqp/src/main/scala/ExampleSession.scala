/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.amqp

import akka.serialization.Serializer
import com.rabbitmq.client.ConnectionParameters
import kernel.actor.Actor

object ExampleSession {
  def main(args: Array[String]) = {
    import AMQP._
    val CONFIG = new ConnectionParameters
    val EXCHANGE = "whitehouse.gov"
    val QUEUE = "twitter"
    val ROUTING_KEY = "@barack_obama"
    val HOSTNAME = "localhost"
    val PORT = 5672
    val SERIALIZER = Serializer.Java

    val endpoint = AMQP.newEndpoint(CONFIG, HOSTNAME, PORT, EXCHANGE, ExchangeType.Direct, SERIALIZER, None, 100)

    // register message consumer
    endpoint ! MessageConsumer(new Actor() {
      def receive: PartialFunction[Any, Unit] = {
        case Message(payload, _, _, _) => log.debug("Received message: %s", payload)
      }
    }, QUEUE, ROUTING_KEY)

    val client = AMQP.newClient(CONFIG, HOSTNAME, PORT, EXCHANGE, SERIALIZER, None, None, 100)
    client ! Message(ROUTING_KEY + " I'm going surfing", ROUTING_KEY)
    client ! Message(ROUTING_KEY + " I'm going surfing", ROUTING_KEY)
  }
}