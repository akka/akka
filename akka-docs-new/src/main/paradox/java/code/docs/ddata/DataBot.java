/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.ddata;

//#data-bot
import static java.util.concurrent.TimeUnit.SECONDS;
import scala.concurrent.duration.Duration;
import java.util.concurrent.ThreadLocalRandom;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.cluster.Cluster;
import akka.cluster.ddata.DistributedData;
import akka.cluster.ddata.Key;
import akka.cluster.ddata.ORSet;
import akka.cluster.ddata.ORSetKey;
import akka.cluster.ddata.Replicator;
import akka.cluster.ddata.Replicator.Changed;
import akka.cluster.ddata.Replicator.Subscribe;
import akka.cluster.ddata.Replicator.Update;
import akka.cluster.ddata.Replicator.UpdateResponse;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.pf.ReceiveBuilder;

public class DataBot extends AbstractActor {
  
  private static final String TICK = "tick";
  
  private final LoggingAdapter log = Logging.getLogger(context().system(), this);

  private final ActorRef replicator = 
      DistributedData.get(context().system()).replicator();
  private final Cluster node = Cluster.get(context().system());

  private final Cancellable tickTask = context().system().scheduler().schedule(
      Duration.create(5, SECONDS), Duration.create(5, SECONDS), self(), TICK,
      context().dispatcher(), self());

  private final Key<ORSet<String>> dataKey = ORSetKey.create("key");
  
  @SuppressWarnings("unchecked")
  public DataBot() {
    receive(ReceiveBuilder
      .match(String.class, a -> a.equals(TICK), a -> receiveTick())
      .match(Changed.class, c -> c.key().equals(dataKey), c -> receiveChanged((Changed<ORSet<String>>) c))
      .match(UpdateResponse.class, r -> receiveUpdateResoponse())
      .build());
  }


  private void receiveTick() {
    String s = String.valueOf((char) ThreadLocalRandom.current().nextInt(97, 123));
    if (ThreadLocalRandom.current().nextBoolean()) {
      // add
      log.info("Adding: {}", s);
      Update<ORSet<String>> update = new Update<>(
          dataKey, 
          ORSet.create(), 
          Replicator.writeLocal(), 
          curr ->  curr.add(node, s));
       replicator.tell(update, self());
    } else {
      // remove
      log.info("Removing: {}", s);
      Update<ORSet<String>> update = new Update<>(
          dataKey, 
          ORSet.create(), 
          Replicator.writeLocal(), 
          curr ->  curr.remove(node, s));
      replicator.tell(update, self());
    }
  }


  private void receiveChanged(Changed<ORSet<String>> c) {
    ORSet<String> data = c.dataValue();
    log.info("Current elements: {}", data.getElements());
  }
  
  private void receiveUpdateResoponse() {
    // ignore
  }

  
  @Override
  public void preStart() {
    Subscribe<ORSet<String>> subscribe = new Subscribe<>(dataKey, self());
    replicator.tell(subscribe, ActorRef.noSender());
  }

  @Override 
  public void postStop(){
    tickTask.cancel();
  }

}
//#data-bot
