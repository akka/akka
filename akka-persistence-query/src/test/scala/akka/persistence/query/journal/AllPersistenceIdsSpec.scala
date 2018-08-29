/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal

import akka.persistence.query.scaladsl.{CurrentPersistenceIdsQuery, PersistenceIdsQuery, ReadJournal}
import akka.stream.ActorMaterializer
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.{AkkaSpec, ImplicitSender}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._

object AllPersistenceIdsSpec {
  def config: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.test.single-expect-default = 10s
    """)
}

abstract class AllPersistenceIdsSpec(backendName: String, config: Config) extends AkkaSpec(config)
  with ImplicitSender {

  implicit val mat = ActorMaterializer()(system)

  def queries: ReadJournal with CurrentPersistenceIdsQuery with PersistenceIdsQuery

  s"$backendName query AllPersistenceIds" must {

    "implement standard AllPersistenceIdsQuery" in {
      queries.isInstanceOf[PersistenceIdsQuery] should ===(true)
    }

    "find existing persistenceIds" in {
      system.actorOf(TestActor.props("a")) ! "a1"
      expectMsg("a1-done")
      system.actorOf(TestActor.props("b")) ! "b1"
      expectMsg("b1-done")
      system.actorOf(TestActor.props("c")) ! "c1"
      expectMsg("c1-done")

      val src = queries.currentPersistenceIds()
      val probe = src.runWith(TestSink.probe[String])
      probe.within(10.seconds) {
        probe.request(5)
          .expectNextUnordered("a", "b", "c")
          .expectComplete()
      }
    }

    "find new persistenceIds" in {
      // a, b, c created by previous step
      system.actorOf(TestActor.props("d")) ! "d1"
      expectMsg("d1-done")

      val src = queries.persistenceIds()
      val probe = src.runWith(TestSink.probe[String])
      probe.within(10.seconds) {
        probe.request(5)
          .expectNextUnorderedN(List("a", "b", "c", "d"))

        system.actorOf(TestActor.props("e")) ! "e1"
        probe.expectNext("e")

        val more = (1 to 100).map("f" + _)
        more.foreach { p ⇒
          system.actorOf(TestActor.props(p)) ! p
        }

        probe.request(100)
        probe.expectNextUnorderedN(more)
      }

    }
  }

}
