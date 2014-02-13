/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication;

import akka.cluster.UniqueAddress;

public class JavaImplOfReplicatedData extends ReplicatedDataBase implements
    RemovedNodePruning {

  @Override
  public JavaImplOfReplicatedData merge(ReplicatedData other) {
    return this;
  }

  @Override
  public boolean hasDataFrom(UniqueAddress node) {
    return false;
  }

  @Override
  public JavaImplOfReplicatedData prune(UniqueAddress from, UniqueAddress to) {
    return this;
  }

  @Override
  public JavaImplOfReplicatedData clear(UniqueAddress from) {
    return this;
  }
}
