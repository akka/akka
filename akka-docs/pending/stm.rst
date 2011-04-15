Akka STM

The Akka Software Transactional Memory implementation

**Read consistency**
^^^^^^^^^^^^^^^^^^^^

Read consistency is that all value

**Read concistency and MVCC**
*****************************

A lot of STM (like the Clojure STM) implementations are Multi Version Concurrency Control Based (MVCC) based (TL2 of david dice could be seen as MVCC).

To provide read consistency, every ref is augmented with a version field (a long). There also is a logical clock (an AtomicLong for instance) that is incremented every time a transaction does a commit (there are some optimizations) and on all refs written, the version of the ref is updated to this new clock value.

If a transaction begins, it reads the current version of the clock and makes sure that the version of the refs it reads, are equal or lower than the version of the transaction. If the transaction encounters a ref with a higher value, the transaction is aborted and retried.

MVCC STM’s are relatively simple to write and have some very nice properties:
# readers don’t block writers
# writers don’t block readers
# persistent data-structures are very easy to write since a log can be added to each ref containing older versions of the data,

The problem with MVCC however is that the central clock forms a contention point that makes independent transactional data-structures not linearly scalable. todo: give example of scalability with MVCC.

So even if you have 2 Threads having their private transactional Ref (so there is no visible contention), underwater the transaction still are going to contend for the clock.

**Read consistency and the Akka STM**
*************************************

The AkkaSTM (that is build on top of the Multiverse 0.7 STM) and from Akka 1.1 it doesn’t use a MVCC based implementation because of the scalability limiting central clock.

It uses 2 different mechanisms:
1) For very short transactions it does a full conflict scan every time a new ref is read. Doing a full conflict scan sounds expensive, but it only involves volatile reads.
2) For longer transactions it uses semi visible reads. Every time a read is done, the surplus of readers is incremented and stored in the ref. Once the transaction aborts or commits, the surplus is lowered again. If a transaction does an update, and sees that there is a surplus of readers, it increments a conflict counter. This conflict counter is checked every time a transaction reads a new ref. If it hasn’t changed, no full conflict scan is needed. If it has changed, a full conflict scan is required. If a conflict is detected, the transaction is aborted and retried. This technique is called a semi visible read (we don’t know which transactions are possibly going to encounter a conflict, but we do know if there is at least one possible conflict).

There are 2 important optimizations to this design:
# Eager full conflict scan
# Read biases refs

**Eager full conflict scan**
****************************

The reasons why short transactions always do a full conflict scan is that doing semi visible reads, relies doing more expensive synchronization operations (e.g. doing a cas to increase the surplus of readers, or doing a cas to decrease it).

**Read biased vs update biased.**
*********************************

The problem with semi visible reads is that certain structures (e.g. the root of a tree) can form a contention point (because of the arrives/departs) even though it mostly is read. To reduce contention, a ref can become read biased after a certain number of reads by transactions that use semi visible reads is done. Once it has become read biased, no arrives and departs are required any more, but once it the Ref is updated it will always increment the conflict counter because it doesn’t know if there are any conflicting readers.

Visible reads, semi visible reads
Read tracking

strict isolation
eager conflict detection
deferred write, no dirty read possible

isolation level
optimistic
various levels of pessimistic behavior
