/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.ints;

/**
 * An abstract class facilitating the creation of type-specific {@linkplain java.util.Comparator comparators}.
 * <p>
 * <P>To create a type-specific comparator you need both a method comparing
 * primitive types and a method comparing objects. However, if you have the
 * first one you can just inherit from this class and get for free the second
 * one.
 *
 * @see java.util.Comparator
 */

public abstract class AbstractIntComparator implements IntComparator, java.io.Serializable {
  private static final long serialVersionUID = 0L;

  protected AbstractIntComparator() {
  }

  public int compare(Integer ok1, Integer ok2) {
    return compare(ok1.intValue(), ok2.intValue());
  }

  public abstract int compare(int k1, int k2);
}

