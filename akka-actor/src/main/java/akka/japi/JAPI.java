package akka.japi;

import scala.collection.Seq;

public class JAPI {

  public static <T> Seq<T> seq(T... ts) {
    return Util.arrayToSeq(ts);
  }
  
}
