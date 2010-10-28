/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>.
 */

package sample.pubsub

import com.redis.{RedisClient, PubSubMessage, S, U, M}
import akka.persistence.redis._
import akka.actor.Actor._

/**
 * Sample Akka application for Redis PubSub
 *
 * Prerequisite: Need Redis Server running (the version that supports pubsub)
 * <pre>
 * 1. Download redis from http://github.com/antirez/redis
 * 2. build using "make"
 * 3. Run server as ./redis-server
 * </pre>
 *
 * For running this sample application :-
 *
 * <pre>
 * 1. Open a shell and set AKKA_HOME to the distribution root
 * 2. cd $AKKA_HOME
 * 3. sbt console
 * 4. import sample.pubsub._
 * 5. Sub.sub("a", "b") // starts Subscription server & subscribes to channels "a" and "b"
 *
 * 6. Open up another shell similarly as the above and set AKKA_HOME
 * 7. cd $AKKA_HOME
 * 8. sbt console
 * 9. import sample.pubsub._
 * 10. Pub.publish("a", "hello") // the first shell should get the message
 * 11. Pub.publish("c", "hi") // the first shell should NOT get this message
 *
 * 12. Open up a redis-client from where you installed redis and issue a publish command
 *     ./redis-cli publish a "hi there" ## the first shell should get the message
 *
 * 13. Go back to the first shell
 * 14. Sub.unsub("a") // should unsubscribe the first shell from channel "a"
 *
 * 15. Study the callback function defined below. It supports many other message formats.
 *     In the second shell window do the following:
 *     scala> Pub.publish("b", "+c")  // will subscribe the first window to channel "c"
 *     scala> Pub.publish("b", "+d")  // will subscribe the first window to channel "d"
 *     scala> Pub.publish("b", "-c")  // will unsubscribe the first window from channel "c"
 *     scala> Pub.publish("b", "exit")  // will unsubscribe the first window from all channels
 * </pre>
 */

object Pub {
  println("starting publishing service ..")
  val r = new RedisClient("localhost", 6379)
  val p = actorOf(new Publisher(r))
  p.start

  def publish(channel: String, message: String) = {
    p ! Publish(channel, message)
  }
}

object Sub {
  println("starting subscription service ..")
  val r = new RedisClient("localhost", 6379)
  val s = actorOf(new Subscriber(r))
  s.start
  s ! Register(callback)

  def sub(channels: String*) = {
    s ! Subscribe(channels.toArray)
  }

  def unsub(channels: String*) = {
    s ! Unsubscribe(channels.toArray)
  }

  def callback(pubsub: PubSubMessage) = pubsub match {
    case S(channel, no) => println("subscribed to " + channel + " and count = " + no)
    case U(channel, no) => println("unsubscribed from " + channel + " and count = " + no)
    case M(channel, msg) =>
      msg match {
        // exit will unsubscribe from all channels and stop subscription service
        case "exit" =>
          println("unsubscribe all ..")
          r.unsubscribe

        // message "+x" will subscribe to channel x
        case x if x startsWith "+" =>
          val s: Seq[Char] = x
          s match {
            case Seq('+', rest @ _*) => r.subscribe(rest.toString){ m => }
          }

        // message "-x" will unsubscribe from channel x
        case x if x startsWith "-" =>
          val s: Seq[Char] = x
          s match {
            case Seq('-', rest @ _*) => r.unsubscribe(rest.toString)
          }

        // other message receive
        case x =>
          println("received message on channel " + channel + " as : " + x)
      }
  }
}
