/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

import akka.actor.ActorRef;

public class InternalMsg {
   static class MathOpMsg {
       private final ActorRef actor;
       private final Op.MathOp mathOp;

       MathOpMsg(ActorRef actor, Op.MathOp mathOp) {
           this.actor = actor;
           this.mathOp = mathOp;
       }

       public ActorRef getActor() {
           return actor;
       }

       public Op.MathOp getMathOp() {
           return mathOp;
       }
   }
}
