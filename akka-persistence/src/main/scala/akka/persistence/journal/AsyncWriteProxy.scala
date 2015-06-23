/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal

import akka.AkkaException
import akka.actor._
import akka.pattern.ask
import akka.persistence._
import akka.util._
import scala.util.Try

import scala.collection.immutable
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.language.postfixOps

/**
 * INTERNAL API.
 *
 * A journal that delegates actual storage to a target actor. For testing only.
 */
private[persistence] trait AsyncWriteProxy extends AsyncWriteJournal with Stash with ActorLogging {
  import AsyncWriteProxy._
  import AsyncWriteTarget._

  private var isInitialized = false
  private var store: ActorRef = _

  override protected[akka] def aroundReceive(receive: Receive, msg: Any): Unit =
    if (isInitialized) super.aroundReceive(receive, msg)
    else msg match {
      case SetStore(ref) ⇒
        store = ref
        unstashAll()
        isInitialized = true
      case _ ⇒ stash()
    }

  implicit def timeout: Timeout

  def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] =
    (store ? WriteMessages(messages)).mapTo[immutable.Seq[Try[Unit]]]

  def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean): Future[Unit] =
    (store ? DeleteMessagesTo(persistenceId, toSequenceNr, permanent)).mapTo[Unit]

  def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(replayCallback: PersistentRepr ⇒ Unit): Future[Unit] = {
    val replayCompletionPromise = Promise[Unit]()
    val mediator = context.actorOf(Props(classOf[ReplayMediator], replayCallback, replayCompletionPromise, timeout.duration).withDeploy(Deploy.local))
    store.tell(ReplayMessages(persistenceId, fromSequenceNr, toSequenceNr, max), mediator)
    replayCompletionPromise.future
  }

  def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    (store ? ReadHighestSequenceNr(persistenceId, fromSequenceNr)).mapTo[Long]
}

/**
 * INTERNAL API.
 */
private[persistence] object AsyncWriteProxy {
  final case class SetStore(ref: ActorRef)
}

/**
 * INTERNAL API.
 */
private[persistence] object AsyncWriteTarget {
  @SerialVersionUID(1L)
  final case class WriteMessages(messages: immutable.Seq[AtomicWrite])

  @SerialVersionUID(1L)
  final case class DeleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean)

  @SerialVersionUID(1L)
  final case class ReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)

  @SerialVersionUID(1L)
  case object ReplaySuccess

  @SerialVersionUID(1L)
  final case class ReplayFailure(cause: Throwable)

  @SerialVersionUID(1L)
  final case class ReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long)
}

/**
 * Thrown if replay inactivity exceeds a specified timeout.
 */
@SerialVersionUID(1L)
class AsyncReplayTimeoutException(msg: String) extends AkkaException(msg)

private class ReplayMediator(replayCallback: PersistentRepr ⇒ Unit, replayCompletionPromise: Promise[Unit], replayTimeout: Duration) extends Actor {
  import AsyncWriteTarget._

  context.setReceiveTimeout(replayTimeout)

  def receive = {
    case p: PersistentRepr ⇒ replayCallback(p)
    case ReplaySuccess ⇒
      replayCompletionPromise.success(())
      context.stop(self)
    case ReplayFailure(cause) ⇒
      replayCompletionPromise.failure(cause)
      context.stop(self)
    case ReceiveTimeout ⇒
      replayCompletionPromise.failure(new AsyncReplayTimeoutException(s"replay timed out after ${replayTimeout.toSeconds} seconds inactivity"))
      context.stop(self)
  }
}
