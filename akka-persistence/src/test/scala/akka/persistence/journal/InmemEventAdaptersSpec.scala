/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.journal

import akka.actor.ExtendedActorSystem
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory

class InmemEventAdaptersSpec extends AkkaSpec {

  val config = ConfigFactory.parseString(
    s"""
      |akka.persistence.journal {
      |  plugin = "akka.persistence.journal.inmem"
      |
      |
      |  # adapters defined for all plugins
      |  common-event-adapter-bindings {
      |  }
      |
      |  inmem {
      |    # showcases re-using and concating configuration of adapters
      |
      |    event-adapters {
      |      example  = ${classOf[ExampleEventAdapter].getCanonicalName}
      |      marker   = ${classOf[MarkerInterfaceAdapter].getCanonicalName}
      |      precise  = ${classOf[PreciseAdapter].getCanonicalName}
      |      reader  = ${classOf[ReaderAdapter].getCanonicalName}
      |      writer  = ${classOf[WriterAdapter].getCanonicalName}
      |    }
      |    event-adapter-bindings = {
      |      "${classOf[EventMarkerInterface].getCanonicalName}" = marker
      |      "java.lang.String" = example
      |      "akka.persistence.journal.PreciseAdapterEvent" = precise
      |      "akka.persistence.journal.ReadMeEvent" = reader
      |      "akka.persistence.journal.WriteMeEvent" = writer
      |    }
      |  }
      |}
    """.stripMargin).withFallback(ConfigFactory.load())

  val extendedActorSystem = system.asInstanceOf[ExtendedActorSystem]
  val inmemConfig = config.getConfig("akka.persistence.journal.inmem")

  "EventAdapters" must {
    "parse configuration and resolve adapter definitions" in {
      val adapters = EventAdapters(extendedActorSystem, inmemConfig)
      adapters.get(classOf[EventMarkerInterface]).getClass should ===(classOf[MarkerInterfaceAdapter])
    }

    "pick the most specific adapter available" in {
      val adapters = EventAdapters(extendedActorSystem, inmemConfig)

      // sanity check; precise case, matching non-user classes
      adapters.get(classOf[java.lang.String]).getClass should ===(classOf[ExampleEventAdapter])

      // pick adapter by implemented marker interface
      adapters.get(classOf[SampleEvent]).getClass should ===(classOf[MarkerInterfaceAdapter])

      // more general adapter matches as well, but most specific one should be picked
      adapters.get(classOf[PreciseAdapterEvent]).getClass should ===(classOf[PreciseAdapter])

      // no adapter defined for Long, should return identity adapter
      adapters.get(classOf[java.lang.Long]).getClass should ===(IdentityEventAdapter.getClass)
    }

    "fail with useful message when binding to not defined adapter" in {
      val badConfig = ConfigFactory.parseString(
        """
          |akka.persistence.journal.inmem {
          |  event-adapter-bindings {
          |    "java.lang.Integer" = undefined-adapter
          |  }
          |}
        """.stripMargin)

      val combinedConfig = badConfig.getConfig("akka.persistence.journal.inmem")
      val ex = intercept[IllegalArgumentException] {
        EventAdapters(extendedActorSystem, combinedConfig)
      }

      ex.getMessage should include("java.lang.Integer was bound to undefined event-adapter: undefined-adapter")
    }

    "allow implementing only the read-side (ReadEventAdapter)" in {
      val adapters = EventAdapters(extendedActorSystem, inmemConfig)

      // read-side only adapter
      val r: EventAdapter = adapters.get(classOf[ReadMeEvent])
      r.fromJournal(r.toJournal(ReadMeEvent()), "").events.head.toString should ===("from-ReadMeEvent()")
    }

    "allow implementing only the write-side (WriteEventAdapter)" in {
      val adapters = EventAdapters(extendedActorSystem, inmemConfig)

      // write-side only adapter
      val w: EventAdapter = adapters.get(classOf[WriteMeEvent])
      w.fromJournal(w.toJournal(WriteMeEvent()), "").events.head.toString should ===("to-WriteMeEvent()")
    }
  }

}

abstract class BaseTestAdapter extends EventAdapter {
  override def toJournal(event: Any): Any = event
  override def fromJournal(event: Any, manifest: String): EventSeq = EventSeq.single(event)
  override def manifest(event: Any): String = ""
}

class ExampleEventAdapter extends BaseTestAdapter {
}
class MarkerInterfaceAdapter extends BaseTestAdapter {
}
class PreciseAdapter extends BaseTestAdapter {
}

case class ReadMeEvent()
class ReaderAdapter extends ReadEventAdapter {
  override def fromJournal(event: Any, manifest: String): EventSeq =
    EventSeq("from-" + event)
}

case class WriteMeEvent()
class WriterAdapter extends WriteEventAdapter {
  override def manifest(event: Any): String = ""
  override def toJournal(event: Any): Any = "to-" + event
}

trait EventMarkerInterface
final case class SampleEvent() extends EventMarkerInterface
final case class PreciseAdapterEvent() extends EventMarkerInterface

