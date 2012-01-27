/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package akka.jsr166y;

import akka.util.Unsafe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

/**
 * An {@link ExecutorService} for running {@link ForkJoinTask}s.
 * A {@code ForkJoinPool} provides the entry point for submissions
 * from non-{@code ForkJoinTask} clients, as well as management and
 * monitoring operations.
 *
 * <p>A {@code ForkJoinPool} differs from other kinds of {@link
 * ExecutorService} mainly by virtue of employing
 * <em>work-stealing</em>: all threads in the pool attempt to find and
 * execute tasks submitted to the pool and/or created by other active
 * tasks (eventually blocking waiting for work if none exist). This
 * enables efficient processing when most tasks spawn other subtasks
 * (as do most {@code ForkJoinTask}s), as well as when many small
 * tasks are submitted to the pool from external clients.  Especially
 * when setting <em>asyncMode</em> to true in constructors, {@code
 * ForkJoinPool}s may also be appropriate for use with event-style
 * tasks that are never joined.
 *
 * <p>A {@code ForkJoinPool} is constructed with a given target
 * parallelism level; by default, equal to the number of available
 * processors. The pool attempts to maintain enough active (or
 * available) threads by dynamically adding, suspending, or resuming
 * internal worker threads, even if some tasks are stalled waiting to
 * join others. However, no such adjustments are guaranteed in the
 * face of blocked IO or other unmanaged synchronization. The nested
 * {@link ManagedBlocker} interface enables extension of the kinds of
 * synchronization accommodated.
 *
 * <p>In addition to execution and lifecycle control methods, this
 * class provides status check methods (for example
 * {@link #getStealCount}) that are intended to aid in developing,
 * tuning, and monitoring fork/join applications. Also, method
 * {@link #toString} returns indications of pool state in a
 * convenient form for informal monitoring.
 *
 * <p> As is the case with other ExecutorServices, there are three
 * main task execution methods summarized in the following
 * table. These are designed to be used primarily by clients not
 * already engaged in fork/join computations in the current pool.  The
 * main forms of these methods accept instances of {@code
 * ForkJoinTask}, but overloaded forms also allow mixed execution of
 * plain {@code Runnable}- or {@code Callable}- based activities as
 * well.  However, tasks that are already executing in a pool should
 * normally instead use the within-computation forms listed in the
 * table unless using async event-style tasks that are not usually
 * joined, in which case there is little difference among choice of
 * methods.
 *
 * <table BORDER CELLPADDING=3 CELLSPACING=1>
 *  <tr>
 *    <td></td>
 *    <td ALIGN=CENTER> <b>Call from non-fork/join clients</b></td>
 *    <td ALIGN=CENTER> <b>Call from within fork/join computations</b></td>
 *  </tr>
 *  <tr>
 *    <td> <b>Arrange async execution</td>
 *    <td> {@link #execute(ForkJoinTask)}</td>
 *    <td> {@link ForkJoinTask#fork}</td>
 *  </tr>
 *  <tr>
 *    <td> <b>Await and obtain result</td>
 *    <td> {@link #invoke(ForkJoinTask)}</td>
 *    <td> {@link ForkJoinTask#invoke}</td>
 *  </tr>
 *  <tr>
 *    <td> <b>Arrange exec and obtain Future</td>
 *    <td> {@link #submit(ForkJoinTask)}</td>
 *    <td> {@link ForkJoinTask#fork} (ForkJoinTasks <em>are</em> Futures)</td>
 *  </tr>
 * </table>
 *
 * <p><b>Sample Usage.</b> Normally a single {@code ForkJoinPool} is
 * used for all parallel task execution in a program or subsystem.
 * Otherwise, use would not usually outweigh the construction and
 * bookkeeping overhead of creating a large set of threads. For
 * example, a common pool could be used for the {@code SortTasks}
 * illustrated in {@link RecursiveAction}. Because {@code
 * ForkJoinPool} uses threads in {@linkplain java.lang.Thread#isDaemon
 * daemon} mode, there is typically no need to explicitly {@link
 * #shutdown} such a pool upon program exit.
 *
 *  <pre> {@code
 * static final ForkJoinPool mainPool = new ForkJoinPool();
 * ...
 * public void sort(long[] array) {
 *   mainPool.invoke(new SortTask(array, 0, array.length));
 * }}</pre>
 *
 * <p><b>Implementation notes</b>: This implementation restricts the
 * maximum number of running threads to 32767. Attempts to create
 * pools with greater than the maximum number result in
 * {@code IllegalArgumentException}.
 *
 * <p>This implementation rejects submitted tasks (that is, by throwing
 * {@link RejectedExecutionException}) only when the pool is shut down
 * or internal resources have been exhausted.
 *
 * @since 1.7
 * @author Doug Lea
 */
public class ForkJoinPool extends AbstractExecutorService {

    /*
     * Implementation Overview
     *
     * This class and its nested classes provide the main
     * functionality and control for a set of worker threads:
     * Submissions from non-FJ threads enter into submission
     * queues. Workers take these tasks and typically split them into
     * subtasks that may be stolen by other workers.  Preference rules
     * give first priority to processing tasks from their own queues
     * (LIFO or FIFO, depending on mode), then to randomized FIFO
     * steals of tasks in other queues.
     *
     * WorkQueues.
     * ==========
     *
     * Most operations occur within work-stealing queues (in nested
     * class WorkQueue).  These are special forms of Deques that
     * support only three of the four possible end-operations -- push,
     * pop, and poll (aka steal), under the further constraints that
     * push and pop are called only from the owning thread (or, as
     * extended here, under a lock), while poll may be called from
     * other threads.  (If you are unfamiliar with them, you probably
     * want to read Herlihy and Shavit's book "The Art of
     * Multiprocessor programming", chapter 16 describing these in
     * more detail before proceeding.)  The main work-stealing queue
     * design is roughly similar to those in the papers "Dynamic
     * Circular Work-Stealing Deque" by Chase and Lev, SPAA 2005
     * (http://research.sun.com/scalable/pubs/index.html) and
     * "Idempotent work stealing" by Michael, Saraswat, and Vechev,
     * PPoPP 2009 (http://portal.acm.org/citation.cfm?id=1504186).
     * The main differences ultimately stem from gc requirements that
     * we null out taken slots as soon as we can, to maintain as small
     * a footprint as possible even in programs generating huge
     * numbers of tasks. To accomplish this, we shift the CAS
     * arbitrating pop vs poll (steal) from being on the indices
     * ("base" and "top") to the slots themselves.  So, both a
     * successful pop and poll mainly entail a CAS of a slot from
     * non-null to null.  Because we rely on CASes of references, we
     * do not need tag bits on base or top.  They are simple ints as
     * used in any circular array-based queue (see for example
     * ArrayDeque).  Updates to the indices must still be ordered in a
     * way that guarantees that top == base means the queue is empty,
     * but otherwise may err on the side of possibly making the queue
     * appear nonempty when a push, pop, or poll have not fully
     * committed. Note that this means that the poll operation,
     * considered individually, is not wait-free. One thief cannot
     * successfully continue until another in-progress one (or, if
     * previously empty, a push) completes.  However, in the
     * aggregate, we ensure at least probabilistic non-blockingness.
     * If an attempted steal fails, a thief always chooses a different
     * random victim target to try next. So, in order for one thief to
     * progress, it suffices for any in-progress poll or new push on
     * any empty queue to complete.
     *
     * This approach also enables support of a user mode in which local
     * task processing is in FIFO, not LIFO order, simply by using
     * poll rather than pop.  This can be useful in message-passing
     * frameworks in which tasks are never joined.  However neither
     * mode considers affinities, loads, cache localities, etc, so
     * rarely provide the best possible performance on a given
     * machine, but portably provide good throughput by averaging over
     * these factors.  (Further, even if we did try to use such
     * information, we do not usually have a basis for exploiting
     * it. For example, some sets of tasks profit from cache
     * affinities, but others are harmed by cache pollution effects.)
     *
     * WorkQueues are also used in a similar way for tasks submitted
     * to the pool. We cannot mix these tasks in the same queues used
     * for work-stealing (this would contaminate lifo/fifo
     * processing). Instead, we loosely associate (via hashing)
     * submission queues with submitting threads, and randomly scan
     * these queues as well when looking for work. In essence,
     * submitters act like workers except that they never take tasks,
     * and they are multiplexed on to a finite number of shared work
     * queues. However, classes are set up so that future extensions
     * could allow submitters to optionally help perform tasks as
     * well. Pool submissions from internal workers are also allowed,
     * but use randomized rather than thread-hashed queue indices to
     * avoid imbalance.  Insertion of tasks in shared mode requires a
     * lock (mainly to protect in the case of resizing) but we use
     * only a simple spinlock (using bits in field runState), because
     * submitters encountering a busy queue try or create others so
     * never block.
     *
     * Management.
     * ==========
     *
     * The main throughput advantages of work-stealing stem from
     * decentralized control -- workers mostly take tasks from
     * themselves or each other. We cannot negate this in the
     * implementation of other management responsibilities. The main
     * tactic for avoiding bottlenecks is packing nearly all
     * essentially atomic control state into two volatile variables
     * that are by far most often read (not written) as status and
     * consistency checks
     *
     * Field "ctl" contains 64 bits holding all the information needed
     * to atomically decide to add, inactivate, enqueue (on an event
     * queue), dequeue, and/or re-activate workers.  To enable this
     * packing, we restrict maximum parallelism to (1<<15)-1 (which is
     * far in excess of normal operating range) to allow ids, counts,
     * and their negations (used for thresholding) to fit into 16bit
     * fields.
     *
     * Field "runState" contains 32 bits needed to register and
     * deregister WorkQueues, as well as to enable shutdown. It is
     * only modified under a lock (normally briefly held, but
     * occasionally protecting allocations and resizings) but even
     * when locked remains available to check consistency.
     *
     * Recording WorkQueues.  WorkQueues are recorded in the
     * "workQueues" array that is created upon pool construction and
     * expanded if necessary.  Updates to the array while recording
     * new workers and unrecording terminated ones are protected from
     * each other by a lock but the array is otherwise concurrently
     * readable, and accessed directly.  To simplify index-based
     * operations, the array size is always a power of two, and all
     * readers must tolerate null slots. Shared (submission) queues
     * are at even indices, worker queues at odd indices. Grouping
     * them together in this way simplifies and speeds up task
     * scanning. To avoid flailing during start-up, the array is
     * presized to hold twice #parallelism workers (which is unlikely
     * to need further resizing during execution). But to avoid
     * dealing with so many null slots, variable runState includes a
     * mask for the nearest power of two that contains all current
     * workers.  All worker thread creation is on-demand, triggered by
     * task submissions, replacement of terminated workers, and/or
     * compensation for blocked workers. However, all other support
     * code is set up to work with other policies.  To ensure that we
     * do not hold on to worker references that would prevent GC, ALL
     * accesses to workQueues are via indices into the workQueues
     * array (which is one source of some of the messy code
     * constructions here). In essence, the workQueues array serves as
     * a weak reference mechanism. Thus for example the wait queue
     * field of ctl stores indices, not references.  Access to the
     * workQueues in associated methods (for example signalWork) must
     * both index-check and null-check the IDs. All such accesses
     * ignore bad IDs by returning out early from what they are doing,
     * since this can only be associated with termination, in which
     * case it is OK to give up.
     *
     * All uses of the workQueues array check that it is non-null
     * (even if previously non-null). This allows nulling during
     * termination, which is currently not necessary, but remains an
     * option for resource-revocation-based shutdown schemes. It also
     * helps reduce JIT issuance of uncommon-trap code, which tends to
     * unnecessarily complicate control flow in some methods.
     *
     * Event Queuing. Unlike HPC work-stealing frameworks, we cannot
     * let workers spin indefinitely scanning for tasks when none can
     * be found immediately, and we cannot start/resume workers unless
     * there appear to be tasks available.  On the other hand, we must
     * quickly prod them into action when new tasks are submitted or
     * generated. In many usages, ramp-up time to activate workers is
     * the main limiting factor in overall performance (this is
     * compounded at program start-up by JIT compilation and
     * allocation). So we try to streamline this as much as possible.
     * We park/unpark workers after placing in an event wait queue
     * when they cannot find work. This "queue" is actually a simple
     * Treiber stack, headed by the "id" field of ctl, plus a 15bit
     * counter value (that reflects the number of times a worker has
     * been inactivated) to avoid ABA effects (we need only as many
     * version numbers as worker threads). Successors are held in
     * field WorkQueue.nextWait.  Queuing deals with several intrinsic
     * races, mainly that a task-producing thread can miss seeing (and
     * signalling) another thread that gave up looking for work but
     * has not yet entered the wait queue. We solve this by requiring
     * a full sweep of all workers (via repeated calls to method
     * scan()) both before and after a newly waiting worker is added
     * to the wait queue. During a rescan, the worker might release
     * some other queued worker rather than itself, which has the same
     * net effect. Because enqueued workers may actually be rescanning
     * rather than waiting, we set and clear the "parker" field of
     * Workqueues to reduce unnecessary calls to unpark.  (This
     * requires a secondary recheck to avoid missed signals.)  Note
     * the unusual conventions about Thread.interrupts surrounding
     * parking and other blocking: Because interrupts are used solely
     * to alert threads to check termination, which is checked anyway
     * upon blocking, we clear status (using Thread.interrupted)
     * before any call to park, so that park does not immediately
     * return due to status being set via some other unrelated call to
     * interrupt in user code.
     *
     * Signalling.  We create or wake up workers only when there
     * appears to be at least one task they might be able to find and
     * execute.  When a submission is added or another worker adds a
     * task to a queue that previously had fewer than two tasks, they
     * signal waiting workers (or trigger creation of new ones if
     * fewer than the given parallelism level -- see signalWork).
     * These primary signals are buttressed by signals during rescans;
     * together these cover the signals needed in cases when more
     * tasks are pushed but untaken, and improve performance compared
     * to having one thread wake up all workers.
     *
     * Trimming workers. To release resources after periods of lack of
     * use, a worker starting to wait when the pool is quiescent will
     * time out and terminate if the pool has remained quiescent for
     * SHRINK_RATE nanosecs. This will slowly propagate, eventually
     * terminating all workers after long periods of non-use.
     *
     * Shutdown and Termination. A call to shutdownNow atomically sets
     * a runState bit and then (non-atomically) sets each workers
     * runState status, cancels all unprocessed tasks, and wakes up
     * all waiting workers.  Detecting whether termination should
     * commence after a non-abrupt shutdown() call requires more work
     * and bookkeeping. We need consensus about quiescence (i.e., that
     * there is no more work). The active count provides a primary
     * indication but non-abrupt shutdown still requires a rechecking
     * scan for any workers that are inactive but not queued.
     *
     * Joining Tasks.
     * ==============
     *
     * Any of several actions may be taken when one worker is waiting
     * to join a task stolen (or always held by) another.  Because we
     * are multiplexing many tasks on to a pool of workers, we can't
     * just let them block (as in Thread.join).  We also cannot just
     * reassign the joiner's run-time stack with another and replace
     * it later, which would be a form of "continuation", that even if
     * possible is not necessarily a good idea since we sometimes need
     * both an unblocked task and its continuation to
     * progress. Instead we combine two tactics:
     *
     *   Helping: Arranging for the joiner to execute some task that it
     *      would be running if the steal had not occurred.
     *
     *   Compensating: Unless there are already enough live threads,
     *      method tryCompensate() may create or re-activate a spare
     *      thread to compensate for blocked joiners until they unblock.
     *
     * A third form (implemented in tryRemoveAndExec and
     * tryPollForAndExec) amounts to helping a hypothetical
     * compensator: If we can readily tell that a possible action of a
     * compensator is to steal and execute the task being joined, the
     * joining thread can do so directly, without the need for a
     * compensation thread (although at the expense of larger run-time
     * stacks, but the tradeoff is typically worthwhile).
     *
     * The ManagedBlocker extension API can't use helping so relies
     * only on compensation in method awaitBlocker.
     *
     * The algorithm in tryHelpStealer entails a form of "linear"
     * helping: Each worker records (in field currentSteal) the most
     * recent task it stole from some other worker. Plus, it records
     * (in field currentJoin) the task it is currently actively
     * joining. Method tryHelpStealer uses these markers to try to
     * find a worker to help (i.e., steal back a task from and execute
     * it) that could hasten completion of the actively joined task.
     * In essence, the joiner executes a task that would be on its own
     * local deque had the to-be-joined task not been stolen. This may
     * be seen as a conservative variant of the approach in Wagner &
     * Calder "Leapfrogging: a portable technique for implementing
     * efficient futures" SIGPLAN Notices, 1993
     * (http://portal.acm.org/citation.cfm?id=155354). It differs in
     * that: (1) We only maintain dependency links across workers upon
     * steals, rather than use per-task bookkeeping.  This sometimes
     * requires a linear scan of workers array to locate stealers, but
     * often doesn't because stealers leave hints (that may become
     * stale/wrong) of where to locate them.  A stealHint is only a
     * hint because a worker might have had multiple steals and the
     * hint records only one of them (usually the most current).
     * Hinting isolates cost to when it is needed, rather than adding
     * to per-task overhead.  (2) It is "shallow", ignoring nesting
     * and potentially cyclic mutual steals.  (3) It is intentionally
     * racy: field currentJoin is updated only while actively joining,
     * which means that we miss links in the chain during long-lived
     * tasks, GC stalls etc (which is OK since blocking in such cases
     * is usually a good idea).  (4) We bound the number of attempts
     * to find work (see MAX_HELP_DEPTH) and fall back to suspending
     * the worker and if necessary replacing it with another.
     *
     * It is impossible to keep exactly the target parallelism number
     * of threads running at any given time.  Determining the
     * existence of conservatively safe helping targets, the
     * availability of already-created spares, and the apparent need
     * to create new spares are all racy, so we rely on multiple
     * retries of each.  Currently, in keeping with on-demand
     * signalling policy, we compensate only if blocking would leave
     * less than one active (non-waiting, non-blocked) worker.
     * Additionally, to avoid some false alarms due to GC, lagging
     * counters, system activity, etc, compensated blocking for joins
     * is only attempted after rechecks stabilize in
     * ForkJoinTask.awaitJoin. (Retries are interspersed with
     * Thread.yield, for good citizenship.)
     *
     * Style notes: There is a lot of representation-level coupling
     * among classes ForkJoinPool, ForkJoinWorkerThread, and
     * ForkJoinTask.  The fields of WorkQueue maintain data structures
     * managed by ForkJoinPool, so are directly accessed.  There is
     * little point trying to reduce this, since any associated future
     * changes in representations will need to be accompanied by
     * algorithmic changes anyway. All together, these low-level
     * implementation choices produce as much as a factor of 4
     * performance improvement compared to naive implementations, and
     * enable the processing of billions of tasks per second, at the
     * expense of some ugliness.
     *
     * Methods signalWork() and scan() are the main bottlenecks so are
     * especially heavily micro-optimized/mangled.  There are lots of
     * inline assignments (of form "while ((local = field) != 0)")
     * which are usually the simplest way to ensure the required read
     * orderings (which are sometimes critical). This leads to a
     * "C"-like style of listing declarations of these locals at the
     * heads of methods or blocks.  There are several occurrences of
     * the unusual "do {} while (!cas...)"  which is the simplest way
     * to force an update of a CAS'ed variable. There are also other
     * coding oddities that help some methods perform reasonably even
     * when interpreted (not compiled).
     *
     * The order of declarations in this file is: (1) declarations of
     * statics (2) fields (along with constants used when unpacking
     * some of them), listed in an order that tends to reduce
     * contention among them a bit under most JVMs; (3) nested
     * classes; (4) internal control methods; (5) callbacks and other
     * support for ForkJoinTask methods; (6) exported methods (plus a
     * few little helpers); (7) static block initializing all statics
     * in a minimally dependent order.
     */

    /**
     * Factory for creating new {@link ForkJoinWorkerThread}s.
     * A {@code ForkJoinWorkerThreadFactory} must be defined and used
     * for {@code ForkJoinWorkerThread} subclasses that extend base
     * functionality or initialize threads with different contexts.
     */
    public static interface ForkJoinWorkerThreadFactory {
        /**
         * Returns a new worker thread operating in the given pool.
         *
         * @param pool the pool this thread works in
         * @throws NullPointerException if the pool is null
         */
        public ForkJoinWorkerThread newThread(ForkJoinPool pool);
    }

    /**
     * Default ForkJoinWorkerThreadFactory implementation; creates a
     * new ForkJoinWorkerThread.
     */
    static class DefaultForkJoinWorkerThreadFactory
        implements ForkJoinWorkerThreadFactory {
        public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return new ForkJoinWorkerThread(pool);
        }
    }

    /**
     * Creates a new ForkJoinWorkerThread. This factory is used unless
     * overridden in ForkJoinPool constructors.
     */
    public static final ForkJoinWorkerThreadFactory
        defaultForkJoinWorkerThreadFactory;

    /**
     * Permission required for callers of methods that may start or
     * kill threads.
     */
    private static final RuntimePermission modifyThreadPermission;

    /**
     * If there is a security manager, makes sure caller has
     * permission to modify threads.
     */
    private static void checkPermission() {
        SecurityManager security = System.getSecurityManager();
        if (security != null)
            security.checkPermission(modifyThreadPermission);
    }

    /**
     * Generator for assigning sequence numbers as pool names.
     */
    private static final AtomicInteger poolNumberGenerator;

    /**
     * Bits and masks for control variables
     *
     * Field ctl is a long packed with:
     * AC: Number of active running workers minus target parallelism (16 bits)
     * TC: Number of total workers minus target parallelism (16 bits)
     * ST: true if pool is terminating (1 bit)
     * EC: the wait count of top waiting thread (15 bits)
     * ID: ~(poolIndex >>> 1) of top of Treiber stack of waiters (16 bits)
     *
     * When convenient, we can extract the upper 32 bits of counts and
     * the lower 32 bits of queue state, u = (int)(ctl >>> 32) and e =
     * (int)ctl.  The ec field is never accessed alone, but always
     * together with id and st. The offsets of counts by the target
     * parallelism and the positionings of fields makes it possible to
     * perform the most common checks via sign tests of fields: When
     * ac is negative, there are not enough active workers, when tc is
     * negative, there are not enough total workers, when id is
     * negative, there is at least one waiting worker, and when e is
     * negative, the pool is terminating.  To deal with these possibly
     * negative fields, we use casts in and out of "short" and/or
     * signed shifts to maintain signedness.
     *
     * When a thread is queued (inactivated), its eventCount field is
     * negative, which is the only way to tell if a worker is
     * prevented from executing tasks, even though it must continue to
     * scan for them to avoid queuing races.
     *
     * Field runState is an int packed with:
     * SHUTDOWN: true if shutdown is enabled (1 bit)
     * SEQ:  a sequence number updated upon (de)registering workers (15 bits)
     * MASK: mask (power of 2 - 1) covering all registered poolIndexes (16 bits)
     *
     * The combination of mask and sequence number enables simple
     * consistency checks: Staleness of read-only operations on the
     * workers and queues arrays can be checked by comparing runState
     * before vs after the reads. The low 16 bits (i.e, anding with
     * SMASK) hold (the smallest power of two covering all worker
     * indices, minus one.  The mask for queues (vs workers) is twice
     * this value plus 1.
     */

    // bit positions/shifts for fields
    private static final int  AC_SHIFT   = 48;
    private static final int  TC_SHIFT   = 32;
    private static final int  ST_SHIFT   = 31;
    private static final int  EC_SHIFT   = 16;

    // bounds
    private static final int  MAX_ID     = 0x7fff;  // max poolIndex
    private static final int  SMASK      = 0xffff;  // mask short bits
    private static final int  SHORT_SIGN = 1 << 15;
    private static final int  INT_SIGN   = 1 << 31;

    // masks
    private static final long STOP_BIT   = 0x0001L << ST_SHIFT;
    private static final long AC_MASK    = ((long)SMASK) << AC_SHIFT;
    private static final long TC_MASK    = ((long)SMASK) << TC_SHIFT;

    // units for incrementing and decrementing
    private static final long TC_UNIT    = 1L << TC_SHIFT;
    private static final long AC_UNIT    = 1L << AC_SHIFT;

    // masks and units for dealing with u = (int)(ctl >>> 32)
    private static final int  UAC_SHIFT  = AC_SHIFT - 32;
    private static final int  UTC_SHIFT  = TC_SHIFT - 32;
    private static final int  UAC_MASK   = SMASK << UAC_SHIFT;
    private static final int  UTC_MASK   = SMASK << UTC_SHIFT;
    private static final int  UAC_UNIT   = 1 << UAC_SHIFT;
    private static final int  UTC_UNIT   = 1 << UTC_SHIFT;

    // masks and units for dealing with e = (int)ctl
    private static final int E_MASK      = 0x7fffffff; // no STOP_BIT
    private static final int E_SEQ       = 1 << EC_SHIFT;

    // runState bits
    private static final int SHUTDOWN    = 1 << 31;
    private static final int RS_SEQ      = 1 << 16;
    private static final int RS_SEQ_MASK = 0x7fff0000;

    // access mode for WorkQueue
    static final int LIFO_QUEUE          =  0;
    static final int FIFO_QUEUE          =  1;
    static final int SHARED_QUEUE        = -1;

    /**
     * The wakeup interval (in nanoseconds) for a worker waiting for a
     * task when the pool is quiescent to instead try to shrink the
     * number of workers.  The exact value does not matter too
     * much. It must be short enough to release resources during
     * sustained periods of idleness, but not so short that threads
     * are continually re-created.
     */
    private static final long SHRINK_RATE =
        4L * 1000L * 1000L * 1000L; // 4 seconds

    /**
     * The timeout value for attempted shrinkage, includes
     * some slop to cope with system timer imprecision.
     */
    private static final long SHRINK_TIMEOUT = SHRINK_RATE - (SHRINK_RATE / 10);

    /**
     * The maximum stolen->joining link depth allowed in tryHelpStealer.
     * Depths for legitimate chains are unbounded, but we use a fixed
     * constant to avoid (otherwise unchecked) cycles and to bound
     * staleness of traversal parameters at the expense of sometimes
     * blocking when we could be helping.
     */
    private static final int MAX_HELP_DEPTH = 16;

    /*
     * Field layout order in this class tends to matter more than one
     * would like. Runtime layout order is only loosely related to
     * declaration order and may differ across JVMs, but the following
     * empirically works OK on current JVMs.
     */

    volatile long ctl;                       // main pool control
    final int parallelism;                   // parallelism level
    final int localMode;                     // per-worker scheduling mode
    int nextPoolIndex;                       // hint used in registerWorker
    volatile int runState;                   // shutdown status, seq, and mask
    WorkQueue[] workQueues;                  // main registry
    final ReentrantLock lock;                // for registration
    final Condition termination;             // for awaitTermination
    final ForkJoinWorkerThreadFactory factory; // factory for new workers
    final Thread.UncaughtExceptionHandler ueh; // per-worker UEH
    final AtomicLong stealCount;             // collect counts when terminated
    final AtomicInteger nextWorkerNumber;    // to create worker name string
    final String workerNamePrefix;           // Prefix for assigning worker names

    /**
     * Queues supporting work-stealing as well as external task
     * submission. See above for main rationale and algorithms.
     * Implementation relies heavily on "Unsafe" intrinsics
     * and selective use of "volatile":
     *
     * Field "base" is the index (mod array.length) of the least valid
     * queue slot, which is always the next position to steal (poll)
     * from if nonempty. Reads and writes require volatile orderings
     * but not CAS, because updates are only performed after slot
     * CASes.
     *
     * Field "top" is the index (mod array.length) of the next queue
     * slot to push to or pop from. It is written only by owner thread
     * for push, or under lock for trySharedPush, and accessed by
     * other threads only after reading (volatile) base.  Both top and
     * base are allowed to wrap around on overflow, but (top - base)
     * (or more commonly -(base - top) to force volatile read of base
     * before top) still estimates size.
     *
     * The array slots are read and written using the emulation of
     * volatiles/atomics provided by Unsafe. Insertions must in
     * general use putOrderedObject as a form of releasing store to
     * ensure that all writes to the task object are ordered before
     * its publication in the queue. (Although we can avoid one case
     * of this when locked in trySharedPush.) All removals entail a
     * CAS to null.  The array is always a power of two. To ensure
     * safety of Unsafe array operations, all accesses perform
     * explicit null checks and implicit bounds checks via
     * power-of-two masking.
     *
     * In addition to basic queuing support, this class contains
     * fields described elsewhere to control execution. It turns out
     * to work better memory-layout-wise to include them in this
     * class rather than a separate class.
     *
     * Performance on most platforms is very sensitive to placement of
     * instances of both WorkQueues and their arrays -- we absolutely
     * do not want multiple WorkQueue instances or multiple queue
     * arrays sharing cache lines. (It would be best for queue objects
     * and their arrays to share, but there is nothing available to
     * help arrange that).  Unfortunately, because they are recorded
     * in a common array, WorkQueue instances are often moved to be
     * adjacent by garbage collectors. To reduce impact, we use field
     * padding that works OK on common platforms; this effectively
     * trades off slightly slower average field access for the sake of
     * avoiding really bad worst-case access. (Until better JVM
     * support is in place, this padding is dependent on transient
     * properties of JVM field layout rules.)  We also take care in
     * allocating and sizing and resizing the array. Non-shared queue
     * arrays are initialized (via method growArray) by workers before
     * use. Others are allocated on first use.
     */
    static final class WorkQueue {
        /**
         * Capacity of work-stealing queue array upon initialization.
         * Must be a power of two; at least 4, but set larger to
         * reduce cacheline sharing among queues.
         */
        static final int INITIAL_QUEUE_CAPACITY = 1 << 8;

        /**
         * Maximum size for queue arrays. Must be a power of two less
         * than or equal to 1 << (31 - width of array entry) to ensure
         * lack of wraparound of index calculations, but defined to a
         * value a bit less than this to help users trap runaway
         * programs before saturating systems.
         */
        static final int MAXIMUM_QUEUE_CAPACITY = 1 << 26; // 64M

        volatile long totalSteals; // cumulative number of steals
        int seed;                  // for random scanning; initialize nonzero
        volatile int eventCount;   // encoded inactivation count; < 0 if inactive
        int nextWait;              // encoded record of next event waiter
        int rescans;               // remaining scans until block
        int nsteals;               // top-level task executions since last idle
        final int mode;            // lifo, fifo, or shared
        int poolIndex;             // index of this queue in pool (or 0)
        int stealHint;             // index of most recent known stealer
        volatile int runState;     // 1: locked, -1: terminate; else 0
        volatile int base;         // index of next slot for poll
        int top;                   // index of next slot for push
        ForkJoinTask<?>[] array;   // the elements (initially unallocated)
        final ForkJoinWorkerThread owner; // owning thread or null if shared
        volatile Thread parker;    // == owner during call to park; else null
        ForkJoinTask<?> currentJoin;  // task being joined in awaitJoin
        ForkJoinTask<?> currentSteal; // current non-local task being executed
        // Heuristic padding to ameliorate unfortunate memory placements
        Object p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p0a;

        WorkQueue(ForkJoinWorkerThread owner, int mode) {
            this.owner = owner;
            this.mode = mode;
            // Place indices in the center of array (that is not yet allocated)
            base = top = INITIAL_QUEUE_CAPACITY >>> 1;
        }

        /**
         * Returns number of tasks in the queue
         */
        final int queueSize() {
            int n = base - top; // non-owner callers must read base first
            return (n >= 0) ? 0 : -n;
        }

        /**
         * Pushes a task. Call only by owner in unshared queues.
         *
         * @param task the task. Caller must ensure non-null.
         * @param p, if non-null, pool to signal if necessary
         * @throw RejectedExecutionException if array cannot
         * be resized
         */
        final void push(ForkJoinTask<?> task, ForkJoinPool p) {
            ForkJoinTask<?>[] a;
            int s = top, m, n;
            if ((a = array) != null) {    // ignore if queue removed
                U.putOrderedObject
                    (a, (((m = a.length - 1) & s) << ASHIFT) + ABASE, task);
                if ((n = (top = s + 1) - base) <= 2) {
                    if (p != null)
                        p.signalWork();
                }
                else if (n >= m)
                    growArray(true);
            }
        }

        /**
         * Pushes a task if lock is free and array is either big
         * enough or can be resized to be big enough.
         *
         * @param task the task. Caller must ensure non-null.
         * @return true if submitted
         */
        final boolean trySharedPush(ForkJoinTask<?> task) {
            boolean submitted = false;
            if (runState == 0 && U.compareAndSwapInt(this, RUNSTATE, 0, 1)) {
                ForkJoinTask<?>[] a = array;
                int s = top, n = s - base;
                try {
                    if ((a != null && n < a.length - 1) ||
                        (a = growArray(false)) != null) { // must presize
                        int j = (((a.length - 1) & s) << ASHIFT) + ABASE;
                        U.putObject(a, (long)j, task);    // don't need "ordered"
                        top = s + 1;
                        submitted = true;
                    }
                } finally {
                    runState = 0;                         // unlock
                }
            }
            return submitted;
        }

        /**
         * Takes next task, if one exists, in FIFO order.
         */
        final ForkJoinTask<?> poll() {
            ForkJoinTask<?>[] a; int b, i;
            while ((b = base) - top < 0 && (a = array) != null &&
                   (i = (a.length - 1) & b) >= 0) {
                int j = (i << ASHIFT) + ABASE;
                ForkJoinTask<?> t = (ForkJoinTask<?>)U.getObjectVolatile(a, j);
                if (t != null && base == b &&
                    U.compareAndSwapObject(a, j, t, null)) {
                    base = b + 1;
                    return t;
                }
            }
            return null;
        }

        /**
         * Takes next task, if one exists, in LIFO order.
         * Call only by owner in unshared queues.
         */
        final ForkJoinTask<?> pop() {
            ForkJoinTask<?> t; int m;
            ForkJoinTask<?>[] a = array;
            if (a != null && (m = a.length - 1) >= 0) {
                for (int s; (s = top - 1) - base >= 0;) {
                    int j = ((m & s) << ASHIFT) + ABASE;
                    if ((t = (ForkJoinTask<?>)U.getObjectVolatile(a, j)) == null)
                        break;
                    if (U.compareAndSwapObject(a, j, t, null)) {
                        top = s;
                        return t;
                    }
                }
            }
            return null;
        }

        /**
         * Takes next task, if one exists, in order specified by mode.
         */
        final ForkJoinTask<?> nextLocalTask() {
            return mode == 0 ? pop() : poll();
        }

        /**
         * Returns next task, if one exists, in order specified by mode.
         */
        final ForkJoinTask<?> peek() {
            ForkJoinTask<?>[] a = array; int m;
            if (a == null || (m = a.length - 1) < 0)
                return null;
            int i = mode == 0 ? top - 1 : base;
            int j = ((i & m) << ASHIFT) + ABASE;
            return (ForkJoinTask<?>)U.getObjectVolatile(a, j);
        }

        /**
         * Returns task at index b if b is current base of queue.
         */
        final ForkJoinTask<?> pollAt(int b) {
            ForkJoinTask<?>[] a; int i;
            ForkJoinTask<?> task = null;
            if ((a = array) != null && (i = ((a.length - 1) & b)) >= 0) {
                int j = (i << ASHIFT) + ABASE;
                ForkJoinTask<?> t = (ForkJoinTask<?>)U.getObjectVolatile(a, j);
                if (t != null && base == b &&
                    U.compareAndSwapObject(a, j, t, null)) {
                    base = b + 1;
                    task = t;
                }
            }
            return task;
        }

        /**
         * Pops the given task only if it is at the current top.
         */
        final boolean tryUnpush(ForkJoinTask<?> t) {
            ForkJoinTask<?>[] a; int s;
            if ((a = array) != null && (s = top) != base &&
                U.compareAndSwapObject
                (a, (((a.length - 1) & --s) << ASHIFT) + ABASE, t, null)) {
                top = s;
                return true;
            }
            return false;
        }

        /**
         * Polls the given task only if it is at the current base.
         */
        final boolean pollFor(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a; int b, i;
            if ((b = base) - top < 0 && (a = array) != null &&
                (i = (a.length - 1) & b) >= 0) {
                int j = (i << ASHIFT) + ABASE;
                if (U.getObjectVolatile(a, j) == task && base == b &&
                    U.compareAndSwapObject(a, j, task, null)) {
                    base = b + 1;
                    return true;
                }
            }
            return false;
        }

        /**
         * If present, removes from queue and executes the given task, or
         * any other cancelled task. Returns (true) immediately on any CAS
         * or consistency check failure so caller can retry.
         *
         * @return false if no progress can be made
         */
        final boolean tryRemoveAndExec(ForkJoinTask<?> task) {
            boolean removed = false, empty = true, progress = true;
            ForkJoinTask<?>[] a; int m, s, b, n;
            if ((a = array) != null && (m = a.length - 1) >= 0 &&
                (n = (s = top) - (b = base)) > 0) {
                for (ForkJoinTask<?> t;;) {           // traverse from s to b
                    int j = ((--s & m) << ASHIFT) + ABASE;
                    t = (ForkJoinTask<?>)U.getObjectVolatile(a, j);
                    if (t == null)                    // inconsistent length
                        break;
                    else if (t == task) {
                        if (s + 1 == top) {           // pop
                            if (!U.compareAndSwapObject(a, j, task, null))
                                break;
                            top = s;
                            removed = true;
                        }
                        else if (base == b)           // replace with proxy
                            removed = U.compareAndSwapObject(a, j, task,
                                                             new EmptyTask());
                        break;
                    }
                    else if (t.status >= 0)
                        empty = false;
                    else if (s + 1 == top) {          // pop and throw away
                        if (U.compareAndSwapObject(a, j, t, null))
                            top = s;
                        break;
                    }
                    if (--n == 0) {
                        if (!empty && base == b)
                            progress = false;
                        break;
                    }
                }
            }
            if (removed)
                task.doExec();
            return progress;
        }

        /**
         * Initializes or doubles the capacity of array. Call either
         * by owner or with lock held -- it is OK for base, but not
         * top, to move while resizings are in progress.
         *
         * @param rejectOnFailure if true, throw exception if capacity
         * exceeded (relayed ultimately to user); else return null.
         */
        final ForkJoinTask<?>[] growArray(boolean rejectOnFailure) {
            ForkJoinTask<?>[] oldA = array;
            int size = oldA != null ? oldA.length << 1 : INITIAL_QUEUE_CAPACITY;
            if (size <= MAXIMUM_QUEUE_CAPACITY) {
                int oldMask, t, b;
                ForkJoinTask<?>[] a = array = new ForkJoinTask<?>[size];
                if (oldA != null && (oldMask = oldA.length - 1) >= 0 &&
                    (t = top) - (b = base) > 0) {
                    int mask = size - 1;
                    do {
                        ForkJoinTask<?> x;
                        int oldj = ((b & oldMask) << ASHIFT) + ABASE;
                        int j    = ((b &    mask) << ASHIFT) + ABASE;
                        x = (ForkJoinTask<?>)U.getObjectVolatile(oldA, oldj);
                        if (x != null &&
                            U.compareAndSwapObject(oldA, oldj, x, null))
                            U.putObjectVolatile(a, j, x);
                    } while (++b != t);
                }
                return a;
            }
            else if (!rejectOnFailure)
                return null;
            else
                throw new RejectedExecutionException("Queue capacity exceeded");
        }

        /**
         * Removes and cancels all known tasks, ignoring any exceptions
         */
        final void cancelAll() {
            ForkJoinTask.cancelIgnoringExceptions(currentJoin);
            ForkJoinTask.cancelIgnoringExceptions(currentSteal);
            for (ForkJoinTask<?> t; (t = poll()) != null; )
                ForkJoinTask.cancelIgnoringExceptions(t);
        }

        // Execution methods

        /**
         * Removes and runs tasks until empty, using local mode
         * ordering.
         */
        final void runLocalTasks() {
            if (base - top < 0) {
                for (ForkJoinTask<?> t; (t = nextLocalTask()) != null; )
                    t.doExec();
            }
        }

        /**
         * Executes a top-level task and any local tasks remaining
         * after execution.
         *
         * @return true unless terminating
         */
        final boolean runTask(ForkJoinTask<?> t) {
            boolean alive = true;
            if (t != null) {
                currentSteal = t;
                t.doExec();
                runLocalTasks();
                ++nsteals;
                currentSteal = null;
            }
            else if (runState < 0)            // terminating
                alive = false;
            return alive;
        }

        /**
         * Executes a non-top-level (stolen) task
         */
        final void runSubtask(ForkJoinTask<?> t) {
            if (t != null) {
                ForkJoinTask<?> ps = currentSteal;
                currentSteal = t;
                t.doExec();
                currentSteal = ps;
            }
        }

        /**
         * Computes next value for random probes.  Scans don't require
         * a very high quality generator, but also not a crummy one.
         * Marsaglia xor-shift is cheap and works well enough.  Note:
         * This is manually inlined in several usages in ForkJoinPool
         * to avoid writes inside busy scan loops.
         */
        final int nextSeed() {
            int r = seed;
            r ^= r << 13;
            r ^= r >>> 17;
            r ^= r << 5;
            return seed = r;
        }

        // Unsafe mechanics
        private static final sun.misc.Unsafe U;
        private static final long RUNSTATE;
        private static final int ABASE;
        private static final int ASHIFT;
        static {
            int s;
            try {
                U = getUnsafe();
                Class<?> k = WorkQueue.class;
                Class<?> ak = ForkJoinTask[].class;
                RUNSTATE = U.objectFieldOffset
                    (k.getDeclaredField("runState"));
                ABASE = U.arrayBaseOffset(ak);
                s = U.arrayIndexScale(ak);
            } catch (Exception e) {
                throw new Error(e);
            }
            if ((s & (s-1)) != 0)
                throw new Error("data type scale not a power of two");
            ASHIFT = 31 - Integer.numberOfLeadingZeros(s);
        }
    }

    /**
     * Class for artificial tasks that are used to replace the target
     * of local joins if they are removed from an interior queue slot
     * in WorkQueue.tryRemoveAndExec. We don't need the proxy to
     * actually do anything beyond having a unique identity.
     */
    static final class EmptyTask extends ForkJoinTask<Void> {
        EmptyTask() { status = ForkJoinTask.NORMAL; } // force done
        public Void getRawResult() { return null; }
        public void setRawResult(Void x) {}
        public boolean exec() { return true; }
    }

    /**
     * Computes a hash code for the given thread. This method is
     * expected to provide higher-quality hash codes than those using
     * method hashCode().
     */
    static final int hashThread(Thread t) {
        long id = (t == null) ? 0L : t.getId(); // Use MurmurHash of thread id
        int h = (int)id ^ (int)(id >>> 32);
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        return h ^ (h >>> 16);
    }

    /**
     * Top-level runloop for workers
     */
    final void runWorker(ForkJoinWorkerThread wt) {
        WorkQueue w = wt.workQueue;
        w.growArray(false);     // Initialize queue array and seed in this thread
        w.seed = hashThread(Thread.currentThread()) | (1 << 31); // force < 0

        do {} while (w.runTask(scan(w)));
    }

    // Creating, registering and deregistering workers

    /**
     * Tries to create and start a worker
     */
    private void addWorker() {
        Throwable ex = null;
        ForkJoinWorkerThread w = null;
        try {
            if ((w = factory.newThread(this)) != null) {
                w.start();
                return;
            }
        } catch (Throwable e) {
            ex = e;
        }
        deregisterWorker(w, ex);
    }

    /**
     * Callback from ForkJoinWorkerThread constructor to assign a
     * public name. This must be separate from registerWorker because
     * it is called during the "super" constructor call in
     * ForkJoinWorkerThread.
     */
    final String nextWorkerName() {
        return workerNamePrefix.concat
            (Integer.toString(nextWorkerNumber.addAndGet(1)));
    }

    /**
     * Callback from ForkJoinWorkerThread constructor to establish and
     * record its WorkQueue
     *
     * @param wt the worker thread
     */
    final void registerWorker(ForkJoinWorkerThread wt) {
        WorkQueue w = wt.workQueue;
        ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int k = nextPoolIndex;
            WorkQueue[] ws = workQueues;
            if (ws != null) {                       // ignore on shutdown
                int n = ws.length;
                if (k < 0 || (k & 1) == 0 || k >= n || ws[k] != null) {
                    for (k = 1; k < n && ws[k] != null; k += 2)
                        ;                           // workers are at odd indices
                    if (k >= n)                     // resize
                        workQueues = ws = Arrays.copyOf(ws, n << 1);
                }
                w.poolIndex = k;
                w.eventCount = ~(k >>> 1) & SMASK;  // Set up wait count
                ws[k] = w;                          // record worker
                nextPoolIndex = k + 2;
                int rs = runState;
                int m = rs & SMASK;                 // recalculate runState mask
                if (k > m)
                    m = (m << 1) + 1;
                runState = (rs & SHUTDOWN) | ((rs + RS_SEQ) & RS_SEQ_MASK) | m;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Final callback from terminating worker, as well as failure to
     * construct or start a worker in addWorker.  Removes record of
     * worker from array, and adjusts counts. If pool is shutting
     * down, tries to complete termination.
     *
     * @param wt the worker thread or null if addWorker failed
     * @param ex the exception causing failure, or null if none
     */
    final void deregisterWorker(ForkJoinWorkerThread wt, Throwable ex) {
        WorkQueue w = null;
        if (wt != null && (w = wt.workQueue) != null) {
            w.runState = -1;                // ensure runState is set
            stealCount.getAndAdd(w.totalSteals + w.nsteals);
            int idx = w.poolIndex;
            ReentrantLock lock = this.lock;
            lock.lock();
            try {                           // remove record from array
                WorkQueue[] ws = workQueues;
                if (ws != null && idx >= 0 && idx < ws.length && ws[idx] == w)
                    ws[nextPoolIndex = idx] = null;
            } finally {
                lock.unlock();
            }
        }

        long c;                             // adjust ctl counts
        do {} while (!U.compareAndSwapLong
                     (this, CTL, c = ctl, (((c - AC_UNIT) & AC_MASK) |
                                           ((c - TC_UNIT) & TC_MASK) |
                                           (c & ~(AC_MASK|TC_MASK)))));

        if (!tryTerminate(false) && w != null) {
            w.cancelAll();                  // cancel remaining tasks
            if (w.array != null)            // suppress signal if never ran
                signalWork();               // wake up or create replacement
        }

        if (ex != null)                     // rethrow
            U.throwException(ex);
    }


    // Maintaining ctl counts

    /**
     * Increments active count; mainly called upon return from blocking
     */
    final void incrementActiveCount() {
        long c;
        do {} while (!U.compareAndSwapLong(this, CTL, c = ctl, c + AC_UNIT));
    }

    /**
     * Activates or creates a worker
     */
    final void signalWork() {
        /*
         * The while condition is true if: (there is are too few total
         * workers OR there is at least one waiter) AND (there are too
         * few active workers OR the pool is terminating).  The value
         * of e distinguishes the remaining cases: zero (no waiters)
         * for create, negative if terminating (in which case do
         * nothing), else release a waiter. The secondary checks for
         * release (non-null array etc) can fail if the pool begins
         * terminating after the test, and don't impose any added cost
         * because JVMs must perform null and bounds checks anyway.
         */
        long c; int e, u;
        while ((((e = (int)(c = ctl)) | (u = (int)(c >>> 32))) &
                (INT_SIGN|SHORT_SIGN)) == (INT_SIGN|SHORT_SIGN)) {
            WorkQueue[] ws = workQueues; int i; WorkQueue w; Thread p;
            if (e == 0) {                    // add a new worker
                if (U.compareAndSwapLong
                    (this, CTL, c, (long)(((u + UTC_UNIT) & UTC_MASK) |
                                          ((u + UAC_UNIT) & UAC_MASK)) << 32)) {
                    addWorker();
                    break;
                }
            }
            else if (e > 0 && ws != null &&
                     (i = ((~e << 1) | 1) & SMASK) < ws.length &&
                     (w = ws[i]) != null &&
                     w.eventCount == (e | INT_SIGN)) {
                if (U.compareAndSwapLong
                    (this, CTL, c, (((long)(w.nextWait & E_MASK)) |
                                    ((long)(u + UAC_UNIT) << 32)))) {
                    w.eventCount = (e + E_SEQ) & E_MASK;
                    if ((p = w.parker) != null)
                        U.unpark(p);         // release a waiting worker
                    break;
                }
            }
            else
                break;
        }
    }

    /**
     * Tries to decrement active count (sometimes implicitly) and
     * possibly release or create a compensating worker in preparation
     * for blocking. Fails on contention or termination.
     *
     * @return true if the caller can block, else should recheck and retry
     */
    final boolean tryCompensate() {
        WorkQueue[] ws; WorkQueue w; Thread p;
        int pc = parallelism, e, u, ac, tc, i;
        long c = ctl;

        if ((e = (int)c) >= 0) {
            if ((ac = ((u = (int)(c >>> 32)) >> UAC_SHIFT)) <= 0 &&
                e != 0 && (ws = workQueues) != null &&
                (i = ((~e << 1) | 1) & SMASK) < ws.length &&
                (w = ws[i]) != null) {
                if (w.eventCount == (e | INT_SIGN) &&
                    U.compareAndSwapLong
                    (this, CTL, c, ((long)(w.nextWait & E_MASK) |
                                    (c & (AC_MASK|TC_MASK))))) {
                    w.eventCount = (e + E_SEQ) & E_MASK;
                    if ((p = w.parker) != null)
                        U.unpark(p);
                    return true;             // release an idle worker
                }
            }
            else if ((tc = (short)(u >>> UTC_SHIFT)) >= 0 && ac + pc > 1) {
                long nc = ((c - AC_UNIT) & AC_MASK) | (c & ~AC_MASK);
                if (U.compareAndSwapLong(this, CTL, c, nc))
                    return true;             // no compensation needed
            }
            else if (tc + pc < MAX_ID) {
                long nc = ((c + TC_UNIT) & TC_MASK) | (c & ~TC_MASK);
                if (U.compareAndSwapLong(this, CTL, c, nc)) {
                    addWorker();
                    return true;             // create replacement
                }
            }
        }
        return false;
    }

    // Submissions

    /**
     * Unless shutting down, adds the given task to some submission
     * queue; using a randomly chosen queue index if the caller is a
     * ForkJoinWorkerThread, else one based on caller thread's hash
     * code. If no queue exists at the index, one is created.  If the
     * queue is busy, another is chosen by sweeping through the queues
     * array.
     */
    private void doSubmit(ForkJoinTask<?> task) {
        if (task == null)
            throw new NullPointerException();
        Thread t = Thread.currentThread();
        int r = ((t instanceof ForkJoinWorkerThread) ?
                 ((ForkJoinWorkerThread)t).workQueue.nextSeed() : hashThread(t));
        for (;;) {
            int rs = runState, m = rs & SMASK;
            int j = r &= (m & ~1);                      // even numbered queues
            WorkQueue[] ws = workQueues;
            if (rs < 0 || ws == null)
                throw new RejectedExecutionException(); // shutting down
            if (ws.length > m) {                        // consistency check
                for (WorkQueue q;;) {                   // circular sweep
                    if (((q = ws[j]) != null ||
                         (q = tryAddSharedQueue(j)) != null) &&
                        q.trySharedPush(task)) {
                        signalWork();
                        return;
                    }
                    if ((j = (j + 2) & m) == r) {
                        Thread.yield();                 // all queues busy
                        break;
                    }
                }
            }
        }
    }

    /**
     * Tries to add and register a new queue at the given index.
     *
     * @param idx the workQueues array index to register the queue
     * @return the queue, or null if could not add because could
     * not acquire lock or idx is unusable
     */
    private WorkQueue tryAddSharedQueue(int idx) {
        WorkQueue q = null;
        ReentrantLock lock = this.lock;
        if (idx >= 0 && (idx & 1) == 0 && !lock.isLocked()) {
            // create queue outside of lock but only if apparently free
            WorkQueue nq = new WorkQueue(null, SHARED_QUEUE);
            if (lock.tryLock()) {
                try {
                    WorkQueue[] ws = workQueues;
                    if (ws != null && idx < ws.length) {
                        if ((q = ws[idx]) == null) {
                            int rs;         // update runState seq
                            ws[idx] = q = nq;
                            runState = (((rs = runState) & SHUTDOWN) |
                                        ((rs + RS_SEQ) & ~SHUTDOWN));
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
        return q;
    }

    // Scanning for tasks

    /**
     * Scans for and, if found, returns one task, else possibly
     * inactivates the worker. This method operates on single reads of
     * volatile state and is designed to be re-invoked continuously in
     * part because it returns upon detecting inconsistencies,
     * contention, or state changes that indicate possible success on
     * re-invocation.
     *
     * The scan searches for tasks across queues, randomly selecting
     * the first #queues probes, favoring steals 2:1 over submissions
     * (by exploiting even/odd indexing), and then performing a
     * circular sweep of all queues.  The scan terminates upon either
     * finding a non-empty queue, or completing a full sweep. If the
     * worker is not inactivated, it takes and returns a task from
     * this queue.  On failure to find a task, we take one of the
     * following actions, after which the caller will retry calling
     * this method unless terminated.
     *
     * * If not a complete sweep, try to release a waiting worker.  If
     * the scan terminated because the worker is inactivated, then the
     * released worker will often be the calling worker, and it can
     * succeed obtaining a task on the next call. Or maybe it is
     * another worker, but with same net effect. Releasing in other
     * cases as well ensures that we have enough workers running.
     *
     * * If the caller has run a task since the the last empty scan,
     * return (to allow rescan) if other workers are not also yet
     * enqueued.  Field WorkQueue.rescans counts down on each scan to
     * ensure eventual inactivation, and occasional calls to
     * Thread.yield to help avoid interference with more useful
     * activities on the system.
     *
     * * If pool is terminating, terminate the worker
     *
     * * If not already enqueued, try to inactivate and enqueue the
     * worker on wait queue.
     *
     * * If already enqueued and none of the above apply, either park
     * awaiting signal, or if this is the most recent waiter and pool
     * is quiescent, relay to idleAwaitWork to check for termination
     * and possibly shrink pool.
     *
     * @param w the worker (via its WorkQueue)
     * @return a task or null of none found
     */
    private final ForkJoinTask<?> scan(WorkQueue w) {
        boolean swept = false;                 // true after full empty scan
        WorkQueue[] ws;                        // volatile read order matters
        int r = w.seed, ec = w.eventCount;     // ec is negative if inactive
        int rs = runState, m = rs & SMASK;
        if ((ws = workQueues) != null && ws.length > m) {
            ForkJoinTask<?> task = null;
            for (int k = 0, j = -2 - m; ; ++j) {
                WorkQueue q; int b;
                if (j < 0) {                    // random probes while j negative
                    r ^= r << 13; r ^= r >>> 17; k = (r ^= r << 5) | (j & 1);
                }                               // worker (not submit) for odd j
                else                            // cyclic scan when j >= 0
                    k += (m >>> 1) | 1;         // step by half to reduce bias

                if ((q = ws[k & m]) != null && (b = q.base) - q.top < 0) {
                    if (ec >= 0)
                        task = q.pollAt(b);     // steal
                    break;
                }
                else if (j > m) {
                    if (rs == runState)        // staleness check
                        swept = true;
                    break;
                }
            }
            w.seed = r;                        // save seed for next scan
            if (task != null)
                return task;
        }

        // Decode ctl on empty scan
        long c = ctl; int e = (int)c, a = (int)(c >> AC_SHIFT), nr, ns;
        if (!swept) {                          // try to release a waiter
            WorkQueue v; Thread p;
            if (e > 0 && a < 0 && ws != null &&
                (v = ws[((~e << 1) | 1) & m]) != null &&
                v.eventCount == (e | INT_SIGN) && U.compareAndSwapLong
                (this, CTL, c, ((long)(v.nextWait & E_MASK) |
                                ((c + AC_UNIT) & (AC_MASK|TC_MASK))))) {
                v.eventCount = (e + E_SEQ) & E_MASK;
                if ((p = v.parker) != null)
                    U.unpark(p);
            }
        }
        else if ((nr = w.rescans) > 0) {       // continue rescanning
            int ac = a + parallelism;
            if ((w.rescans = (ac < nr) ? ac : nr - 1) > 0 && w.seed < 0 &&
                w.eventCount == ec)
                Thread.yield();                // 1 bit randomness for yield call
        }
        else if (e < 0)                        // pool is terminating
            w.runState = -1;
        else if (ec >= 0) {                    // try to enqueue
            long nc = (long)ec | ((c - AC_UNIT) & (AC_MASK|TC_MASK));
            w.nextWait = e;
            w.eventCount = ec | INT_SIGN;      // mark as inactive
            if (!U.compareAndSwapLong(this, CTL, c, nc))
                w.eventCount = ec;             // back out on CAS failure
            else if ((ns = w.nsteals) != 0) {  // set rescans if ran task
                if (a <= 0)                    // ... unless too many active
                    w.rescans = a + parallelism;
                w.nsteals = 0;
                w.totalSteals += ns;
            }
        }
        else{                                  // already queued
            if (parallelism == -a)
                idleAwaitWork(w);              // quiescent
            if (w.eventCount == ec) {
                Thread.interrupted();          // clear status
                ForkJoinWorkerThread wt = w.owner;
                U.putObject(wt, PARKBLOCKER, this);
                w.parker = wt;                 // emulate LockSupport.park
                if (w.eventCount == ec)        // recheck
                    U.park(false, 0L);         // block
                w.parker = null;
                U.putObject(wt, PARKBLOCKER, null);
            }
        }
        return null;
    }

    /**
     * If inactivating worker w has caused pool to become quiescent,
     * check for pool termination, and, so long as this is not the
     * only worker, wait for event for up to SHRINK_RATE nanosecs On
     * timeout, if ctl has not changed, terminate the worker, which
     * will in turn wake up another worker to possibly repeat this
     * process.
     *
     * @param w the calling worker
     */
    private void idleAwaitWork(WorkQueue w) {
        long c; int nw, ec;
        if (!tryTerminate(false) &&
            (int)((c = ctl) >> AC_SHIFT) + parallelism == 0 &&
            (ec = w.eventCount) == ((int)c | INT_SIGN) &&
            (nw = w.nextWait) != 0) {
            long nc = ((long)(nw & E_MASK) | // ctl to restore on timeout
                       ((c + AC_UNIT) & AC_MASK) | (c & TC_MASK));
            ForkJoinTask.helpExpungeStaleExceptions(); // help clean
            ForkJoinWorkerThread wt = w.owner;
            while (ctl == c) {
                long startTime = System.nanoTime();
                Thread.interrupted();  // timed variant of version in scan()
                U.putObject(wt, PARKBLOCKER, this);
                w.parker = wt;
                if (ctl == c)
                    U.park(false, SHRINK_RATE);
                w.parker = null;
                U.putObject(wt, PARKBLOCKER, null);
                if (ctl != c)
                    break;
                if (System.nanoTime() - startTime >= SHRINK_TIMEOUT &&
                    U.compareAndSwapLong(this, CTL, c, nc)) {
                    w.runState = -1;          // shrink
                    w.eventCount = (ec + E_SEQ) | E_MASK;
                    break;
                }
            }
        }
    }

    /**
     * Tries to locate and execute tasks for a stealer of the given
     * task, or in turn one of its stealers, Traces currentSteal ->
     * currentJoin links looking for a thread working on a descendant
     * of the given task and with a non-empty queue to steal back and
     * execute tasks from. The first call to this method upon a
     * waiting join will often entail scanning/search, (which is OK
     * because the joiner has nothing better to do), but this method
     * leaves hints in workers to speed up subsequent calls. The
     * implementation is very branchy to cope with potential
     * inconsistencies or loops encountering chains that are stale,
     * unknown, or of length greater than MAX_HELP_DEPTH links.  All
     * of these cases are dealt with by just retrying by caller.
     *
     * @param joiner the joining worker
     * @param task the task to join
     * @return true if found or ran a task (and so is immediately retryable)
     */
    final boolean tryHelpStealer(WorkQueue joiner, ForkJoinTask<?> task) {
        ForkJoinTask<?> subtask;    // current target
        boolean progress = false;
        int depth = 0;              // current chain depth
        int m = runState & SMASK;
        WorkQueue[] ws = workQueues;

        if (ws != null && ws.length > m && (subtask = task).status >= 0) {
            outer:for (WorkQueue j = joiner;;) {
                // Try to find the stealer of subtask, by first using hint
                WorkQueue stealer = null;
                WorkQueue v = ws[j.stealHint & m];
                if (v != null && v.currentSteal == subtask)
                    stealer = v;
                else {
                    for (int i = 1; i <= m; i += 2) {
                        if ((v = ws[i]) != null && v.currentSteal == subtask) {
                            stealer = v;
                            j.stealHint = i; // save hint
                            break;
                        }
                    }
                    if (stealer == null)
                        break;
                }

                for (WorkQueue q = stealer;;) { // Try to help stealer
                    ForkJoinTask<?> t; int b;
                    if (task.status < 0)
                        break outer;
                    if ((b = q.base) - q.top < 0) {
                        progress = true;
                        if (subtask.status < 0)
                            break outer;               // stale
                        if ((t = q.pollAt(b)) != null) {
                            stealer.stealHint = joiner.poolIndex;
                            joiner.runSubtask(t);
                        }
                    }
                    else { // empty - try to descend to find stealer's stealer
                        ForkJoinTask<?> next = stealer.currentJoin;
                        if (++depth == MAX_HELP_DEPTH || subtask.status < 0 ||
                            next == null || next == subtask)
                            break outer;  // max depth, stale, dead-end, cyclic
                        subtask = next;
                        j = stealer;
                        break;
                    }
                }
            }
        }
        return progress;
    }

    /**
     * If task is at base of some steal queue, steals and executes it.
     *
     * @param joiner the joining worker
     * @param task the task
     */
    final void tryPollForAndExec(WorkQueue joiner, ForkJoinTask<?> task) {
        WorkQueue[] ws;
        int m = runState & SMASK;
        if ((ws = workQueues) != null && ws.length > m) {
            for (int j = 1; j <= m && task.status >= 0; j += 2) {
                WorkQueue q = ws[j];
                if (q != null && q.pollFor(task)) {
                    joiner.runSubtask(task);
                    break;
                }
            }
        }
    }

    /**
     * Returns a non-empty steal queue, if one is found during a random,
     * then cyclic scan, else null.  This method must be retried by
     * caller if, by the time it tries to use the queue, it is empty.
     */
    private WorkQueue findNonEmptyStealQueue(WorkQueue w) {
        int r = w.seed;    // Same idea as scan(), but ignoring submissions
        for (WorkQueue[] ws;;) {
            int m = runState & SMASK;
            if ((ws = workQueues) == null)
                return null;
            if (ws.length > m) {
                WorkQueue q;
                for (int n = m << 2, k = r, j = -n;;) {
                    r ^= r << 13; r ^= r >>> 17; r ^= r << 5;
                    if ((q = ws[(k | 1) & m]) != null && q.base - q.top < 0) {
                        w.seed = r;
                        return q;
                    }
                    else if (j > n)
                        return null;
                    else
                        k = (j++ < 0) ? r : k + ((m >>> 1) | 1);

                }
            }
        }
    }

    /**
     * Runs tasks until {@code isQuiescent()}. We piggyback on
     * active count ctl maintenance, but rather than blocking
     * when tasks cannot be found, we rescan until all others cannot
     * find tasks either.
     */
    final void helpQuiescePool(WorkQueue w) {
        for (boolean active = true;;) {
            w.runLocalTasks();      // exhaust local queue
            WorkQueue q = findNonEmptyStealQueue(w);
            if (q != null) {
                ForkJoinTask<?> t;
                if (!active) {      // re-establish active count
                    long c;
                    active = true;
                    do {} while (!U.compareAndSwapLong
                                 (this, CTL, c = ctl, c + AC_UNIT));
                }
                if ((t = q.poll()) != null)
                    w.runSubtask(t);
            }
            else {
                long c;
                if (active) {       // decrement active count without queuing
                    active = false;
                    do {} while (!U.compareAndSwapLong
                                 (this, CTL, c = ctl, c -= AC_UNIT));
                }
                else
                    c = ctl;        // re-increment on exit
                if ((int)(c >> AC_SHIFT) + parallelism == 0) {
                    do {} while (!U.compareAndSwapLong
                                 (this, CTL, c = ctl, c + AC_UNIT));
                    break;
                }
            }
        }
    }

    /**
     * Gets and removes a local or stolen task for the given worker
     *
     * @return a task, if available
     */
    final ForkJoinTask<?> nextTaskFor(WorkQueue w) {
        for (ForkJoinTask<?> t;;) {
            WorkQueue q;
            if ((t = w.nextLocalTask()) != null)
                return t;
            if ((q = findNonEmptyStealQueue(w)) == null)
                return null;
            if ((t = q.poll()) != null)
                return t;
        }
    }

    /**
     * Returns the approximate (non-atomic) number of idle threads per
     * active thread to offset steal queue size for method
     * ForkJoinTask.getSurplusQueuedTaskCount().
     */
    final int idlePerActive() {
        // Approximate at powers of two for small values, saturate past 4
        int p = parallelism;
        int a = p + (int)(ctl >> AC_SHIFT);
        return (a > (p >>>= 1) ? 0 :
                a > (p >>>= 1) ? 1 :
                a > (p >>>= 1) ? 2 :
                a > (p >>>= 1) ? 4 :
                8);
    }

    // Termination

    /**
     * Sets SHUTDOWN bit of runState under lock
     */
    private void enableShutdown() {
        ReentrantLock lock = this.lock;
        if (runState >= 0) {
            lock.lock();                       // don't need try/finally
            runState |= SHUTDOWN;
            lock.unlock();
        }
    }

    /**
     * Possibly initiates and/or completes termination.  Upon
     * termination, cancels all queued tasks and then
     *
     * @param now if true, unconditionally terminate, else only
     * if no work and no active workers
     * @return true if now terminating or terminated
     */
    private boolean tryTerminate(boolean now) {
        for (long c;;) {
            if (((c = ctl) & STOP_BIT) != 0) {      // already terminating
                if ((short)(c >>> TC_SHIFT) == -parallelism) {
                    ReentrantLock lock = this.lock; // signal when no workers
                    lock.lock();                    // don't need try/finally
                    termination.signalAll();        // signal when 0 workers
                    lock.unlock();
                }
                return true;
            }
            if (!now) {
                if ((int)(c >> AC_SHIFT) != -parallelism || runState >= 0 ||
                    hasQueuedSubmissions())
                    return false;
                // Check for unqueued inactive workers. One pass suffices.
                WorkQueue[] ws = workQueues; WorkQueue w;
                if (ws != null) {
                    int n = ws.length;
                    for (int i = 1; i < n; i += 2) {
                        if ((w = ws[i]) != null && w.eventCount >= 0)
                            return false;
                    }
                }
            }
            if (U.compareAndSwapLong(this, CTL, c, c | STOP_BIT))
                startTerminating();
        }
    }

    /**
     * Initiates termination: Runs three passes through workQueues:
     * (0) Setting termination status, followed by wakeups of queued
     * workers; (1) cancelling all tasks; (2) interrupting lagging
     * threads (likely in external tasks, but possibly also blocked in
     * joins).  Each pass repeats previous steps because of potential
     * lagging thread creation.
     */
    private void startTerminating() {
        for (int pass = 0; pass < 3; ++pass) {
            WorkQueue[] ws = workQueues;
            if (ws != null) {
                WorkQueue w; Thread wt;
                int n = ws.length;
                for (int j = 0; j < n; ++j) {
                    if ((w = ws[j]) != null) {
                        w.runState = -1;
                        if (pass > 0) {
                            w.cancelAll();
                            if (pass > 1 && (wt = w.owner) != null &&
                                !wt.isInterrupted()) {
                                try {
                                    wt.interrupt();
                                } catch (SecurityException ignore) {
                                }
                            }
                        }
                    }
                }
                // Wake up workers parked on event queue
                int i, e; long c; Thread p;
                while ((i = ((~(e = (int)(c = ctl)) << 1) | 1) & SMASK) < n &&
                       (w = ws[i]) != null &&
                       w.eventCount == (e | INT_SIGN)) {
                    long nc = ((long)(w.nextWait & E_MASK) |
                               ((c + AC_UNIT) & AC_MASK) |
                               (c & (TC_MASK|STOP_BIT)));
                    if (U.compareAndSwapLong(this, CTL, c, nc)) {
                        w.eventCount = (e + E_SEQ) & E_MASK;
                        if ((p = w.parker) != null)
                            U.unpark(p);
                    }
                }
            }
        }
    }

    // Exported methods

    // Constructors

    /**
     * Creates a {@code ForkJoinPool} with parallelism equal to {@link
     * java.lang.Runtime#availableProcessors}, using the {@linkplain
     * #defaultForkJoinWorkerThreadFactory default thread factory},
     * no UncaughtExceptionHandler, and non-async LIFO processing mode.
     *
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool() {
        this(Runtime.getRuntime().availableProcessors(),
             defaultForkJoinWorkerThreadFactory, null, false);
    }

    /**
     * Creates a {@code ForkJoinPool} with the indicated parallelism
     * level, the {@linkplain
     * #defaultForkJoinWorkerThreadFactory default thread factory},
     * no UncaughtExceptionHandler, and non-async LIFO processing mode.
     *
     * @param parallelism the parallelism level
     * @throws IllegalArgumentException if parallelism less than or
     *         equal to zero, or greater than implementation limit
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool(int parallelism) {
        this(parallelism, defaultForkJoinWorkerThreadFactory, null, false);
    }

    /**
     * Creates a {@code ForkJoinPool} with the given parameters.
     *
     * @param parallelism the parallelism level. For default value,
     * use {@link java.lang.Runtime#availableProcessors}.
     * @param factory the factory for creating new threads. For default value,
     * use {@link #defaultForkJoinWorkerThreadFactory}.
     * @param handler the handler for internal worker threads that
     * terminate due to unrecoverable errors encountered while executing
     * tasks. For default value, use {@code null}.
     * @param asyncMode if true,
     * establishes local first-in-first-out scheduling mode for forked
     * tasks that are never joined. This mode may be more appropriate
     * than default locally stack-based mode in applications in which
     * worker threads only process event-style asynchronous tasks.
     * For default value, use {@code false}.
     * @throws IllegalArgumentException if parallelism less than or
     *         equal to zero, or greater than implementation limit
     * @throws NullPointerException if the factory is null
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool(int parallelism,
                        ForkJoinWorkerThreadFactory factory,
                        Thread.UncaughtExceptionHandler handler,
                        boolean asyncMode) {
        checkPermission();
        if (factory == null)
            throw new NullPointerException();
        if (parallelism <= 0 || parallelism > MAX_ID)
            throw new IllegalArgumentException();
        this.parallelism = parallelism;
        this.factory = factory;
        this.ueh = handler;
        this.localMode = asyncMode ? FIFO_QUEUE : LIFO_QUEUE;
        this.nextPoolIndex = 1;
        long np = (long)(-parallelism); // offset ctl counts
        this.ctl = ((np << AC_SHIFT) & AC_MASK) | ((np << TC_SHIFT) & TC_MASK);
        // initialize workQueues array with room for 2*parallelism if possible
        int n = parallelism << 1;
        if (n >= MAX_ID)
            n = MAX_ID;
        else { // See Hackers Delight, sec 3.2, where n < (1 << 16)
            n |= n >>> 1; n |= n >>> 2; n |= n >>> 4; n |= n >>> 8;
        }
        this.workQueues = new WorkQueue[(n + 1) << 1];
        ReentrantLock lck = this.lock = new ReentrantLock();
        this.termination = lck.newCondition();
        this.stealCount = new AtomicLong();
        this.nextWorkerNumber = new AtomicInteger();
        StringBuilder sb = new StringBuilder("ForkJoinPool-");
        sb.append(poolNumberGenerator.incrementAndGet());
        sb.append("-worker-");
        this.workerNamePrefix = sb.toString();
        // Create initial submission queue
        WorkQueue sq = tryAddSharedQueue(0);
        if (sq != null)
            sq.growArray(false);
    }

    // Execution methods

    /**
     * Performs the given task, returning its result upon completion.
     * If the computation encounters an unchecked Exception or Error,
     * it is rethrown as the outcome of this invocation.  Rethrown
     * exceptions behave in the same way as regular exceptions, but,
     * when possible, contain stack traces (as displayed for example
     * using {@code ex.printStackTrace()}) of both the current thread
     * as well as the thread actually encountering the exception;
     * minimally only the latter.
     *
     * @param task the task
     * @return the task's result
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> T invoke(ForkJoinTask<T> task) {
        doSubmit(task);
        return task.join();
    }

    /**
     * Arranges for (asynchronous) execution of the given task.
     *
     * @param task the task
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public void execute(ForkJoinTask<?> task) {
        doSubmit(task);
    }

    // AbstractExecutorService methods

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public void execute(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        ForkJoinTask<?> job;
        if (task instanceof ForkJoinTask<?>) // avoid re-wrap
            job = (ForkJoinTask<?>) task;
        else
            job = ForkJoinTask.adapt(task, null);
        doSubmit(job);
    }

    /**
     * Submits a ForkJoinTask for execution.
     *
     * @param task the task to submit
     * @return the task
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(ForkJoinTask<T> task) {
        doSubmit(task);
        return task;
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(Callable<T> task) {
        if (task == null)
            throw new NullPointerException();
        ForkJoinTask<T> job = ForkJoinTask.adapt(task);
        doSubmit(job);
        return job;
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(Runnable task, T result) {
        if (task == null)
            throw new NullPointerException();
        ForkJoinTask<T> job = ForkJoinTask.adapt(task, result);
        doSubmit(job);
        return job;
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public ForkJoinTask<?> submit(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        ForkJoinTask<?> job;
        if (task instanceof ForkJoinTask<?>) // avoid re-wrap
            job = (ForkJoinTask<?>) task;
        else
            job = ForkJoinTask.adapt(task, null);
        doSubmit(job);
        return job;
    }

    /**
     * @throws NullPointerException       {@inheritDoc}
     * @throws RejectedExecutionException {@inheritDoc}
     */
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        ArrayList<ForkJoinTask<T>> forkJoinTasks =
            new ArrayList<ForkJoinTask<T>>(tasks.size());
        for (Callable<T> task : tasks)
            forkJoinTasks.add(ForkJoinTask.adapt(task));
        invoke(new InvokeAll<T>(forkJoinTasks));

        @SuppressWarnings({"unchecked", "rawtypes"})
            List<Future<T>> futures = (List<Future<T>>) (List) forkJoinTasks;
        return futures;
    }

    static final class InvokeAll<T> extends RecursiveAction {
        final ArrayList<ForkJoinTask<T>> tasks;
        InvokeAll(ArrayList<ForkJoinTask<T>> tasks) { this.tasks = tasks; }
        public void compute() {
            try { invokeAll(tasks); }
            catch (Exception ignore) {}
        }
        private static final long serialVersionUID = -7914297376763021607L;
    }

    /**
     * Returns the factory used for constructing new workers.
     *
     * @return the factory used for constructing new workers
     */
    public ForkJoinWorkerThreadFactory getFactory() {
        return factory;
    }

    /**
     * Returns the handler for internal worker threads that terminate
     * due to unrecoverable errors encountered while executing tasks.
     *
     * @return the handler, or {@code null} if none
     */
    public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return ueh;
    }

    /**
     * Returns the targeted parallelism level of this pool.
     *
     * @return the targeted parallelism level of this pool
     */
    public int getParallelism() {
        return parallelism;
    }

    /**
     * Returns the number of worker threads that have started but not
     * yet terminated.  The result returned by this method may differ
     * from {@link #getParallelism} when threads are created to
     * maintain parallelism when others are cooperatively blocked.
     *
     * @return the number of worker threads
     */
    public int getPoolSize() {
        return parallelism + (short)(ctl >>> TC_SHIFT);
    }

    /**
     * Returns {@code true} if this pool uses local first-in-first-out
     * scheduling mode for forked tasks that are never joined.
     *
     * @return {@code true} if this pool uses async mode
     */
    public boolean getAsyncMode() {
        return localMode != 0;
    }

    /**
     * Returns an estimate of the number of worker threads that are
     * not blocked waiting to join tasks or for other managed
     * synchronization. This method may overestimate the
     * number of running threads.
     *
     * @return the number of worker threads
     */
    public int getRunningThreadCount() {
        int rc = 0;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            int n = ws.length;
            for (int i = 1; i < n; i += 2) {
                Thread.State s; ForkJoinWorkerThread wt;
                if ((w = ws[i]) != null && (wt = w.owner) != null &&
                    w.eventCount >= 0 &&
                    (s = wt.getState()) != Thread.State.BLOCKED &&
                    s != Thread.State.WAITING &&
                    s != Thread.State.TIMED_WAITING)
                    ++rc;
            }
        }
        return rc;
    }

    /**
     * Returns an estimate of the number of threads that are currently
     * stealing or executing tasks. This method may overestimate the
     * number of active threads.
     *
     * @return the number of active threads
     */
    public int getActiveThreadCount() {
        int r = parallelism + (int)(ctl >> AC_SHIFT);
        return (r <= 0) ? 0 : r; // suppress momentarily negative values
    }

    /**
     * Returns {@code true} if all worker threads are currently idle.
     * An idle worker is one that cannot obtain a task to execute
     * because none are available to steal from other threads, and
     * there are no pending submissions to the pool. This method is
     * conservative; it might not return {@code true} immediately upon
     * idleness of all threads, but will eventually become true if
     * threads remain inactive.
     *
     * @return {@code true} if all threads are currently idle
     */
    public boolean isQuiescent() {
        return (int)(ctl >> AC_SHIFT) + parallelism == 0;
    }

    /**
     * Returns an estimate of the total number of tasks stolen from
     * one thread's work queue by another. The reported value
     * underestimates the actual total number of steals when the pool
     * is not quiescent. This value may be useful for monitoring and
     * tuning fork/join programs: in general, steal counts should be
     * high enough to keep threads busy, but low enough to avoid
     * overhead and contention across threads.
     *
     * @return the number of steals
     */
    public long getStealCount() {
        long count = stealCount.get();
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            int n = ws.length;
            for (int i = 1; i < n; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.totalSteals;
            }
        }
        return count;
    }

    /**
     * Returns an estimate of the total number of tasks currently held
     * in queues by worker threads (but not including tasks submitted
     * to the pool that have not begun executing). This value is only
     * an approximation, obtained by iterating across all threads in
     * the pool. This method may be useful for tuning task
     * granularities.
     *
     * @return the number of queued tasks
     */
    public long getQueuedTaskCount() {
        long count = 0;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            int n = ws.length;
            for (int i = 1; i < n; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.queueSize();
            }
        }
        return count;
    }

    /**
     * Returns an estimate of the number of tasks submitted to this
     * pool that have not yet begun executing.  This method may take
     * time proportional to the number of submissions.
     *
     * @return the number of queued submissions
     */
    public int getQueuedSubmissionCount() {
        int count = 0;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            int n = ws.length;
            for (int i = 0; i < n; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.queueSize();
            }
        }
        return count;
    }

    /**
     * Returns {@code true} if there are any tasks submitted to this
     * pool that have not yet begun executing.
     *
     * @return {@code true} if there are any queued submissions
     */
    public boolean hasQueuedSubmissions() {
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            int n = ws.length;
            for (int i = 0; i < n; i += 2) {
                if ((w = ws[i]) != null && w.queueSize() != 0)
                    return true;
            }
        }
        return false;
    }

    /**
     * Removes and returns the next unexecuted submission if one is
     * available.  This method may be useful in extensions to this
     * class that re-assign work in systems with multiple pools.
     *
     * @return the next submission, or {@code null} if none
     */
    protected ForkJoinTask<?> pollSubmission() {
        WorkQueue[] ws; WorkQueue w; ForkJoinTask<?> t;
        if ((ws = workQueues) != null) {
            int n = ws.length;
            for (int i = 0; i < n; i += 2) {
                if ((w = ws[i]) != null && (t = w.poll()) != null)
                    return t;
            }
        }
        return null;
    }

    /**
     * Removes all available unexecuted submitted and forked tasks
     * from scheduling queues and adds them to the given collection,
     * without altering their execution status. These may include
     * artificially generated or wrapped tasks. This method is
     * designed to be invoked only when the pool is known to be
     * quiescent. Invocations at other times may not remove all
     * tasks. A failure encountered while attempting to add elements
     * to collection {@code c} may result in elements being in
     * neither, either or both collections when the associated
     * exception is thrown.  The behavior of this operation is
     * undefined if the specified collection is modified while the
     * operation is in progress.
     *
     * @param c the collection to transfer elements into
     * @return the number of elements transferred
     */
    protected int drainTasksTo(Collection<? super ForkJoinTask<?>> c) {
        int count = 0;
        WorkQueue[] ws; WorkQueue w; ForkJoinTask<?> t;
        if ((ws = workQueues) != null) {
            int n = ws.length;
            for (int i = 0; i < n; ++i) {
                if ((w = ws[i]) != null) {
                    while ((t = w.poll()) != null) {
                        c.add(t);
                        ++count;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Returns a string identifying this pool, as well as its state,
     * including indications of run state, parallelism level, and
     * worker and task counts.
     *
     * @return a string identifying this pool, as well as its state
     */
    public String toString() {
        long st = getStealCount();
        long qt = getQueuedTaskCount();
        long qs = getQueuedSubmissionCount();
        int rc = getRunningThreadCount();
        int pc = parallelism;
        long c = ctl;
        int tc = pc + (short)(c >>> TC_SHIFT);
        int ac = pc + (int)(c >> AC_SHIFT);
        if (ac < 0) // ignore transient negative
            ac = 0;
        String level;
        if ((c & STOP_BIT) != 0)
            level = (tc == 0) ? "Terminated" : "Terminating";
        else
            level = runState < 0 ? "Shutting down" : "Running";
        return super.toString() +
            "[" + level +
            ", parallelism = " + pc +
            ", size = " + tc +
            ", active = " + ac +
            ", running = " + rc +
            ", steals = " + st +
            ", tasks = " + qt +
            ", submissions = " + qs +
            "]";
    }

    /**
     * Initiates an orderly shutdown in which previously submitted
     * tasks are executed, but no new tasks will be accepted.
     * Invocation has no additional effect if already shut down.
     * Tasks that are in the process of being submitted concurrently
     * during the course of this method may or may not be rejected.
     *
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public void shutdown() {
        checkPermission();
        enableShutdown();
        tryTerminate(false);
    }

    /**
     * Attempts to cancel and/or stop all tasks, and reject all
     * subsequently submitted tasks.  Tasks that are in the process of
     * being submitted or executed concurrently during the course of
     * this method may or may not be rejected. This method cancels
     * both existing and unexecuted tasks, in order to permit
     * termination in the presence of task dependencies. So the method
     * always returns an empty list (unlike the case for some other
     * Executors).
     *
     * @return an empty list
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public List<Runnable> shutdownNow() {
        checkPermission();
        enableShutdown();
        tryTerminate(true);
        return Collections.emptyList();
    }

    /**
     * Returns {@code true} if all tasks have completed following shut down.
     *
     * @return {@code true} if all tasks have completed following shut down
     */
    public boolean isTerminated() {
        long c = ctl;
        return ((c & STOP_BIT) != 0L &&
                (short)(c >>> TC_SHIFT) == -parallelism);
    }

    /**
     * Returns {@code true} if the process of termination has
     * commenced but not yet completed.  This method may be useful for
     * debugging. A return of {@code true} reported a sufficient
     * period after shutdown may indicate that submitted tasks have
     * ignored or suppressed interruption, or are waiting for IO,
     * causing this executor not to properly terminate. (See the
     * advisory notes for class {@link ForkJoinTask} stating that
     * tasks should not normally entail blocking operations.  But if
     * they do, they must abort them on interrupt.)
     *
     * @return {@code true} if terminating but not yet terminated
     */
    public boolean isTerminating() {
        long c = ctl;
        return ((c & STOP_BIT) != 0L &&
                (short)(c >>> TC_SHIFT) != -parallelism);
    }

    /**
     * Returns {@code true} if this pool has been shut down.
     *
     * @return {@code true} if this pool has been shut down
     */
    public boolean isShutdown() {
        return runState < 0;
    }

    /**
     * Blocks until all tasks have completed execution after a shutdown
     * request, or the timeout occurs, or the current thread is
     * interrupted, whichever happens first.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if this executor terminated and
     *         {@code false} if the timeout elapsed before termination
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            for (;;) {
                if (isTerminated())
                    return true;
                if (nanos <= 0)
                    return false;
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Interface for extending managed parallelism for tasks running
     * in {@link ForkJoinPool}s.
     *
     * <p>A {@code ManagedBlocker} provides two methods.  Method
     * {@code isReleasable} must return {@code true} if blocking is
     * not necessary. Method {@code block} blocks the current thread
     * if necessary (perhaps internally invoking {@code isReleasable}
     * before actually blocking). These actions are performed by any
     * thread invoking {@link ForkJoinPool#managedBlock}.  The
     * unusual methods in this API accommodate synchronizers that may,
     * but don't usually, block for long periods. Similarly, they
     * allow more efficient internal handling of cases in which
     * additional workers may be, but usually are not, needed to
     * ensure sufficient parallelism.  Toward this end,
     * implementations of method {@code isReleasable} must be amenable
     * to repeated invocation.
     *
     * <p>For example, here is a ManagedBlocker based on a
     * ReentrantLock:
     *  <pre> {@code
     * class ManagedLocker implements ManagedBlocker {
     *   final ReentrantLock lock;
     *   boolean hasLock = false;
     *   ManagedLocker(ReentrantLock lock) { this.lock = lock; }
     *   public boolean block() {
     *     if (!hasLock)
     *       lock.lock();
     *     return true;
     *   }
     *   public boolean isReleasable() {
     *     return hasLock || (hasLock = lock.tryLock());
     *   }
     * }}</pre>
     *
     * <p>Here is a class that possibly blocks waiting for an
     * item on a given queue:
     *  <pre> {@code
     * class QueueTaker<E> implements ManagedBlocker {
     *   final BlockingQueue<E> queue;
     *   volatile E item = null;
     *   QueueTaker(BlockingQueue<E> q) { this.queue = q; }
     *   public boolean block() throws InterruptedException {
     *     if (item == null)
     *       item = queue.take();
     *     return true;
     *   }
     *   public boolean isReleasable() {
     *     return item != null || (item = queue.poll()) != null;
     *   }
     *   public E getItem() { // call after pool.managedBlock completes
     *     return item;
     *   }
     * }}</pre>
     */
    public static interface ManagedBlocker {
        /**
         * Possibly blocks the current thread, for example waiting for
         * a lock or condition.
         *
         * @return {@code true} if no additional blocking is necessary
         * (i.e., if isReleasable would return true)
         * @throws InterruptedException if interrupted while waiting
         * (the method is not required to do so, but is allowed to)
         */
        boolean block() throws InterruptedException;

        /**
         * Returns {@code true} if blocking is unnecessary.
         */
        boolean isReleasable();
    }

    /**
     * Blocks in accord with the given blocker.  If the current thread
     * is a {@link ForkJoinWorkerThread}, this method possibly
     * arranges for a spare thread to be activated if necessary to
     * ensure sufficient parallelism while the current thread is blocked.
     *
     * <p>If the caller is not a {@link ForkJoinTask}, this method is
     * behaviorally equivalent to
     *  <pre> {@code
     * while (!blocker.isReleasable())
     *   if (blocker.block())
     *     return;
     * }</pre>
     *
     * If the caller is a {@code ForkJoinTask}, then the pool may
     * first be expanded to ensure parallelism, and later adjusted.
     *
     * @param blocker the blocker
     * @throws InterruptedException if blocker.block did so
     */
    public static void managedBlock(ManagedBlocker blocker)
        throws InterruptedException {
        Thread t = Thread.currentThread();
        ForkJoinPool p = ((t instanceof ForkJoinWorkerThread) ?
                          ((ForkJoinWorkerThread)t).pool : null);
        while (!blocker.isReleasable()) {
            if (p == null || p.tryCompensate()) {
                try {
                    do {} while (!blocker.isReleasable() && !blocker.block());
                } finally {
                    if (p != null)
                        p.incrementActiveCount();
                }
                break;
            }
        }
    }

    // AbstractExecutorService overrides.  These rely on undocumented
    // fact that ForkJoinTask.adapt returns ForkJoinTasks that also
    // implement RunnableFuture.

    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return (RunnableFuture<T>) ForkJoinTask.adapt(runnable, value);
    }

    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return (RunnableFuture<T>) ForkJoinTask.adapt(callable);
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    private static final long CTL;
    private static final long RUNSTATE;
    private static final long PARKBLOCKER;

    static {
        poolNumberGenerator = new AtomicInteger();
        modifyThreadPermission = new RuntimePermission("modifyThread");
        defaultForkJoinWorkerThreadFactory =
            new DefaultForkJoinWorkerThreadFactory();
        int s;
        try {
            U = getUnsafe();
            Class<?> k = ForkJoinPool.class;
            Class<?> tk = Thread.class;
            CTL = U.objectFieldOffset
                (k.getDeclaredField("ctl"));
            RUNSTATE = U.objectFieldOffset
                (k.getDeclaredField("runState"));
            PARKBLOCKER = U.objectFieldOffset
                (tk.getDeclaredField("parkBlocker"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    private static sun.misc.Unsafe getUnsafe() {
        return Unsafe.instance;
    }
}
