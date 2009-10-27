/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.amqp

import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.actor.Actor

import com.rabbitmq.client.ConnectionParameters

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
    val consumer = AMQP.newConsumer(CONFIG, HOSTNAME, PORT, IM, ExchangeType.Direct, SERIALIZER, None, 100, false, false, Map[String, AnyRef]())
    consumer ! MessageConsumerListener("@george_bush", "direct", false, new Actor() {
      def receive: PartialFunction[Any, Unit] = {
        case Message(payload, _, _, _, _) => log.info("@george_bush received message from: %s", payload)
      }
    })
    val producer = AMQP.newProducer(CONFIG, HOSTNAME, PORT, IM, SERIALIZER, None, None, 100)
    producer ! Message("@jonas_boner: You sucked!!", "direct")
  }

  def fanout = {
    val consumer = AMQP.newConsumer(CONFIG, HOSTNAME, PORT, CHAT, ExchangeType.Fanout, SERIALIZER, None, 100, false, false, Map[String, AnyRef]())
    consumer ! MessageConsumerListener("@george_bush", "", false, new Actor() {
      def receive: PartialFunction[Any, Unit] = {
        case Message(payload, _, _, _, _) => log.info("@george_bush received message from: %s", payload)
      }
    })
    consumer ! MessageConsumerListener("@barack_obama", "", false, new Actor() {
      def receive: PartialFunction[Any, Unit] = {
        case Message(payload, _, _, _, _) => log.info("@barack_obama received message from: %s", payload)
      }
    })
    val producer = AMQP.newProducer(CONFIG, HOSTNAME, PORT, CHAT, SERIALIZER, None, None, 100)
    producer ! Message("@jonas_boner: I'm going surfing", "")
  }
}