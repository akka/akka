/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.ddata.sample

import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorSystem
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.ORSet
import com.typesafe.config.ConfigFactory
import akka.cluster.ddata.Replicator
import akka.cluster.ddata.ORSetKey

object DataBot {

  def main(args: Array[String]): Unit = {
    if (args.isEmpty)
      startup(Seq("2551", "2552", "0"))
    else
      startup(args)
  }

  def startup(ports: Seq[String]): Unit = {
    ports.foreach { port ⇒
      // Override the configuration of the port
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
        withFallback(ConfigFactory.load(
          ConfigFactory.parseString("""
            akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
            akka.remote {
              netty.tcp {
                hostname = "127.0.0.1"
                port = 0
              }
            }

            akka.cluster {
              seed-nodes = [
                "akka.tcp://ClusterSystem@127.0.0.1:2551",
                "akka.tcp://ClusterSystem@127.0.0.1:2552"]

              auto-down-unreachable-after = 10s
            }
            """)))

      // Create an Akka system
      val system = ActorSystem("ClusterSystem", config)
      // Create an actor that handles cluster domain events
      system.actorOf(Props[DataBot], name = "dataBot")
    }
  }

  private case object Tick

}

class DataBot extends Actor with ActorLogging {
  import DataBot._
  import Replicator._

  val replicator = DistributedData(context.system).replicator
  implicit val node = Cluster(context.system)

  import context.dispatcher
  val tickTask = context.system.scheduler.schedule(5.seconds, 5.seconds, self, Tick)

  val DataKey = ORSetKey[String]("key")

  replicator ! Subscribe(DataKey, self)

  def receive = {
    case Tick ⇒
      val s = ThreadLocalRandom.current().nextInt(97, 123).toChar.toString
      if (ThreadLocalRandom.current().nextBoolean()) {
        // add
        log.info("Adding: {}", s)
        replicator ! Update(DataKey, ORSet.empty[String], WriteLocal)(_ + s)
      } else {
        // remove
        log.info("Removing: {}", s)
        replicator ! Update(DataKey, ORSet.empty[String], WriteLocal)(_ - s)
      }

    case _: UpdateResponse[_] ⇒ // ignore

    case c @ Changed(DataKey) ⇒
      log.info("Current elements: {}", c.get(DataKey).elements)
  }

  override def postStop(): Unit = tickTask.cancel()

}

