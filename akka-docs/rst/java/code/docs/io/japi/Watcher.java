package docs.io.japi;

import java.util.concurrent.CountDownLatch;

import akka.actor.ActorRef;
import akka.actor.Terminated;
import akka.actor.UntypedActor;

public class Watcher extends UntypedActor {
  
  static public class Watch {
    final ActorRef target;
    public Watch(ActorRef target) {
      this.target = target;
    }
  }
  
  final CountDownLatch latch;

  public Watcher(CountDownLatch latch) {
    this.latch = latch;
  }

  @Override
  public void onReceive(Object msg) throws Exception {
    if (msg instanceof Watch) {
      getContext().watch(((Watch) msg).target);
    } else if (msg instanceof Terminated) {
      latch.countDown();
      if (latch.getCount() == 0) getContext().stop(getSelf());
    }
  }

}
