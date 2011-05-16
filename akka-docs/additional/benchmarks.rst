Benchmarks
==========

Scalability, Throughput and Latency benchmark
---------------------------------------------

 .. image:: ../images/benchmark-akka-sample-trading-throughput.png

Simple Trading system.

- `Here is the result with some graphs <https://spreadsheets.google.com/ccc?key=0AqkhZTxa6-dOdERaQnNvOEZpMDdnazRWOVNHMWIxZ0E&hl=en&authkey=CLyksoEI#gid=0>`_
- `Here is the article <http://blog.jayway.com/2010/08/10/yet-another-akka-benchmark/>`_
- `Here is the code <http://github.com/patriknw/akka-sample-trading>`_

Compares:

- Synchronous Scala solution
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
