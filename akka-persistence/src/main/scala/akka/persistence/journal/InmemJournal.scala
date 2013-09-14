/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal

import com.typesafe.config.Config

import akka.actor._
import akka.pattern.PromiseActorRef
import akka.persistence._

/**
 * In-memory journal configuration object.
 */
private[persistence] class InmemJournalSettings(config: Config) extends JournalFactory {
  /**
   * Creates a new in-memory journal actor from this configuration object.
   */
  def createJournal(implicit factory: ActorRefFactory): ActorRef = factory.actorOf(Props(classOf[InmemJournal], this))
}

/**
 * In-memory journal.
 */
private[persistence] class InmemJournal(settings: InmemJournalSettings) extends Actor {
  // processorId => (message, sender, deleted)
  private var messages = Map.empty[String, List[(PersistentImpl, ActorRef, Boolean)]]
  // (processorId, sequenceNr) => confirming channels ids
  private var confirms = Map.empty[(String, Long), List[String]]

  import Journal._

  def receive = {
    case Write(pm, p) ⇒ {
      // must be done because PromiseActorRef instances have no uid set TODO: discuss
      val ps = if (sender.isInstanceOf[PromiseActorRef]) context.system.deadLetters else sender
      messages = messages + (messages.get(pm.processorId) match {
        case None      ⇒ pm.processorId -> List((pm.copy(resolved = false), ps, false))
        case Some(mss) ⇒ pm.processorId -> ((pm.copy(resolved = false), ps, false) :: mss)
      })
      p.tell(Written(pm), sender)
    }
    case c @ Confirm(pid, snr, cid) ⇒ {
      val pair = (pid, snr)
      confirms = confirms + (confirms.get(pair) match {
        case None       ⇒ pair -> List(cid)
        case Some(cids) ⇒ pair -> (cid :: cids)
      })
      // TODO: turn off by default and allow to turn on by configuration
      context.system.eventStream.publish(c)
    }
    case Delete(pm: PersistentImpl) ⇒ {
      val pid = pm.processorId
      val snr = pm.sequenceNr
      messages = messages map {
        case (`pid`, mss) ⇒ pid -> (mss map {
          case (msg, sdr, _) if msg.sequenceNr == snr ⇒ (msg, sdr, true)
          case ms                                     ⇒ ms
        })
        case kv ⇒ kv
      }
    }
    case Loop(m, p) ⇒ {
      p.tell(Looped(m), sender)
    }
    case Replay(toSnr, p, pid) ⇒ {
      val cfs = confirms.withDefaultValue(Nil)
      for {
        mss ← messages.get(pid)
        (msg, sdr, del) ← mss.reverseIterator.filter(_._1.sequenceNr <= toSnr)
      } if (!del) p.tell(Replayed(msg.copy(confirms = cfs((msg.processorId, msg.sequenceNr)))), sdr)
      p.tell(RecoveryEnd(messages.get(pid).map(_.head._1.sequenceNr).getOrElse(0L)), self)
    }
  }
}
