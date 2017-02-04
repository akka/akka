package sample.cluster.stats;

import sample.cluster.stats.StatsMessages.StatsJob;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.AbstractActor;
import akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope;
import akka.routing.FromConfig;

//#service
public class StatsService extends AbstractActor {

  // This router is used both with lookup and deploy of routees. If you
  // have a router with only lookup of routees you can use Props.empty()
  // instead of Props.create(StatsWorker.class).
  ActorRef workerRouter = getContext().actorOf(
      FromConfig.getInstance().props(Props.create(StatsWorker.class)),
      "workerRouter");

  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(StatsJob.class, job -> !"".equals(job.getText()), job -> {
        final String[] words = job.getText().split(" ");
        final ActorRef replyTo = sender();

        // create actor that collects replies from workers
        ActorRef aggregator = getContext().actorOf(
          Props.create(StatsAggregator.class, words.length, replyTo));

        // send each word to a worker
        for (String word : words) {
          workerRouter.tell(new ConsistentHashableEnvelope(word, word),
            aggregator);
        }
      })
      .build();
  }
}

//#service

