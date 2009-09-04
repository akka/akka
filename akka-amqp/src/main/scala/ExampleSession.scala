/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.amqp

import akka.serialization.Serializer
import com.rabbitmq.client.ConnectionParameters
import actor.Actor

object ExampleSession {
  import AMQP._
  val SERIALIZER = Serializer.Java
  val CONFIG = new ConnectionParameters
  val HOSTNAME = "localhost"
  val PORT = 5672

  val IM = "im.whitehouse.gov"
  val CHAT = "chat.whitehouse.gov"

  def main(args: Array[String]) = {
    println("==== DIRECT ===")
    direct

    Thread.sleep(1000)
    
    println("==== FANOUT ===")
    fanout
  }

  def direct = {
    val endpoint = AMQP.newEndpoint(CONFIG, HOSTNAME, PORT, IM, ExchangeType.Direct, SERIALIZER, None, 100)
    endpoint ! MessageConsumer("@george_bush", "direct", new Actor() {
      def receive: PartialFunction[Any, Unit] = {
        case Message(payload, _, _, _, _) => log.info("@george_bush received message from: %s", payload)
      }
    })
    val client = AMQP.newClient(CONFIG, HOSTNAME, PORT, IM, SERIALIZER, None, None, 100)
    client ! Message("@jonas_boner: You sucked!!", "direct")
  }

  def fanout = {
    val endpoint = AMQP.newEndpoint(CONFIG, HOSTNAME, PORT, CHAT, ExchangeType.Fanout, SERIALIZER, None, 100)
    endpoint ! MessageConsumer("@george_bush", "", new Actor() {
      def receive: PartialFunction[Any, Unit] = {
        case Message(payload, _, _, _, _) => log.info("@george_bush received message from: %s", payload)
      }
    })
    endpoint ! MessageConsumer("@barack_obama", "", new Actor() {
      def receive: PartialFunction[Any, Unit] = {
        case Message(payload, _, _, _, _) => log.info("@barack_obama received message from: %s", payload)
      }
    })
    val client = AMQP.newClient(CONFIG, HOSTNAME, PORT, CHAT, SERIALIZER, None, None, 100)
    client ! Message("@jonas_boner: I'm going surfing", "")
  }
}