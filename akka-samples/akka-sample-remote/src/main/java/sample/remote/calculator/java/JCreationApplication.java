/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.kernel.Bootable;
import com.typesafe.config.ConfigFactory;

//#setup
public class JCreationApplication implements Bootable {
  private ActorSystem system;
  private ActorRef actor;

  public JCreationApplication() {
    system = ActorSystem.create("CreationApplication", ConfigFactory.load()
        .getConfig("remotecreation"));
    final ActorRef remoteActor = system.actorOf(Props.create(
      JAdvancedCalculatorActor.class), "advancedCalculator");
    actor = system.actorOf(Props.create(JCreationActor.class, remoteActor), 
      "creationActor");

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
//#setup
