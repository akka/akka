/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata;

import akka.cluster.UniqueAddress;

public class JavaImplOfReplicatedData extends AbstractReplicatedData<JavaImplOfReplicatedData> implements
    RemovedNodePruning {

  @Override
  public JavaImplOfReplicatedData mergeData(JavaImplOfReplicatedData other) {
    return this;
  }

  @Override
  public boolean needPruningFrom(UniqueAddress removedNode) {
    return false;
  }

  @Override
  public JavaImplOfReplicatedData prune(UniqueAddress removedNode, UniqueAddress collapseInto) {
    return this;
  }

  @Override
  public JavaImplOfReplicatedData pruningCleanup(UniqueAddress removedNode) {
    return this;
  }
}
