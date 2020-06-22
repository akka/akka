/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.persistence.typed.aa

import java.time.Instant

import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, _ }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.query.journal.inmem.scaladsl.InmemReadJournal
import akka.persistence.typed.scaladsl.{ ActiveActiveContext, ActiveActiveEventSourcing, Effect, EventSourcedBehavior }
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

object AAAuctionExampleSpec {
  val config = ConfigFactory.parseString(s"""
       akka.loglevel = info 
       akka.persistence {
         journal {
           plugin = "akka.persistence.journal.inmem"
         }
       }
      """)
  type MoneyAmount = Int

  case class Bid(bidder: String, offer: MoneyAmount, timestamp: Instant, originDc: String)

  // commands
  sealed trait AuctionCommand
  case object Finish extends AuctionCommand // A timer needs to schedule this event at each replica
  final case class OfferBid(bidder: String, offer: MoneyAmount) extends AuctionCommand
  final case class GetHighestBid(replyTo: ActorRef[Bid]) extends AuctionCommand
  final case class IsClosed(replyTo: ActorRef[Boolean]) extends AuctionCommand
  private final case object Close extends AuctionCommand // Internal, should not be sent from the outside

  sealed trait AuctionEvent
  final case class BidRegistered(bid: Bid) extends AuctionEvent
  final case class AuctionFinished(atDc: String) extends AuctionEvent
  final case class WinnerDecided(atDc: String, winningBid: Bid, highestCounterOffer: MoneyAmount) extends AuctionEvent

  sealed trait AuctionPhase
  case object Running extends AuctionPhase
  final case class Closing(finishedAtDc: Set[String]) extends AuctionPhase
  case object Closed extends AuctionPhase

  case class AuctionState(
      phase: AuctionPhase,
      highestBid: Bid,
      highestCounterOffer: MoneyAmount // in ebay style auctions, we need to keep track of current highest counter offer
  ) {

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
      (first.offer == second.offer && first.timestamp.equals(second.timestamp) && first.originDc.compareTo(
        second.originDc) < 0)
  }

  case class AuctionSetup(
      name: String,
      initialBid: Bid, // the initial bid is basically the minimum price bidden at start time by the owner
      closingAt: Instant,
      responsibleForClosing: Boolean,
      allDcs: Set[String])

  def commandHandler(setup: AuctionSetup, ctx: ActorContext[AuctionCommand], aaContext: ActiveActiveContext)(
      state: AuctionState,
      command: AuctionCommand): Effect[AuctionEvent, AuctionState] = {
    state.phase match {
      case Closing(_) | Closed =>
        command match {
          case GetHighestBid(replyTo) =>
            replyTo ! state.highestBid
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

  private def shouldClose(auctionSetup: AuctionSetup, state: AuctionState): Boolean = {
    auctionSetup.responsibleForClosing && (state.phase match {
      case Closing(alreadyFinishedAtDc) =>
        val allDone = auctionSetup.allDcs.diff(alreadyFinishedAtDc).isEmpty
        if (!allDone) {
          println(
            s"Not closing auction as not all DCs have reported finished. All DCs: ${auctionSetup.allDcs}. Reported finished ${alreadyFinishedAtDc}")
        }
        allDone
      case _ =>
        false
    })
  }

  def eventHandler(ctx: ActorContext[AuctionCommand], aaCtx: ActiveActiveContext, setup: AuctionSetup)(
      state: AuctionState,
      event: AuctionEvent): AuctionState = {

    val newState = state.applyEvent(event)
    ctx.log.infoN("Applying event {}. New start {}", event, newState)
    if (!aaCtx.recoveryRunning) {
      eventTriggers(setup, ctx, aaCtx, event, newState)
    }
    newState

  }

  private def eventTriggers(
      setup: AuctionSetup,
      ctx: ActorContext[AuctionCommand],
      aaCtx: ActiveActiveContext,
      event: AuctionEvent,
      newState: AuctionState) = {
    event match {
      case finished: AuctionFinished =>
        newState.phase match {
          case Closing(alreadyFinishedAtDc) =>
            ctx.log.infoN(
              "AuctionFinished at {}, already finished at [{}]",
              finished.atDc,
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

  def initialState(setup: AuctionSetup) =
    AuctionState(phase = Running, highestBid = setup.initialBid, highestCounterOffer = setup.initialBid.offer)

  def behavior(replica: String, setup: AuctionSetup): Behavior[AuctionCommand] = Behaviors.setup[AuctionCommand] {
    ctx =>
      ActiveActiveEventSourcing(setup.name, replica, setup.allDcs, InmemReadJournal.Identifier) { aaCtx =>
        EventSourcedBehavior(
          aaCtx.persistenceId,
          initialState(setup),
          commandHandler(setup, ctx, aaCtx),
          eventHandler(ctx, aaCtx, setup))
      }
  }
}

class AAAuctionExampleSpec
    extends ScalaTestWithActorTestKit(AAAuctionExampleSpec.config)
    with AnyWordSpecLike
    with Matchers
    with LogCapturing
    with ScalaFutures
    with Eventually {
  import AAAuctionExampleSpec._

  "Auction example" should {

    "work" in {
      val Replicas = Set("DC-A", "DC-B")
      val setupA =
        AuctionSetup(
          "old-skis",
          Bid("chbatey", 12, Instant.now(), "DC-A"),
          Instant.now().plusSeconds(10),
          responsibleForClosing = true,
          Replicas)

      val setupB = setupA.copy(responsibleForClosing = false)

      val dcAReplica: ActorRef[AuctionCommand] = spawn(behavior("DC-A", setupA))
      val dcBReplica: ActorRef[AuctionCommand] = spawn(behavior("DC-B", setupB))

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
