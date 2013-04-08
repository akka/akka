/**
 * Copyright (C) 2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io.japi;

import java.nio.ByteOrder;

import akka.io.PipelineContext;

public interface HasByteOrder extends PipelineContext {

  public ByteOrder byteOrder();
  
}
