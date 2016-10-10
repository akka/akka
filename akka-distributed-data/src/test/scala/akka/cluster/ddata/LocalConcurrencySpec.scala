/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Stash
import akka.cluster.Cluster
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

object LocalConcurrencySpec {

  final case class Add(s: String)

  object Updater {
    val key = ORSetKey[String]("key")
  }

  class Updater extends Actor with Stash {
    implicit val cluster = Cluster(context.system)
    val replicator = DistributedData(context.system).replicator

    def receive = {
      case s: String ⇒
        val update = Replicator.Update(Updater.key, ORSet.empty[String], Replicator.WriteLocal)(_ + s)
        replicator ! update
    }
  }
}

class LocalConcurrencySpec(_system: ActorSystem) extends TestKit(_system)
  with WordSpecLike with Matchers with BeforeAndAfterAll with ImplicitSender {
  import LocalConcurrencySpec._

  def this() {
    this(ActorSystem(
      "LocalConcurrencySpec",
      ConfigFactory.parseString("""
      akka.actor.provider = "cluster"
      akka.remote.netty.tcp.port=0
      akka.remote.artery.canonical.port = 0
      """)))
  }

  override def afterAll(): Unit = {
    shutdown(system)
  }

  val replicator = DistributedData(system).replicator

  "Updates from same node" must {

    "be possible to do from two actors" in {
      val updater1 = system.actorOf(Props[Updater], "updater1")
      val updater2 = system.actorOf(Props[Updater], "updater2")

      val numMessages = 100
      for (n ← 1 to numMessages) {
        updater1 ! s"a$n"
        updater2 ! s"b$n"
      }

      val expected = ((1 to numMessages).map("a" + _) ++ (1 to numMessages).map("b" + _)).toSet
      awaitAssert {
        replicator ! Replicator.Get(Updater.key, Replicator.ReadLocal)
        val ORSet(elements) = expectMsgType[Replicator.GetSuccess[_]].get(Updater.key)
        elements should be(expected)
      }

    }

  }
}
