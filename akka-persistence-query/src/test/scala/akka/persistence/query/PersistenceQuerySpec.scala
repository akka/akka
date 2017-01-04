/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.query

import java.util.concurrent.atomic.AtomicInteger
import akka.actor.ActorSystem
import akka.persistence.journal.EventSeq
import akka.persistence.journal.ReadEventAdapter
import com.typesafe.config.ConfigFactory
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import scala.concurrent.Await
import scala.concurrent.duration._

class PersistenceQuerySpec extends WordSpecLike with Matchers with BeforeAndAfterAll {

  val eventAdaptersConfig =
    s"""
      |akka.persistence.query.journal.dummy {
      |  event-adapters {
      |    adapt = ${classOf[PrefixStringWithPAdapter].getCanonicalName}
      |  }
      |}
    """.stripMargin

  "ReadJournal" must {
    "be found by full config key" in {
      withActorSystem() { system ⇒
        PersistenceQuery.get(system).readJournalFor[DummyReadJournal](DummyReadJournal.Identifier)
        // other combinations of constructor parameters
        PersistenceQuery.get(system).readJournalFor[DummyReadJournal](DummyReadJournal.Identifier + "2")
        PersistenceQuery.get(system).readJournalFor[DummyReadJournal](DummyReadJournal.Identifier + "3")
        PersistenceQuery.get(system).readJournalFor[DummyReadJournal](DummyReadJournal.Identifier + "4")
      }
    }

    "throw if unable to find query journal by config key" in {
      withActorSystem() { system ⇒
        intercept[IllegalArgumentException] {
          PersistenceQuery.get(system).readJournalFor[DummyReadJournal](DummyReadJournal.Identifier + "-unknown")
        }.getMessage should include("missing persistence read journal")
      }
    }

  }

  private val systemCounter = new AtomicInteger()
  private def withActorSystem(conf: String = "")(block: ActorSystem ⇒ Unit): Unit = {
    val config =
      DummyReadJournalProvider.config
        .withFallback(DummyJavaReadJournalProvider.config)
        .withFallback(ConfigFactory.parseString(conf))
        .withFallback(ConfigFactory.parseString(eventAdaptersConfig))
        .withFallback(ConfigFactory.load())

    val sys = ActorSystem(s"sys-${systemCounter.incrementAndGet()}", config)
    try block(sys) finally Await.ready(sys.terminate(), 10.seconds)
  }
}

object ExampleQueryModels {
  case class OldModel(value: String) { def promote = NewModel(value) }
  case class NewModel(value: String)
}

class PrefixStringWithPAdapter extends ReadEventAdapter {
  override def fromJournal(event: Any, manifest: String) = EventSeq.single("p-" + event)
}

