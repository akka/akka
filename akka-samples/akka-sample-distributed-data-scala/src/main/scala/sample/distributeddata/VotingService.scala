package sample.distributeddata

import scala.concurrent.duration._
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.FlagKey
import akka.actor.Actor
import akka.cluster.ddata.PNCounterMapKey
import akka.actor.ActorRef
import akka.cluster.Cluster
import akka.cluster.ddata.PNCounterMap
import akka.cluster.ddata.Flag

object VotingService {
  case object Open
  case object OpenAck
  case object Close
  case object CloseAck
  final case class Vote(participant: String)
  case object GetVotes
  final case class Votes(result: Map[String, BigInt], open: Boolean)

  private final case class GetVotesReq(replyTo: ActorRef)
}

class VotingService extends Actor {
  import akka.cluster.ddata.Replicator._
  import VotingService._

  val replicator = DistributedData(context.system).replicator
  implicit val cluster = Cluster(context.system)
  val OpenedKey = FlagKey("contestOpened")
  val ClosedKey = FlagKey("contestClosed")
  val CountersKey = PNCounterMapKey("contestCounters")

  replicator ! Subscribe(OpenedKey, self)

  def receive = {
    case Open ⇒
      replicator ! Update(OpenedKey, Flag(), WriteAll(5.seconds))(_.switchOn)
      becomeOpen()

    case c @ Changed(OpenedKey) if c.get(OpenedKey).enabled ⇒
      becomeOpen()

    case GetVotes ⇒
      sender() ! Votes(Map.empty, open = false)
  }

  def becomeOpen(): Unit = {
    replicator ! Unsubscribe(OpenedKey, self)
    replicator ! Subscribe(ClosedKey, self)
    context.become(open orElse getVotes(open = true))
  }

  def open: Receive = {
    case v @ Vote(participant) ⇒
      val update = Update(CountersKey, PNCounterMap(), WriteLocal, request = Some(v)) {
        _.increment(participant, 1)
      }
      replicator ! update

    case _: UpdateSuccess[_] ⇒

    case Close ⇒
      replicator ! Update(ClosedKey, Flag(), WriteAll(5.seconds))(_.switchOn)
      context.become(getVotes(open = false))

    case c @ Changed(ClosedKey) if c.get(ClosedKey).enabled ⇒
      context.become(getVotes(open = false))
  }

  def getVotes(open: Boolean): Receive = {
    case GetVotes ⇒
      replicator ! Get(CountersKey, ReadAll(3.seconds), Some(GetVotesReq(sender())))

    case g @ GetSuccess(CountersKey, Some(GetVotesReq(replyTo))) ⇒
      val data = g.get(CountersKey)
      replyTo ! Votes(data.entries, open)

    case NotFound(CountersKey, Some(GetVotesReq(replyTo))) ⇒
      replyTo ! Votes(Map.empty, open)

    case _: GetFailure[_]    ⇒

    case _: UpdateSuccess[_] ⇒
  }

}
