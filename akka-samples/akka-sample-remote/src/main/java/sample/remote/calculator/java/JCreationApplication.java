/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.kernel.Bootable;
import com.typesafe.config.ConfigFactory;

//#setup
public class JCreationApplication implements Bootable {
  private ActorSystem system;
  private ActorRef actor;

  public JCreationApplication() {
    system = ActorSystem.create("CreationApplication", ConfigFactory.load()
        .getConfig("remotecreation"));
    final ActorRef remoteActor = system.actorOf(new Props(JAdvancedCalculatorActor.class),
        "advancedCalculator");
    actor = system.actorOf(new Props().withCreator(new UntypedActorFactory() {
      public UntypedActor create() {
        return new JCreationActor(remoteActor);
      }
    }), "creationActor");

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
