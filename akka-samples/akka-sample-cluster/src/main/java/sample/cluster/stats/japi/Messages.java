package sample.cluster.stats.japi;

import java.io.Serializable;

//#messages
public interface Messages {

  public static class StatsJob implements Serializable {
    private final String text;

    public StatsJob(String text) {
      this.text = text;
    }

    public String getText() {
      return text;
    }
  }

  public static class StatsResult implements Serializable {
    private final double meanWordLength;

    public StatsResult(double meanWordLength) {
      this.meanWordLength = meanWordLength;
    }

    public double getMeanWordLength() {
      return meanWordLength;
    }

  }

  public static class JobFailed implements Serializable {
    private final String reason;

    public JobFailed(String reason) {
      this.reason = reason;
    }

    public String getReason() {
      return reason;
    }

  }

}
//#messages