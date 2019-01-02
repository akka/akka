/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed.auction;

import akka.Done;
import akka.persistence.typed.ExpectingReply;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.CommandHandlerBuilder;
import akka.persistence.typed.javadsl.Effect;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.EventSourcedBehavior;

import static jdocs.akka.persistence.typed.auction.AuctionCommand.*;
import static jdocs.akka.persistence.typed.auction.AuctionEvent.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Based on https://github.com/lagom/online-auction-java/blob/master/bidding-impl/src/main/java/com/example/auction/bidding/impl/AuctionEntity.java
 */
public class AuctionEntity extends EventSourcedBehavior<AuctionCommand, AuctionEvent, AuctionState> {

  private final UUID entityUUID;

  public AuctionEntity(String entityId) {
    // when used with Cluster Sharding this should use EntityTypeKey, or PersistentEntity
    super(new PersistenceId("Auction|" + entityId));
    this.entityUUID = UUID.fromString(entityId);
  }

  // Command handler for the not started state.
  private CommandHandlerBuilder<AuctionCommand, AuctionEvent, AuctionState, AuctionState> notStartedHandler =
    commandHandlerBuilder(state -> state.getStatus() == AuctionStatus.NOT_STARTED)
      .matchCommand(StartAuction.class, this::startAuction)
      .matchCommand(PlaceBid.class, (state, cmd) -> Effect().reply(cmd, createResult(state, PlaceBidStatus.NOT_STARTED)));

  // Command handler for the under auction state.
  private CommandHandlerBuilder<AuctionCommand, AuctionEvent, AuctionState, AuctionState> underAuctionHandler =
    commandHandlerBuilder(state -> state.getStatus() == AuctionStatus.UNDER_AUCTION)
      .matchCommand(StartAuction.class, (state, cmd) -> alreadyDone(cmd))
      .matchCommand(PlaceBid.class, this::placeBid)
      .matchCommand(FinishBidding.class, this::finishBidding);

  // Command handler for the completed state.
  private CommandHandlerBuilder<AuctionCommand, AuctionEvent, AuctionState, AuctionState> completedHandler =
    commandHandlerBuilder(state -> state.getStatus() == AuctionStatus.COMPLETE)
      .matchCommand(StartAuction.class, (state, cmd) -> alreadyDone(cmd))
      .matchCommand(FinishBidding.class, (state, cmd) -> alreadyDone(cmd))
      .matchCommand(PlaceBid.class, (state, cmd) -> Effect().reply(cmd, createResult(state, PlaceBidStatus.FINISHED)));

  // Command handler for the cancelled state.
  private CommandHandlerBuilder<AuctionCommand, AuctionEvent, AuctionState, AuctionState> cancelledHandler =
    commandHandlerBuilder(state -> state.getStatus() == AuctionStatus.CANCELLED)
      .matchCommand(StartAuction.class, (state, cmd) -> alreadyDone(cmd))
      .matchCommand(FinishBidding.class, (state, cmd) -> alreadyDone(cmd))
      .matchCommand(CancelAuction.class, (state, cmd) -> alreadyDone(cmd))
      .matchCommand(PlaceBid.class, (state, cmd) -> Effect().reply(cmd, createResult(state, PlaceBidStatus.CANCELLED)));

  private CommandHandlerBuilder<AuctionCommand, AuctionEvent, AuctionState, AuctionState> getAuctionHandler =
    commandHandlerBuilder(AuctionState.class)
      .matchCommand(GetAuction.class, (state, cmd) -> Effect().reply(cmd, state));

  private CommandHandlerBuilder<AuctionCommand, AuctionEvent, AuctionState, AuctionState> cancelHandler =
    commandHandlerBuilder(AuctionState.class)
      .matchCommand(CancelAuction.class, this::cancelAuction);
  // Note, an item can go from completed to cancelled, since it is the item service that controls
  // whether an auction is cancelled or not. If it cancels before it receives a bidding finished
  // event from us, it will ignore the bidding finished event, so we need to update our state
  // to reflect that.


  private Effect<AuctionEvent, AuctionState> startAuction(AuctionState state, StartAuction cmd) {
    return Effect().persist(new AuctionStarted(entityUUID, cmd.getAuction()))
      .thenReply(cmd, __ -> Done.getInstance());
  }

  private Effect<AuctionEvent, AuctionState> finishBidding(AuctionState state, FinishBidding cmd) {
    return Effect().persist(new BiddingFinished(entityUUID))
      .thenReply(cmd, __ -> Done.getInstance());
  }

  private Effect<AuctionEvent, AuctionState> cancelAuction(AuctionState state, CancelAuction cmd) {
    return Effect().persist(new AuctionCancelled(entityUUID))
      .thenReply(cmd, __ -> Done.getInstance());
  }

  /**
   * The main logic for handling of bids.
   */
  private Effect<AuctionEvent, AuctionState> placeBid(AuctionState state, PlaceBid bid) {
    Auction auction = state.getAuction().get();

    Instant now = Instant.now();

    // Even though we're not in the finished state yet, we should check
    if (auction.getEndTime().isBefore(now)) {
      return Effect().reply(bid, createResult(state, PlaceBidStatus.FINISHED));
    }

    if (auction.getCreator().equals(bid.getBidder())) {
      return Effect().reply(bid, new PlaceBidRejected("An auctions creator cannot bid in their own auction."));
    }

    Optional<Bid> currentBid = state.lastBid();
    int currentBidPrice;
    int currentBidMaximum;
    if (currentBid.isPresent()) {
      currentBidPrice = currentBid.get().getBidPrice();
      currentBidMaximum = currentBid.get().getMaximumBid();
    } else {
      currentBidPrice = 0;
      currentBidMaximum = 0;
    }

    boolean bidderIsCurrentBidder = currentBid.filter(b -> b.getBidder().equals(bid.getBidder())).isPresent();

    if (bidderIsCurrentBidder && bid.getBidPrice() >= currentBidPrice) {
      // Allow the current bidder to update their bid
      if (auction.getReservePrice()>currentBidPrice) {

        int newBidPrice = Math.min(auction.getReservePrice(), bid.getBidPrice());
        PlaceBidStatus placeBidStatus;

        if (newBidPrice == auction.getReservePrice()) {
          placeBidStatus = PlaceBidStatus.ACCEPTED;
        }
        else {
          placeBidStatus = PlaceBidStatus.ACCEPTED_BELOW_RESERVE;
        }
        return Effect().persist(new BidPlaced(entityUUID,
          new Bid(bid.getBidder(), now, newBidPrice, bid.getBidPrice())))
            .thenReply(bid, newState -> new PlaceBidResult(placeBidStatus, newBidPrice, bid.getBidder()));
      }
      return Effect().persist(new BidPlaced(entityUUID,
          new Bid(bid.getBidder(), now, currentBidPrice, bid.getBidPrice())))
          .thenReply(bid, newState -> new PlaceBidResult(PlaceBidStatus.ACCEPTED, currentBidPrice, bid.getBidder()));
    }

    if (bid.getBidPrice() < currentBidPrice + auction.getIncrement()) {
      return Effect().reply(bid, createResult(state, PlaceBidStatus.TOO_LOW));
    } else if (bid.getBidPrice() <= currentBidMaximum) {
      return handleAutomaticOutbid(bid, auction, now, currentBid, currentBidPrice, currentBidMaximum);
    } else {
      return handleNewWinningBidder(bid, auction, now, currentBidMaximum);
    }
  }

  /**
   * Handle the situation where a bid will be accepted, but it will be automatically outbid by the current bidder.
   *
   * This emits two events, one for the bid currently being replace, and another automatic bid for the current bidder.
   */
  private Effect<AuctionEvent, AuctionState> handleAutomaticOutbid(
      PlaceBid bid, Auction auction, Instant now, Optional<Bid> currentBid, int currentBidPrice, int currentBidMaximum) {
    // Adjust the bid so that the increment for the current maximum makes the current maximum a valid bid
    int adjustedBidPrice = Math.min(bid.getBidPrice(), currentBidMaximum - auction.getIncrement());
    int newBidPrice = adjustedBidPrice + auction.getIncrement();

    return Effect().persist(Arrays.asList(
        new BidPlaced(entityUUID,
            new Bid(bid.getBidder(), now, adjustedBidPrice, bid.getBidPrice())
        ),
        new BidPlaced(entityUUID,
            new Bid(currentBid.get().getBidder(), now, newBidPrice, currentBidMaximum)
        )
      ))
      .thenReply(bid, newState -> new PlaceBidResult(PlaceBidStatus.ACCEPTED_OUTBID, newBidPrice, currentBid.get().getBidder()));
  }

  /**
   * Handle the situation where a bid will be accepted as the new winning bidder.
   */
  private Effect<AuctionEvent, AuctionState> handleNewWinningBidder(PlaceBid bid,
                                                       Auction auction, Instant now, int currentBidMaximum) {
    int nextIncrement = Math.min(currentBidMaximum + auction.getIncrement(), bid.getBidPrice());
    int newBidPrice;
    if (nextIncrement < auction.getReservePrice()) {
      newBidPrice = Math.min(auction.getReservePrice(), bid.getBidPrice());
    } else {
      newBidPrice = nextIncrement;
    }
    return Effect().persist(new BidPlaced(
        entityUUID,
        new Bid(bid.getBidder(), now, newBidPrice, bid.getBidPrice())
      ))
      .thenReply(bid, newState -> {
        PlaceBidStatus status;
        if (newBidPrice < auction.getReservePrice()) {
          status = PlaceBidStatus.ACCEPTED_BELOW_RESERVE;
        } else {
          status = PlaceBidStatus.ACCEPTED;
        }
        return new PlaceBidResult(status, newBidPrice, bid.getBidder());
      });
  }

  @Override
  public AuctionState emptyState() {
    return AuctionState.notStarted();
  }

  @Override
  public CommandHandler<AuctionCommand, AuctionEvent, AuctionState> commandHandler() {
    return notStartedHandler
        .orElse(underAuctionHandler)
        .orElse(completedHandler)
        .orElse(getAuctionHandler)
        .orElse(cancelledHandler)
        .build();
  }

  @Override
  public EventHandler<AuctionState, AuctionEvent> eventHandler() {
    return eventHandlerBuilder()
        .matchEvent(AuctionStarted.class, (state, evt) -> AuctionState.start(evt.getAuction()))
        .matchEvent(BidPlaced.class, (state, evt) -> state.bid(evt.getBid()))
        .matchEvent(BiddingFinished.class, (state, evt) -> state.withStatus(AuctionStatus.COMPLETE))
        .matchEvent(AuctionCancelled.class, (state, evt) -> state.withStatus(AuctionStatus.CANCELLED))
        .build();
  }

  private PlaceBidResult createResult(AuctionState state, PlaceBidStatus status) {
    Optional<Bid> lastBid = state.lastBid();
    if (lastBid.isPresent()) {
      Bid bid = lastBid.get();
      return new PlaceBidResult(status, bid.getBidPrice(), bid.getBidder());
    } else {
      return new PlaceBidResult(status, 0, null);
    }
  }

  private Effect<AuctionEvent, AuctionState> alreadyDone(ExpectingReply<Done> cmd) {
    return Effect().reply(cmd, Done.getInstance());
  }
}
