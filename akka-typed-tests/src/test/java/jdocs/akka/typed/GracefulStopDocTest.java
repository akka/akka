/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.akka.typed;

//#imports
import akka.typed.ActorSystem;
import akka.typed.Behavior;
import akka.typed.PostStop;
import akka.typed.javadsl.Actor;
//#imports
import java.util.concurrent.TimeUnit;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

public class GracefulStopDocTest {

    //#master-actor
    public abstract static class JobControl {
        // no instances of this class, it's only a name space for messages
        // and static methods
        private JobControl() {
            // no instances of this class, it's only a name space for messages
            // and static methods
        }

        static interface JobControlLanguage {}

        public static final class SpawnJob implements JobControlLanguage {
            public final String name;

            public SpawnJob(String name) {
                this.name = name;
            }
        }

        public static final class GracefulShutdown implements JobControlLanguage {

            public GracefulShutdown() {}
        }

        public static final Behavior<JobControlLanguage> mcpa = Actor.immutable(JobControlLanguage.class)
                .onMessage(SpawnJob.class, (ctx, msg) -> {
                    ctx.getSystem().log().info("Spawning job {}!", msg.name);
                    ctx.spawn(Job.job(msg.name), msg.name);
                    return Actor.same();
                })
                .onSignal(PostStop.class, (ctx, signal) -> {
                    ctx.getSystem().log().info("Master Control Programme stopped");
                    return Actor.same();
                })
                .onMessage(GracefulShutdown.class, (ctx, msg) -> {
                    ctx.getSystem().log().info("Initiating graceful shutdown...");

                    // perform graceful stop, executing cleanup before final system termination
                    // behavior executing cleanup is passed as a parameter to Actor.stopped
                    return Actor.stopped (Actor.onSignal((context, PostStop) -> {
                            context.getSystem().log().info("Cleanup!");
                            return Actor.same();
                }));
                })
                .onSignal(PostStop.class, (ctx, signal) -> {
                    ctx.getSystem().log().info("Master Control Programme stopped");
                    return Actor.same();
                })
                .build();
    }
    //#master-actor

    public static void main(String[] args) throws Exception{
        //#graceful-shutdown
        final ActorSystem<JobControl.JobControlLanguage> system =
                ActorSystem.create(JobControl.mcpa, "B6700");

        system.tell(new JobControl.SpawnJob("a"));
        system.tell(new JobControl.SpawnJob("b"));

        // sleep here to allow time for the new actors to be started
        Thread.sleep(100);

        system.tell(new JobControl.GracefulShutdown());

        Await.result(system.whenTerminated(), Duration.create(3, TimeUnit.SECONDS));
        //#graceful-shutdown
    }

    //#worker-actor
    public static class Job {
        public static Behavior<JobControl.JobControlLanguage> job(String name) {
            return Actor.onSignal((ctx, PostStop) -> {
                ctx.getSystem().log().info("Worker {} stopped", name);
                return Actor.same();
            });

        }
    }
    //#worker-actor
}
