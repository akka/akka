/*
 * Copyright (C) 2018-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor;

import akka.japi.Creator;

public class NonStaticCreator implements Creator<UntypedAbstractActor> {
  @Override
  public UntypedAbstractActor create() throws Exception {
    return null;
  }
}
