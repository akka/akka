/*
 * Copyright (C) 2018-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.japi;

import scala.collection.Seq;

public class JAPI {

  @SafeVarargs
  public static <T> Seq<T> seq(T... ts) {
    return Util.immutableSeq(ts);
  }
}
