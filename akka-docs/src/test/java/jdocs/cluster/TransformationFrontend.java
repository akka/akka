/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.cluster;

import static jdocs.cluster.TransformationMessages.BACKEND_REGISTRATION;

import java.util.ArrayList;
import java.util.List;

import jdocs.cluster.TransformationMessages.JobFailed;
import jdocs.cluster.TransformationMessages.TransformationJob;
import akka.actor.ActorRef;
import akka.actor.Terminated;
import akka.actor.AbstractActor;

// #frontend
public class TransformationFrontend extends AbstractActor {

  List<ActorRef> backends = new ArrayList<ActorRef>();
  int jobCounter = 0;

  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .match(
            TransformationJob.class,
            job -> backends.isEmpty(),
            job -> {
              getSender()
                  .tell(new JobFailed("Service unavailable, try again later", job), getSender());
            })
        .match(
            TransformationJob.class,
            job -> {
              jobCounter++;
              backends.get(jobCounter % backends.size()).forward(job, getContext());
            })
        .matchEquals(
            BACKEND_REGISTRATION,
            x -> {
              getContext().watch(getSender());
              backends.add(getSender());
            })
        .match(
            Terminated.class,
            terminated -> {
              backends.remove(terminated.getActor());
            })
        .build();
  }
}
// #frontend
