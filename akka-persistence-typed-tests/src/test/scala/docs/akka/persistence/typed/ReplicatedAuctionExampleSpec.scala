/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed

import java.time.Instant

import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, _ }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.testkit.query.scaladsl.PersistenceTestKitReadJournal
import akka.persistence.typed.ReplicaId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplicatedEventSourcing, ReplicationContext }
import akka.serialization.jackson.CborSerializable
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

object ReplicatedAuctionExampleSpec {

  type MoneyAmount = Int

  case class Bid(bidder: String, offer: MoneyAmount, timestamp: Instant, originReplica: ReplicaId)

  //#commands
  sealed trait AuctionCommand
  case object Finish extends AuctionCommand // A timer needs to schedule this event at each replica
  final case class OfferBid(bidder: String, offer: MoneyAmount) extends AuctionCommand
  final case class GetHighestBid(replyTo: ActorRef[Bid]) extends AuctionCommand
  final case class IsClosed(replyTo: ActorRef[Boolean]) extends AuctionCommand
  private final case object Close extends AuctionCommand // Internal, should not be sent from the outside
  //#commands

  //#events
  sealed trait AuctionEvent extends CborSerializable
  final case class BidRegistered(bid: Bid) extends AuctionEvent
  final case class AuctionFinished(atReplica: ReplicaId) extends AuctionEvent
  final case class WinnerDecided(atReplica: ReplicaId, winningBid: Bid, highestCounterOffer: MoneyAmount)
      extends AuctionEvent
  //#events

  //#phase
  sealed trait AuctionPhase
  case object Running extends AuctionPhase
  final case class Closing(finishedAtReplica: Set[ReplicaId]) extends AuctionPhase
  case object Closed extends AuctionPhase
  //#phase

  //#state
  case class AuctionState(phase: AuctionPhase, highestBid: Bid, highestCounterOffer: MoneyAmount) {

    def applyEvent(event: AuctionEvent): AuctionState =
      event match {
        case BidRegistered(b) =>
          if (isHigherBid(b, highestBid))
            withNewHighestBid(b)
          else
            withTooLowBid(b)
        case AuctionFinished(atDc) =>
          phase match {
            case Running =>
              copy(phase = Closing(Set(atDc)))
            case Closing(alreadyFinishedDcs) =>
              copy(phase = Closing(alreadyFinishedDcs + atDc))
            case _ =>
              this
          }
        case _: WinnerDecided =>
          copy(phase = Closed)
      }

    def withNewHighestBid(bid: Bid): AuctionState = {
      require(phase != Closed)
      require(isHigherBid(bid, highestBid))
      copy(highestBid = bid, highestCounterOffer = highestBid.offer // keep last highest bid around
      )
    }
    def withTooLowBid(bid: Bid): AuctionState = {
      require(phase != Closed)
      require(isHigherBid(highestBid, bid))
      copy(highestCounterOffer = highestCounterOffer.max(bid.offer)) // update highest counter offer
    }

    def isHigherBid(first: Bid, second: Bid): Boolean =
      first.offer > second.offer ||
      (first.offer == second.offer && first.timestamp.isBefore(second.timestamp)) || // if equal, first one wins
      // If timestamps are equal, choose by dc where the offer was submitted
      // In real auctions, this last comparison should be deterministic but unpredictable, so that submitting to a
      // particular DC would not be an advantage.
      (first.offer == second.offer && first.timestamp.equals(second.timestamp) && first.originReplica.id
        .compareTo(second.originReplica.id) < 0)
  }
  //#state

  //#setup
  case class AuctionSetup(
      name: String,
      initialBid: Bid, // the initial bid is basically the minimum price bidden at start time by the owner
      closingAt: Instant,
      responsibleForClosing: Boolean,
      allReplicas: Set[ReplicaId])
  //#setup

  //#command-handler
  def commandHandler(setup: AuctionSetup, ctx: ActorContext[AuctionCommand], aaContext: ReplicationContext)(
      state: AuctionState,
      command: AuctionCommand): Effect[AuctionEvent, AuctionState] = {
    state.phase match {
      case Closing(_) | Closed =>
        command match {
          case GetHighestBid(replyTo) =>
            replyTo ! state.highestBid.copy(offer = state.highestCounterOffer) // TODO this is not as described
            Effect.none
          case IsClosed(replyTo) =>
            replyTo ! (state.phase == Closed)
            Effect.none
          case Finish =>
            ctx.log.info("Finish")
            Effect.persist(AuctionFinished(aaContext.replicaId))
          case Close =>
            ctx.log.info("Close")
            require(shouldClose(setup, state))
            // TODO send email (before or after persisting)
            Effect.persist(WinnerDecided(aaContext.replicaId, state.highestBid, state.highestCounterOffer))
          case _: OfferBid =>
            // auction finished, no more bids accepted
            Effect.unhandled
        }
      case Running =>
        command match {
          case OfferBid(bidder, offer) =>
            Effect.persist(
              BidRegistered(
                Bid(bidder, offer, Instant.ofEpochMilli(aaContext.currentTimeMillis()), aaContext.replicaId)))
          case GetHighestBid(replyTo) =>
            replyTo ! state.highestBid
            Effect.none
          case Finish =>
            Effect.persist(AuctionFinished(aaContext.replicaId))
          case Close =>
            ctx.log.warn("Premature close")
            // Close should only be triggered when we have already finished
            Effect.unhandled
          case IsClosed(replyTo) =>
            replyTo ! false
            Effect.none
        }
    }
  }
  //#command-handler

  private def shouldClose(auctionSetup: AuctionSetup, state: AuctionState): Boolean = {
    auctionSetup.responsibleForClosing && (state.phase match {
      case Closing(alreadyFinishedAtDc) =>
        val allDone = auctionSetup.allReplicas.diff(alreadyFinishedAtDc).isEmpty
        if (!allDone) {
          println(
            s"Not closing auction as not all DCs have reported finished. All DCs: ${auctionSetup.allReplicas}. Reported finished ${alreadyFinishedAtDc}")
        }
        allDone
      case _ =>
        false
    })
  }

  //#event-handler
  def eventHandler(ctx: ActorContext[AuctionCommand], aaCtx: ReplicationContext, setup: AuctionSetup)(
      state: AuctionState,
      event: AuctionEvent): AuctionState = {

    val newState = state.applyEvent(event)
    ctx.log.infoN("Applying event {}. New start {}", event, newState)
    if (!aaCtx.recoveryRunning) {
      eventTriggers(setup, ctx, aaCtx, event, newState)
    }
    newState

  }
  //#event-handler

  //#event-triggers
  private def eventTriggers(
      setup: AuctionSetup,
      ctx: ActorContext[AuctionCommand],
      aaCtx: ReplicationContext,
      event: AuctionEvent,
      newState: AuctionState) = {
    event match {
      case finished: AuctionFinished =>
        newState.phase match {
          case Closing(alreadyFinishedAtDc) =>
            ctx.log.infoN(
              "AuctionFinished at {}, already finished at [{}]",
              finished.atReplica,
              alreadyFinishedAtDc.mkString(", "))
            if (alreadyFinishedAtDc(aaCtx.replicaId)) {
              if (shouldClose(setup, newState)) ctx.self ! Close
            } else {
              ctx.log.info("Sending finish to self")
              ctx.self ! Finish
            }

          case _ => // no trigger for this state
        }
      case _ => // no trigger for this event
    }
  }
  //#event-triggers

  def initialState(setup: AuctionSetup) =
    AuctionState(phase = Running, highestBid = setup.initialBid, highestCounterOffer = setup.initialBid.offer)

  def behavior(replica: ReplicaId, setup: AuctionSetup): Behavior[AuctionCommand] = Behaviors.setup[AuctionCommand] {
    ctx =>
      ReplicatedEventSourcing
        .withSharedJournal(setup.name, replica, setup.allReplicas, PersistenceTestKitReadJournal.Identifier) { aaCtx =>
          EventSourcedBehavior(
            aaCtx.persistenceId,
            initialState(setup),
            commandHandler(setup, ctx, aaCtx),
            eventHandler(ctx, aaCtx, setup))
        }
  }
}

class ReplicatedAuctionExampleSpec
    extends ScalaTestWithActorTestKit(PersistenceTestKitPlugin.config)
    with AnyWordSpecLike
    with Matchers
    with LogCapturing
    with ScalaFutures
    with Eventually {
  import ReplicatedAuctionExampleSpec._

  "Auction example" should {

    "work" in {
      val Replicas = Set(ReplicaId("DC-A"), ReplicaId("DC-B"))
      val setupA =
        AuctionSetup(
          "old-skis",
          Bid("chbatey", 12, Instant.now(), ReplicaId("DC-A")),
          Instant.now().plusSeconds(10),
          responsibleForClosing = true,
          Replicas)

      val setupB = setupA.copy(responsibleForClosing = false)

      val dcAReplica: ActorRef[AuctionCommand] = spawn(behavior(ReplicaId("DC-A"), setupA))
      val dcBReplica: ActorRef[AuctionCommand] = spawn(behavior(ReplicaId("DC-B"), setupB))

      dcAReplica ! OfferBid("me", 100)
      dcAReplica ! OfferBid("me", 99)
      dcAReplica ! OfferBid("me", 202)

      eventually {
        val replyProbe = createTestProbe[Bid]()
        dcAReplica ! GetHighestBid(replyProbe.ref)
        val bid = replyProbe.expectMessageType[Bid]
        bid.offer shouldEqual 202
      }

      dcAReplica ! Finish
      eventually {
        val finishProbe = createTestProbe[Boolean]()
        dcAReplica ! IsClosed(finishProbe.ref)
        finishProbe.expectMessage(true)
      }
      eventually {
        val finishProbe = createTestProbe[Boolean]()
        dcBReplica ! IsClosed(finishProbe.ref)
        finishProbe.expectMessage(true)
      }
    }
  }
}
