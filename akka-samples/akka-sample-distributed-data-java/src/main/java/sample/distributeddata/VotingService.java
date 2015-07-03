package sample.distributeddata;

import java.util.Optional;
import java.util.HashMap;
import java.math.BigInteger;
import java.util.Map;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import scala.concurrent.duration.Duration;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.cluster.Cluster;
import akka.cluster.ddata.*;
import akka.japi.pf.ReceiveBuilder;

import static akka.cluster.ddata.Replicator.*;
import static java.util.concurrent.TimeUnit.SECONDS;

@SuppressWarnings("unchecked")
public class VotingService extends AbstractActor {

  public static final String OPEN = "open";
  public static final String CLOSE = "close";
  public static final String GET_VOTES = "getVotes";

  public static class Votes {
    public final Map<String, BigInteger> result;
    public final boolean open;

    public Votes(Map<String, BigInteger> result, boolean open) {
      this.result = result;
      this.open = open;
    }
  }

  public static class Vote {
    public final String participant;

    public Vote(String participant) {
      this.participant = participant;
    }
  }

  private final ActorRef replicator = DistributedData.get(context().system()).replicator();
  private final Cluster node = Cluster.get(context().system());

  private final Key<Flag> openedKey = FlagKey.create("contestOpened");
  private final Key<Flag> closedKey = FlagKey.create("contestClosed");
  private final Key<PNCounterMap> countersKey = PNCounterMapKey.create("contestCounters");
  private final WriteConsistency writeAll = new WriteAll(Duration.create(5, SECONDS));
  private final ReadConsistency readAll = new ReadAll(Duration.create(3, SECONDS));

  @Override
  public void preStart() {
    replicator.tell(new Subscribe<>(openedKey, self()), ActorRef.noSender());
  }

  public VotingService() {
    receive(ReceiveBuilder
      .matchEquals(OPEN, cmd -> receiveOpen())
      .match(Changed.class, c -> c.key().equals(openedKey), c -> receiveOpenedChanged((Changed<Flag>) c))
      .matchEquals(GET_VOTES, cmd -> receiveGetVotesEmpty())
      .build());
  }


  private void receiveOpen() {
    Update<Flag> update = new Update<>(openedKey, Flag.create(), writeAll, curr -> curr.switchOn());
    replicator.tell(update, self());
    becomeOpen();
  }

  private void becomeOpen() {
    replicator.tell(new Unsubscribe<>(openedKey, self()), ActorRef.noSender());
    replicator.tell(new Subscribe<>(closedKey, self()), ActorRef.noSender());
    context().become(matchOpen().orElse(matchGetVotes(true)));
  }

  private void receiveOpenedChanged(Changed<Flag> c) {
    if (c.dataValue().enabled())
      becomeOpen();
  }

  private void receiveGetVotesEmpty() {
    sender().tell(new Votes(new HashMap<>(), false), self());
  }

  private PartialFunction<Object, BoxedUnit> matchOpen() {
    return ReceiveBuilder
      .match(Vote.class, vote -> receiveVote(vote))
      .match(UpdateSuccess.class, u -> receiveUpdateSuccess())
      .matchEquals(CLOSE, cmd -> receiveClose())
      .match(Changed.class, c -> c.key().equals(closedKey), c -> receiveClosedChanged((Changed<Flag>) c))
      .build();
  }

  private void receiveVote(Vote vote) {
    Update<PNCounterMap> update = new Update<>(countersKey, PNCounterMap.create(), Replicator.writeLocal(),
        curr -> curr.increment(node, vote.participant, 1));
    replicator.tell(update, self());
  }

  private void receiveUpdateSuccess() {
    // ok
  }

  private void receiveClose() {
    Update<Flag> update = new Update<>(closedKey, Flag.create(), writeAll, curr -> curr.switchOn());
    replicator.tell(update, self());
    context().become(matchGetVotes(false));
  }

  private void receiveClosedChanged(Changed<Flag> c) {
    if (c.dataValue().enabled())
      context().become(matchGetVotes(false));
  }

  private PartialFunction<Object, BoxedUnit> matchGetVotes(boolean open) {
    return ReceiveBuilder
      .matchEquals(GET_VOTES, s -> receiveGetVotes())
      .match(NotFound.class, n -> n.key().equals(countersKey), n -> receiveNotFound(open, (NotFound<PNCounterMap>) n))
      .match(GetSuccess.class, g -> g.key().equals(countersKey),
          g -> receiveGetSuccess(open, (GetSuccess<PNCounterMap>) g))
      .match(GetFailure.class, f -> f.key().equals(countersKey), f -> receiveGetFailure())
      .match(UpdateSuccess.class, u -> receiveUpdateSuccess()).build();
  }

  private void receiveGetVotes() {
    Optional<Object> ctx = Optional.of(sender());
    replicator.tell(new Replicator.Get<PNCounterMap>(countersKey, readAll, ctx), self());
  }


  private void receiveGetSuccess(boolean open, GetSuccess<PNCounterMap> g) {
    Map<String, BigInteger> result = g.dataValue().getEntries();
    ActorRef replyTo = (ActorRef) g.getRequest().get();
    replyTo.tell(new Votes(result, open), self());
  }

  private void receiveNotFound(boolean open, NotFound<PNCounterMap> n) {
    ActorRef replyTo = (ActorRef) n.getRequest().get();
    replyTo.tell(new Votes(new HashMap<>(), open), self());
  }

  private void receiveGetFailure() {
    // skip
  }
}