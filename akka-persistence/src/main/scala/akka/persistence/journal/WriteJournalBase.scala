/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal

import akka.persistence.{ PersistentRepr, Resequenceable }
import akka.actor.Actor
import scala.collection.immutable

private[akka] trait WriteJournalBase {
  this: Actor ⇒

  protected def preparePersistentBatch(rb: immutable.Seq[Resequenceable]): immutable.Seq[PersistentRepr] =
    rb.filter(persistentPrepareWrite).asInstanceOf[immutable.Seq[PersistentRepr]] // filter instead of flatMap to avoid Some allocations

  private def persistentPrepareWrite(r: Resequenceable): Boolean = r match {
    case p: PersistentRepr ⇒
      p.prepareWrite(); true
    case _ ⇒
      false
  }

}
