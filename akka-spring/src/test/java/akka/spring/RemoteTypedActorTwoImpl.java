package akka.spring;

import akka.actor.*;

import scala.Option;

import java.util.concurrent.CountDownLatch;

public class RemoteTypedActorTwoImpl extends TypedActor implements RemoteTypedActorTwo {

    public static CountDownLatch latch = new CountDownLatch(1);

    public String requestReply(String s) throws Exception {
      if (s.equals("ping")) {
        RemoteTypedActorLog.messageLog().put("ping");
        return "pong";
      } else if (s.equals("die")) {
        throw new RuntimeException("Expected exception; to test fault-tolerance");
      } else return null;
    }

    public void oneWay() throws Exception {
      RemoteTypedActorLog.oneWayLog().put("oneway");
    }

    @Override
    public void preRestart(Throwable e, Option<Object> msg) {
      try { RemoteTypedActorLog.messageLog().put(e.getMessage()); } catch(Exception ex) {}
      latch.countDown();
    }
}
