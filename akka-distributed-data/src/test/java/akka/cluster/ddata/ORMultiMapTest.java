/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata;

import akka.cluster.Cluster;

public class ORMultiMapTest {

  public void compileOnlyORMultiMapTest() {
    // primarily to check API accessibility with overloads/types
    Cluster node = null;
    ORMultiMap<String, String> orMultiMap = ORMultiMap.create();
    orMultiMap.addBinding(node, "a", "1");
    orMultiMap.removeBinding(node, "a", "1");

  }
}
