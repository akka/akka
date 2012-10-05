package sample.cluster.stats.japi;

import java.util.HashMap;
import java.util.Map;

import akka.actor.UntypedActor;

//#worker
public class StatsWorker extends UntypedActor {

  Map<String, Integer> cache = new HashMap<String, Integer>();

  @Override
  public void onReceive(Object message) {
    if (message instanceof String) {
      String word = (String) message;
      Integer length = cache.get(word);
      if (length == null) {
        length = word.length();
        cache.put(word, length);
      }
      getSender().tell(length, getSelf());

    } else {
      unhandled(message);
    }
  }

}
//#worker