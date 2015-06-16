/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal

import akka.persistence.{ PersistentRepr, PersistentEnvelope }
import akka.actor.Actor
import scala.collection.immutable

private[akka] trait WriteJournalBase {
  this: Actor ⇒

  protected def preparePersistentBatch(rb: immutable.Seq[PersistentEnvelope]): immutable.Seq[PersistentRepr] =
    rb.collect {
      case p: PersistentRepr ⇒ p.update(sender = Actor.noSender) // don't store sender
    }

}
