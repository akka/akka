/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.tutorial_3;

class DeviceInProgress3 {

  //#write-protocol-1
  public static final class RecordTemperature {
    final double value;

    public RecordTemperature(double value) {
      this.value = value;
    }
  }
  //#write-protocol-1
}
