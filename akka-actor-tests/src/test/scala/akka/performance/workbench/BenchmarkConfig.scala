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

      client-dispatcher {
        executor = "thread-pool-executor"
        thread-pool-executor {
          core-pool-size-min = ${benchmark.maxClients}
          core-pool-size-max = ${benchmark.maxClients}
        }
      }

      destination-dispatcher {
        executor = "thread-pool-executor"
        thread-pool-executor {
          core-pool-size-min = ${benchmark.maxClients}
          core-pool-size-max = ${benchmark.maxClients}
        }
      }

      high-throughput-dispatcher {
        throughput = 10000
        executor = "thread-pool-executor"
        thread-pool-executor {
          core-pool-size-min = ${benchmark.maxClients}
          core-pool-size-max = ${benchmark.maxClients}
        }
      }

      pinned-dispatcher {
        type = PinnedDispatcher
      }

      latency-dispatcher {
        throughput = 1
        executor = "thread-pool-executor"
        thread-pool-executor {
          core-pool-size-min = ${benchmark.maxClients}
          core-pool-size-max = ${benchmark.maxClients}
        }
      }
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

  def config = {
    val benchCfg =
      if (System.getProperty("benchmark.longRunning") == "true") longRunningBenchmarkConfig else benchmarkConfig
    // external config first, to be able to override
    ConfigFactory.load(benchCfg)
  }

}