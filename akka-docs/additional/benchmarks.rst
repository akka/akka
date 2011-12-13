Benchmarks
==========

Scalability, Throughput and Latency benchmark
---------------------------------------------

Simple Trading system.

- `Here is the result with some graphs <https://github.com/patriknw/akka-sample-trading/wiki/Results>`_
- `Here is the code <http://github.com/patriknw/akka-sample-trading>`_

Compares:

- Scala library Actors

  - Fire-forget
  - Request-reply

- Akka
  - Request-reply
  - Fire-forget with default dispatcher
  - Fire-forget with Hawt dispatcher

Performance benchmark
---------------------

Benchmarking Akka against:

- Scala Library Actors
- Raw Java concurrency
- Jetlang (Java actors lib) `<http://github.com/jboner/akka-bench>`_
