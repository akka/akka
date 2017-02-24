package docs.cluster;

import java.util.HashMap;
import java.util.Map;

import akka.actor.AbstractActor;

//#worker
public class StatsWorker extends AbstractActor {

  Map<String, Integer> cache = new HashMap<String, Integer>();

  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(String.class, word -> {
        Integer length = cache.get(word);
        if (length == null) {
          length = word.length();
          cache.put(word, length);
        }
        sender().tell(length, self());
      })
      .build();
  }
}
//#worker