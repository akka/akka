/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor;

import akka.japi.Creator;

public class NonStaticCreator implements Creator<ClassicAbstractActor> {
  @Override
  public ClassicAbstractActor create() throws Exception {
    return null;
  }
}
