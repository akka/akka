/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication;

import akka.cluster.UniqueAddress;

public class JavaImplOfReplicatedData extends AbstractReplicatedData implements
    RemovedNodePruning {

  @Override
  public JavaImplOfReplicatedData merge(ReplicatedData other) {
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
