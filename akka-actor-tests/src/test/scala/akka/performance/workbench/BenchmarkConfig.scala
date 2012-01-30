package akka.performance.workbench
import com.typesafe.config.ConfigFactory

object BenchmarkConfig {
  private val benchmarkConfig = ConfigFactory.parseString("""
    akka {
        event-handlers = ["akka.testkit.TestEventListener"]
        loglevel = "WARNING"
    }

    benchmark {
      longRunning = false
      minClients = 1
      maxClients = 4
      repeatFactor = 2
      timeDilation = 1
      maxRunDuration = 20 seconds
      clientDelay = 250000 nanoseconds
      logResult = true
      resultDir = "target/benchmark"
      useDummyOrderbook = false

      throughput-dispatcher {
        throughput = 5
        executor = "fork-join-executor"
        fork-join-executor {
          parallelism-min = ${benchmark.maxClients}
          parallelism-max = ${benchmark.maxClients}
        }
      }

      latency-dispatcher {
        throughput = 1
        executor = "fork-join-executor"
        fork-join-executor {
          parallelism-min = ${benchmark.maxClients}
          parallelism-max = ${benchmark.maxClients}
        }
      }

      trading-dispatcher {
        throughput = 5
        executor = "fork-join-executor"
        fork-join-executor {
          parallelism-min = ${benchmark.maxClients}
          parallelism-max = ${benchmark.maxClients}
        }
      }
    }
    """)
  private val longRunningBenchmarkConfig = ConfigFactory.parseString("""
    benchmark {
      longRunning = true
      minClients = 4
      maxClients = 48
      repeatFactor = 2000
      maxRunDuration = 120 seconds
      useDummyOrderbook = true
    }
    """).withFallback(benchmarkConfig)

  def config = {
    val benchCfg =
      if (System.getProperty("benchmark.longRunning") == "true") longRunningBenchmarkConfig else benchmarkConfig
    // external config first, to be able to override
    ConfigFactory.load(benchCfg)
  }

}