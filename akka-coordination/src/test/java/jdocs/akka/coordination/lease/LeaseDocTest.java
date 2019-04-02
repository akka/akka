/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.coordination.lease;

import akka.actor.ActorSystem;
import akka.cluster.Cluster;
import akka.coordination.lease.LeaseSettings;
import akka.coordination.lease.javadsl.Lease;
import akka.coordination.lease.javadsl.LeaseProvider;
import akka.testkit.javadsl.TestKit;
import docs.akka.coordination.LeaseDocSpec;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

@SuppressWarnings("unused")
public class LeaseDocTest {
  // #lease-example
  static class SampleLease extends Lease {

    private LeaseSettings settings;

    public SampleLease(LeaseSettings settings) {
      this.settings = settings;
    }

    @Override
    public LeaseSettings getSettings() {
      return settings;
    }

    @Override
    public CompletionStage<Boolean> acquire() {
      return null;
    }

    @Override
    public CompletionStage<Boolean> acquire(Consumer<Optional<Throwable>> leaseLostCallback) {
      return null;
    }

    @Override
    public CompletionStage<Boolean> release() {
      return null;
    }

    @Override
    public boolean checkLease() {
      return false;
    }
  }
  // #lease-example

  private static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("LeaseDocTest", LeaseDocSpec.config());
  }

  @AfterClass
  public static void teardown() {
    TestKit.shutdownActorSystem(system);
    system = null;
  }

  private void doSomethingImportant(Optional<Throwable> leaseLostReason) {}

  @Test
  public void beLoadable() {
    // #lease-usage
    Lease lease =
        LeaseProvider.get(system).getLease("<name of the lease>", "docs-lease", "<owner name>");
    CompletionStage<Boolean> acquired = lease.acquire();
    boolean stillAcquired = lease.checkLease();
    CompletionStage<Boolean> released = lease.release();
    // #lease-usage

    // #lost-callback
    lease.acquire(this::doSomethingImportant);
    // #lost-callback

    // #cluster-owner
    String owner = Cluster.get(system).selfAddress().hostPort();
    // #cluster-owner

  }
}
