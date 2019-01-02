/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.tutorial_3;

import java.util.Optional;

import jdocs.tutorial_3.Device.ReadTemperature;
import jdocs.tutorial_3.Device.RecordTemperature;
import jdocs.tutorial_3.Device.RespondTemperature;
import jdocs.tutorial_3.Device.TemperatureRecorded;

class DeviceInProgress1 {

  //#read-protocol-1
  public static final class ReadTemperature {
  }

  public static final class RespondTemperature {
    final Optional<Double> value;

    public RespondTemperature(Optional<Double> value) {
      this.value = value;
    }
  }
  //#read-protocol-1

}

