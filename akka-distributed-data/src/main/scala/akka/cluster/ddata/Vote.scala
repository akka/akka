/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.{ MemberStatus, UniqueAddress }

import scala.collection.JavaConverters._

object Vote {
  val empty: Vote = new Vote
  def apply(): Vote = empty

  /**
   * Java API
   */
  def create(): Vote = empty

  /**
   * Decider for voting.
   */
  type Decider = Iterable[Boolean] => Boolean

  /**
   * At least `n` nodes must vote positive.
   */
  def atLeast(n: Int): Decider =
    _.filter(identity).take(n).size >= n

  /**
   * At least one node must vote positive.
   */
  val AtLeastOne: Decider = atLeast(1)

  /**
   * At most `n` node must vote positive.
   */
  def atMost(n: Int): Decider =
    _.filter(identity).take(n + 1).size <= n

  /**
   * All nodes must vote positive.
   */
  val All: Decider = _.forall(identity)

  /**
   * A majority of nodes must vote positive
   */
  val Majority: Decider = { votes =>
    val (totalVoters, votesFor) = votes.foldLeft((0, 0)) {
      case ((total, votes), vote) => (total + 1, if (vote) votes + 1 else votes)
    }
    votesFor > totalVoters / 2
  }

  /**
   * Java API: Predicate for deciding a vote.
   */
  @FunctionalInterface
  trait DeciderPredicate extends Decider with java.util.function.Predicate[java.lang.Iterable[java.lang.Boolean]] {
    override final def apply(votes: Iterable[Boolean]): Boolean = {
      test(votes.asJava.asInstanceOf[java.lang.Iterable[java.lang.Boolean]])
    }
  }

  private val Zero = BigInt(0)
  private val One = BigInt(1)
}

/**
 * Implements a vote CRDT.
 *
 * A vote CRDT allows each node to manage its own vote. Nodes can change their vote at any time.
 *
 * This CRDT has the same state as a GCounter, recording votes for each node in a node vector as an integer, with
 * odd being a positive vote, and even being a negative vote. Changing a nodes vote is done by incrementing the nodes
 * integer by one.
 *
 * The result of the vote is calculated on request by supplying the current cluster state, along with the set of
 * member statuses that are allowed to take part in the vote, and a [[akka.cluster.ddata.Vote.Decider]].
 */
@SerialVersionUID(1L)
final case class Vote private[akka] (
    private[akka] val state: Map[UniqueAddress, BigInt] = Map.empty,
    override val delta: Option[Vote] = None)
    extends NodeVector[BigInt](state)
    with ReplicatedDataSerialization {

  type T = Vote

  import Vote.Zero
  import Vote.One

  /**
   * Scala API: The result given the current cluster state.
   */
  def result(decider: Vote.Decider, allowedVoterStatuses: Set[MemberStatus] = Set(MemberStatus.Up))(
      implicit currentClusterState: CurrentClusterState): Boolean = {
    // this doesn't seem to be the most efficient way of determining the voters
    // nevertheless, filterKeys/values returns a view/iterable, so this allows us to break early from calculating
    // all the voters when possible by using take
    val votes = state
      .filterKeys(key =>
        currentClusterState.members.exists { member =>
          member.uniqueAddress == key && allowedVoterStatuses(member.status)
        })
      .values
      .map(_.testBit(0))

    decider(votes)
  }

  /**
   * Java API: The result given the current cluster state.
   */
  def getResult(
      decider: Vote.Decider,
      allowedVoterStatuses: java.util.Collection[MemberStatus],
      currentClusterState: CurrentClusterState): Boolean =
    result(decider, allowedVoterStatuses.asScala.toSet)(currentClusterState)

  /**
   * Scala API: Place a vote.
   */
  def vote(vote: Boolean)(implicit node: SelfUniqueAddress): Vote = {
    state.get(node.uniqueAddress) match {
      case Some(v) if vote != v.testBit(0) => update(node.uniqueAddress, v + 1)
      case None if vote                    => update(node.uniqueAddress, One)
      case _                               => this
    }
  }

  /**
   * Java API: Place a vote.
   */
  def setVote(node: SelfUniqueAddress, vote: Boolean): Vote = {
    this.vote(vote)(node)
  }

  override protected def newVector(state: Map[UniqueAddress, BigInt], delta: Option[Vote]): Vote =
    new Vote(state, delta)

  override protected def mergeValues(thisValue: BigInt, thatValue: BigInt): BigInt =
    if (thisValue > thatValue) thisValue
    else thatValue

  override protected def collapseInto(key: UniqueAddress, value: BigInt): Vote = this

  override def zero: Vote = Vote.empty

  // this class cannot be a `case class` because we need different `unapply`

  override def toString: String =
    s"Vote(${state.map { case (a, v) => s"${a.address}@${a.longUid} -> ${v.testBit(0)}" }.mkString(",")})"
}

object VoteKey {
  def create(id: String): Key[Vote] = VoteKey(id)
}

@SerialVersionUID(1L)
final case class VoteKey(_id: String) extends Key[Vote](_id) with ReplicatedDataSerialization
