package akka.performance.workbench
import com.typesafe.config.ConfigFactory

object BenchmarkConfig {
  private val benchmarkConfig = ConfigFactory.parseString("""
    benchmark {
      longRunning = false
      minClients = 1
      maxClients = 4
      repeatFactor = 2
      timeDilation = 1
      maxRunDuration = 10 seconds
      clientDelay = 250000 nanoseconds
      logResult = true
      resultDir = "target/benchmark"
      useDummyOrderbook = false
    }      
    """)
  private val longRunningBenchmarkConfig = ConfigFactory.parseString("""
    benchmark {
      longRunning = true
      maxClients = 48
      repeatFactor = 150
      maxRunDuration = 120 seconds
      useDummyOrderbook = true
    }  
    """).withFallback(benchmarkConfig)

  def config = if (System.getProperty("benchmark.longRunning") == "true")
    longRunningBenchmarkConfig else benchmarkConfig

}