/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

//#imports
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.kernel.Bootable;
import com.typesafe.config.ConfigFactory;
//#imports

//#setup
public class JLookupApplication implements Bootable {
  private ActorSystem system;
  private ActorRef actor;
  private ActorRef remoteActor;

  public JLookupApplication() {
    system = ActorSystem.create("LookupApplication", ConfigFactory.load()
        .getConfig("remotelookup"));
    actor = system.actorOf(new Props(JLookupActor.class));
    remoteActor = system
        .actorFor("akka://CalculatorApplication@127.0.0.1:2552/user/simpleCalculator");
  }

  public void doSomething(Op.MathOp mathOp) {
    actor.tell(new InternalMsg.MathOpMsg(remoteActor, mathOp), null);
  }

  @Override
  public void startup() {
  }

  @Override
  public void shutdown() {
    system.shutdown();
  }
}
// #setup
