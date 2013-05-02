/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

//#imports
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.kernel.Bootable;
import com.typesafe.config.ConfigFactory;
//#imports

//#setup
public class JLookupApplication implements Bootable {
  private ActorSystem system;
  private ActorRef actor;

  public JLookupApplication() {
    system = ActorSystem.create("LookupApplication", ConfigFactory.load().getConfig("remotelookup"));
    final String path = "akka.tcp://CalculatorApplication@127.0.0.1:2552/user/simpleCalculator";
    actor = system.actorOf(Props.create(JLookupActor.class, path), "lookupActor");
  }

  public void doSomething(Op.MathOp mathOp) {
    actor.tell(mathOp, null);
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
