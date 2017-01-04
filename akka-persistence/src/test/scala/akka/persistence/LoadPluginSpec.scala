/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence

import akka.persistence.journal.inmem.InmemJournal
import com.typesafe.config.Config
import akka.testkit.ImplicitSender
import akka.actor.Actor

object LoadJournalSpec {

  case object GetConfig

  class JournalWithConfig(val config: Config) extends InmemJournal {
    override def receivePluginInternal: Actor.Receive = {
      case GetConfig ⇒ sender() ! config
    }
  }
}

class LoadJournalSpec extends PersistenceSpec(PersistenceSpec.config("inmem", "LoadJournalSpec", extraConfig = Some(
  """
  akka.persistence.journal.inmem.class = "akka.persistence.LoadJournalSpec$JournalWithConfig"
  akka.persistence.journal.inmem.extra-property = 17
  """))) with ImplicitSender {
  import LoadJournalSpec._

  "A journal with config parameter" must {
    "be created with plugin config" in {
      val journalRef = Persistence(system).journalFor("akka.persistence.journal.inmem")
      journalRef ! GetConfig
      expectMsgType[Config].getInt("extra-property") should be(17)
    }
  }
}

