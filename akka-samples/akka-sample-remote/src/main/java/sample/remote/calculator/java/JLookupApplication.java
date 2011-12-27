/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.kernel.Bootable;
import com.typesafe.config.ConfigFactory;

public class JLookupApplication implements Bootable {
    private ActorSystem system;
    private ActorRef actor;
    private ActorRef remoteActor;

    public JLookupApplication() {
        system = ActorSystem.create("LookupApplication", ConfigFactory.load().getConfig("remotelookup"));
        actor = system.actorOf(new Props().withCreator(JLookupActor.class));
        remoteActor = system.actorFor("akka://CalculatorApplication@127.0.0.1:2552/user/simpleCalculator");
    }

    public void doSomething(Op.MathOp mathOp) {
        actor.tell(new InternalMsg.MathOpMsg(remoteActor, mathOp));
    }

    @Override
    public void startup() {
    }

    @Override
    public void shutdown() {
        system.shutdown();
    }
}
