/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata;

import akka.cluster.UniqueAddress;

public class JavaImplOfDeltaReplicatedData extends AbstractDeltaReplicatedData<JavaImplOfDeltaReplicatedData> implements
    RemovedNodePruning {

  @Override
  public JavaImplOfDeltaReplicatedData mergeData(JavaImplOfDeltaReplicatedData other) {
    return this;
  }

  @Override
  public JavaImplOfDeltaReplicatedData delta() {
    return this;
  }

  @Override
  public JavaImplOfDeltaReplicatedData resetDelta() {
    return this;
  }

  @Override
  public scala.collection.immutable.Set<UniqueAddress> modifiedByNodes() {
    return akka.japi.Util.immutableSeq(new java.util.ArrayList<UniqueAddress>()).toSet();
  }

  @Override
  public boolean needPruningFrom(UniqueAddress removedNode) {
    return false;
  }

  @Override
  public JavaImplOfDeltaReplicatedData prune(UniqueAddress removedNode, UniqueAddress collapseInto) {
    return this;
  }

  @Override
  public JavaImplOfDeltaReplicatedData pruningCleanup(UniqueAddress removedNode) {
    return this;
  }
}
