/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal

import scala.collection.immutable
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.language.postfixOps

import akka.AkkaException
import akka.actor._
import akka.pattern.ask
import akka.persistence._
import akka.util._

/**
 * INTERNAL API.
 *
 * A journal that delegates actual storage to a target actor. For testing only.
 */
private[persistence] trait AsyncWriteProxy extends AsyncWriteJournal with Stash {
  import AsyncWriteProxy._
  import AsyncWriteTarget._

  private val initialized = super.receive
  private var store: ActorRef = _

  override def receive = {
    case SetStore(ref) ⇒
      store = ref
      unstashAll()
      context.become(initialized)
    case _ ⇒ stash()
  }

  implicit def timeout: Timeout

  def writeAsync(persistentBatch: immutable.Seq[PersistentRepr]): Future[Unit] =
    (store ? WriteBatch(persistentBatch)).mapTo[Unit]

  def deleteAsync(processorId: String, fromSequenceNr: Long, toSequenceNr: Long, permanent: Boolean): Future[Unit] =
    (store ? Delete(processorId, fromSequenceNr, toSequenceNr, permanent)).mapTo[Unit]

  def confirmAsync(processorId: String, sequenceNr: Long, channelId: String): Future[Unit] =
    (store ? Confirm(processorId, sequenceNr, channelId)).mapTo[Unit]

  def replayAsync(processorId: String, fromSequenceNr: Long, toSequenceNr: Long)(replayCallback: (PersistentRepr) ⇒ Unit): Future[Long] = {
    val replayCompletionPromise = Promise[Long]
    val mediator = context.actorOf(Props(classOf[ReplayMediator], replayCallback, replayCompletionPromise, timeout.duration).withDeploy(Deploy.local))
    store.tell(Replay(processorId, fromSequenceNr, toSequenceNr), mediator)
    replayCompletionPromise.future
  }
}

/**
 * INTERNAL API.
 */
private[persistence] object AsyncWriteProxy {
  case class SetStore(ref: ActorRef)
}

/**
 * INTERNAL API.
 */
private[persistence] object AsyncWriteTarget {
  @SerialVersionUID(1L)
  case class WriteBatch(pb: immutable.Seq[PersistentRepr])

  @SerialVersionUID(1L)
  case class Delete(processorId: String, fromSequenceNr: Long, toSequenceNr: Long, permanent: Boolean)

  @SerialVersionUID(1L)
  case class Confirm(processorId: String, sequenceNr: Long, channelId: String)

  @SerialVersionUID(1L)
  case class Replay(processorId: String, fromSequenceNr: Long, toSequenceNr: Long)

  @SerialVersionUID(1L)
  case class ReplaySuccess(maxSequenceNr: Long)

  @SerialVersionUID(1L)
  case class ReplayFailure(cause: Throwable)
}

/**
 * Thrown if replay inactivity exceeds a specified timeout.
 */
@SerialVersionUID(1L)
class AsyncReplayTimeoutException(msg: String) extends AkkaException(msg)

private class ReplayMediator(replayCallback: PersistentRepr ⇒ Unit, replayCompletionPromise: Promise[Long], replayTimeout: Duration) extends Actor {
  import AsyncWriteTarget._

  context.setReceiveTimeout(replayTimeout)

  def receive = {
    case p: PersistentRepr ⇒ replayCallback(p)
    case ReplaySuccess(maxSnr) ⇒
      replayCompletionPromise.success(maxSnr)
      context.stop(self)
    case ReplayFailure(cause) ⇒
      replayCompletionPromise.failure(cause)
      context.stop(self)
    case ReceiveTimeout ⇒
      replayCompletionPromise.failure(new AsyncReplayTimeoutException(s"replay timed out after ${replayTimeout.toSeconds} seconds inactivity"))
      context.stop(self)
  }
}