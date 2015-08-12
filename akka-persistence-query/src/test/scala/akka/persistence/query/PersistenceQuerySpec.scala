/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.query

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.persistence.journal.{ EventAdapter, EventSeq }
import com.typesafe.config.ConfigFactory
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.concurrent.Await
import scala.concurrent.duration._

class PersistenceQuerySpec extends WordSpecLike with Matchers with BeforeAndAfterAll {

  val anything: Query[String, _] = null

  val eventAdaptersConfig =
    s"""
      |akka.persistence.query.journal.mock {
      |  event-adapters {
      |    adapt = ${classOf[PrefixStringWithPAdapter].getCanonicalName}
      |  }
      |}
    """.stripMargin

  "ReadJournal" must {
    "be found by full config key" in {
      withActorSystem() { system ⇒
        PersistenceQuery.get(system).readJournalFor(MockReadJournal.Identifier)
      }
    }

    "throw if unable to find query journal by config key" in {
      withActorSystem() { system ⇒
        intercept[IllegalArgumentException] {
          PersistenceQuery.get(system).readJournalFor(MockReadJournal.Identifier + "-unknown")
        }.getMessage should include("missing persistence read journal")
      }
    }

    "expose scaladsl implemented journal as javadsl.ReadJournal" in {
      withActorSystem() { system ⇒
        val j: javadsl.ReadJournal = PersistenceQuery.get(system).getReadJournalFor(MockReadJournal.Identifier)
      }
    }
    "expose javadsl implemented journal as scaladsl.ReadJournal" in {
      withActorSystem() { system ⇒
        val j: scaladsl.ReadJournal = PersistenceQuery.get(system).readJournalFor(MockJavaReadJournal.Identifier)
      }
    }
  }

  private val systemCounter = new AtomicInteger()
  private def withActorSystem(conf: String = "")(block: ActorSystem ⇒ Unit): Unit = {
    val config =
      MockReadJournal.config
        .withFallback(MockJavaReadJournal.config)
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

class PrefixStringWithPAdapter extends EventAdapter {
  override def fromJournal(event: Any, manifest: String) = EventSeq.single("p-" + event)

  override def manifest(event: Any) = ""
  override def toJournal(event: Any) = throw new Exception("toJournal should not be called by query side")
}