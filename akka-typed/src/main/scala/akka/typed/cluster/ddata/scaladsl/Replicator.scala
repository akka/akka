/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.ddata.scaladsl

import akka.actor.NoSerializationVerificationNeeded

import akka.cluster.{ ddata ⇒ dd }
import akka.cluster.ddata.Key
import akka.cluster.ddata.ReplicatedData
import akka.typed.ActorRef
import akka.typed.Behavior
import akka.typed.cluster.ddata.internal.ReplicatorBehavior

object Replicator {

  def behavior(settings: ReplicatorSettings): Behavior[Command[_]] =
    ReplicatorBehavior.behavior(settings)

  type ReadConsistency = dd.Replicator.ReadConsistency
  val ReadLocal = dd.Replicator.ReadLocal
  type ReadFrom = dd.Replicator.ReadFrom
  type ReadMajority = dd.Replicator.ReadMajority
  type ReadAll = dd.Replicator.ReadAll

  type WriteConsistency = dd.Replicator.WriteConsistency
  val WriteLocal = dd.Replicator.WriteLocal
  type WriteTo = dd.Replicator.WriteTo
  type WriteMajority = dd.Replicator.WriteMajority
  type WriteAll = dd.Replicator.WriteAll

  trait Command[A <: ReplicatedData] {
    def key: Key[A]
  }

  final case class Get[A <: ReplicatedData](key: Key[A], consistency: ReadConsistency, request: Option[Any] = None)(val replyTo: ActorRef[GetResponse[A]])
    extends Command[A] {

  }

  type GetResponse[A <: ReplicatedData] = dd.Replicator.GetResponse[A]
  object GetSuccess {
    def unapply[A <: ReplicatedData](rsp: GetSuccess[A]): Option[(Key[A], Option[Any])] = Some((rsp.key, rsp.request))
  }
  type GetSuccess[A <: ReplicatedData] = dd.Replicator.GetSuccess[A]
  type NotFound[A <: ReplicatedData] = dd.Replicator.NotFound[A]
  type GetFailure[A <: ReplicatedData] = dd.Replicator.GetFailure[A]

  object Update {

    /**
     * Modify value of local `Replicator` and replicate with given `writeConsistency`.
     *
     * The current value for the `key` is passed to the `modify` function.
     * If there is no current data value for the `key` the `initial` value will be
     * passed to the `modify` function.
     *
     * The optional `request` context is included in the reply messages. This is a convenient
     * way to pass contextual information (e.g. original sender) without having to use `ask`
     * or local correlation data structures.
     */
    def apply[A <: ReplicatedData](
      key: Key[A], initial: A, writeConsistency: WriteConsistency,
      request: Option[Any] = None)(modify: A ⇒ A)(replyTo: ActorRef[UpdateResponse[A]]): Update[A] =
      Update(key, writeConsistency, request)(modifyWithInitial(initial, modify))(replyTo)

    private def modifyWithInitial[A <: ReplicatedData](initial: A, modify: A ⇒ A): Option[A] ⇒ A = {
      case Some(data) ⇒ modify(data)
      case None       ⇒ modify(initial)
    }
  }
  /**
   * Send this message to the local `Replicator` to update a data value for the
   * given `key`. The `Replicator` will reply with one of the [[UpdateResponse]] messages.
   *
   * Note that the [[Replicator.Update$ companion]] object provides `apply` functions for convenient
   * construction of this message.
   *
   * The current data value for the `key` is passed as parameter to the `modify` function.
   * It is `None` if there is no value for the `key`, and otherwise `Some(data)`. The function
   * is supposed to return the new value of the data, which will then be replicated according to
   * the given `writeConsistency`.
   *
   * The `modify` function is called by the `Replicator` actor and must therefore be a pure
   * function that only uses the data parameter and stable fields from enclosing scope. It must
   * for example not access `sender()` reference of an enclosing actor.
   */
  final case class Update[A <: ReplicatedData](key: Key[A], writeConsistency: WriteConsistency,
                                               request: Option[Any])(val modify: Option[A] ⇒ A)(val replyTo: ActorRef[UpdateResponse[A]])
    extends Command[A] with NoSerializationVerificationNeeded {
  }

  type UpdateResponse[A <: ReplicatedData] = dd.Replicator.UpdateResponse[A]
  type UpdateSuccess[A <: ReplicatedData] = dd.Replicator.UpdateSuccess[A]
  type UpdateFailure[A <: ReplicatedData] = dd.Replicator.UpdateFailure[A]
  type UpdateTimeout[A <: ReplicatedData] = dd.Replicator.UpdateTimeout[A]
  type ModifyFailure[A <: ReplicatedData] = dd.Replicator.ModifyFailure[A]
  type StoreFailure[A <: ReplicatedData] = dd.Replicator.StoreFailure[A]

}
