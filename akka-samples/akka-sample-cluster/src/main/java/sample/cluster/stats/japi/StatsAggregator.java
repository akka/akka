package sample.cluster.stats.japi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import sample.cluster.stats.japi.StatsMessages.JobFailed;
import sample.cluster.stats.japi.StatsMessages.StatsResult;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.ReceiveTimeout;
import akka.actor.UntypedActor;

//#aggregator
public class StatsAggregator extends UntypedActor {

  final int expectedResults;
  final ActorRef replyTo;
  final List<Integer> results = new ArrayList<Integer>();

  public StatsAggregator(int expectedResults, ActorRef replyTo) {
    this.expectedResults = expectedResults;
    this.replyTo = replyTo;
  }

  @Override
  public void preStart() {
    getContext().setReceiveTimeout(Duration.create(5, TimeUnit.SECONDS));
  }

  @Override
  public void onReceive(Object message) {
    if (message instanceof Integer) {
      Integer wordCount = (Integer) message;
      results.add(wordCount);
      if (results.size() == expectedResults) {
        int sum = 0;
        for (int c : results)
          sum += c;
        double meanWordLength = ((double) sum) / results.size();
        replyTo.tell(new StatsResult(meanWordLength), getSelf());
        getContext().stop(getSelf());
      }

    } else if (message == ReceiveTimeout.getInstance()) {
      replyTo.tell(new JobFailed("Service unavailable, try again later"),
          getSelf());
      getContext().stop(getSelf());

    } else {
      unhandled(message);
    }
  }

}
//#aggregator
