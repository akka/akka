package jdocs.tutorial_2;

import java.util.Optional;
import jdocs.tutorial_2.Device.ReadTemperature;
import jdocs.tutorial_2.Device.RecordTemperature;
import jdocs.tutorial_2.Device.RespondTemperature;
import jdocs.tutorial_2.Device.TemperatureRecorded;

class DeviceInProgress1 {

    //#read-protocol-1
    final static class ReadTemperature {}
    final static class RespondTemperature {
      final Optional<Double> value;
      public RespondTemperature(Optional<Double> value) {
        this.value = value;
      }
    }
    //#read-protocol-1

}

