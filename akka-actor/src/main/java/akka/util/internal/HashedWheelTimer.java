/*
 * Copyright 2009 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package akka.util.internal;

import akka.event.LoggingAdapter;
import scala.concurrent.util.Duration;

import java.util.*;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 *
 * <h3>Tick Duration</h3>
 *
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.  {@link HashedWheelTimer}, on every tick, will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy of the execution timing by
 * specifying smaller or larger tick duration in the constructor.  In most
 * network applications, I/O timeout does not need to be accurate.  Therefore,
 * the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 *
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 *
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 *
 * <h3>Do not create many instances.</h3>
 *
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance in
 * {@link ChannelPipelineFactory}, which results in the creation of a new thread
 * for every connection.
 *
 * <h3>Implementation Details</h3>
 *
 * {@link HashedWheelTimer} is based on
 * <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="http://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 *
 * @author <a href="http://www.jboss.org/netty/">The Netty Project</a>
 * @author <a href="http://gleamynode.net/">Trustin Lee</a>
 * @version $Rev: 2297 $, $Date: 2010-06-07 10:50:02 +0900 (Mon, 07 Jun 2010) $
 *
 * The original implementation has been slightly altered to fit the specific requirements of Akka.
 * 
 * Specifically: it is required to throw an IllegalStateException if a job 
 * cannot be queued. If no such exception is thrown, the job must be executed
 * (or returned upon stop()).
 */
public class HashedWheelTimer implements Timer {
    private final Worker worker = new Worker();
    final Thread workerThread;
    boolean shutdown = false;
    private final long roundDuration;
    final long tickDuration;
    final Set<HashedWheelTimeout>[] wheel;
    final ReusableIterator<HashedWheelTimeout>[] iterators;
    final int mask;
    final ReadWriteLock lock = new ReentrantReadWriteLock();
    final boolean isWindows = System.getProperty("os.name", "").toLowerCase().indexOf("win") >= 0;
    volatile int wheelCursor;
    private LoggingAdapter logger;

    /**
     * Creates a new timer.
     *
     * @param threadFactory  a {@link java.util.concurrent.ThreadFactory} that creates a
     *                       background {@link Thread} which is dedicated to
     *                       {@link TimerTask} execution.
     * @param duration       the duration between ticks
     * @param ticksPerWheel  the size of the wheel
     */
    public HashedWheelTimer(
            LoggingAdapter logger,
            ThreadFactory threadFactory,
            Duration duration,
            int ticksPerWheel) {

        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }
        if (duration == null) {
            throw new NullPointerException("duration");
        }
        if (duration.toNanos() <= 0) {
            throw new IllegalArgumentException("duration must be greater than 0 ns: " + duration.toNanos());
        }
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }

        this.logger = logger;

        // Normalize ticksPerWheel to power of two and initialize the wheel.
        wheel = createWheel(ticksPerWheel);
        iterators = createIterators(wheel);
        mask = wheel.length - 1;

        // Convert to standardized tickDuration
        this.tickDuration = duration.toNanos();

        // Prevent overflow.
        if (tickDuration == Long.MAX_VALUE || tickDuration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException("tickDuration is too long: " + tickDuration +  ' ' + duration.unit());
        }

        roundDuration = tickDuration * wheel.length;
        workerThread = threadFactory.newThread(worker);
    }

    @SuppressWarnings("unchecked")
    private static Set<HashedWheelTimeout>[] createWheel(int ticksPerWheel) {
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException(
                    "ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }
        if (ticksPerWheel > 1073741824) {
            throw new IllegalArgumentException(
                    "ticksPerWheel may not be greater than 2^30: " + ticksPerWheel);
        }

        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        Set<HashedWheelTimeout>[] wheel = new Set[ticksPerWheel];
        for (int i = 0; i < wheel.length; i ++) {
            wheel[i] = Collections.newSetFromMap(new ConcurrentIdentityHashMap<HashedWheelTimeout, Boolean>(16, 0.95f, 4));
        }
        return wheel;
    }

    @SuppressWarnings("unchecked")
    private static ReusableIterator<HashedWheelTimeout>[] createIterators(Set<HashedWheelTimeout>[] wheel) {
        ReusableIterator<HashedWheelTimeout>[] iterators = new ReusableIterator[wheel.length];
        for (int i = 0; i < wheel.length; i ++) {
            iterators[i] = (ReusableIterator<HashedWheelTimeout>) wheel[i].iterator();
        }
        return iterators;
    }

    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = 1;
        while (normalizedTicksPerWheel < ticksPerWheel) {
            normalizedTicksPerWheel <<= 1;
        }
        return normalizedTicksPerWheel;
    }

    /**
     * Starts the background thread explicitly.  The background thread will
     * start automatically on demand even if you did not call this method.
     *
     * @throws IllegalStateException if this timer has been
     *                               {@linkplain #stop() stopped} already
     */
    public synchronized void start() {
        lock.readLock().lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("cannot be started once stopped");
            }

            if (!workerThread.isAlive()) {
                workerThread.start();
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    public synchronized Set<Timeout> stop() {
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(
                    HashedWheelTimer.class.getSimpleName() +
                    ".stop() cannot be called from " +
                    TimerTask.class.getSimpleName());
        }

        lock.writeLock().lock();
        try {
            if (shutdown) {
                return Collections.emptySet();
            } else {
                shutdown = true;
            }
        } finally {
            lock.writeLock().unlock();
        }

        boolean interrupted = false;
        while (workerThread.isAlive()) {
            workerThread.interrupt();
            try {
                workerThread.join(100);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();
        for (Set<HashedWheelTimeout> bucket: wheel) {
            unprocessedTimeouts.addAll(bucket);
            bucket.clear();
        }

        return Collections.unmodifiableSet(unprocessedTimeouts);
    }
    
    public HashedWheelTimeout createTimeout(TimerTask task, long time) {
        return new HashedWheelTimeout(task, time);
    }

    public Timeout newTimeout(TimerTask task, Duration delay) {
        final long currentTime = System.nanoTime();

        if (task == null) {
            throw new NullPointerException("task");
        }
        if (delay == null) {
            throw new NullPointerException("delay");
        }

        if (!workerThread.isAlive()) {
            start();
        }

        HashedWheelTimeout timeout = createTimeout(task, currentTime + delay.toNanos());
        scheduleTimeout(timeout, delay.toNanos());
        return timeout;
    }

    void scheduleTimeout(HashedWheelTimeout timeout, long delay) {
        // delay must be equal to or greater than tickDuration so that the
        // worker thread never misses the timeout.
        if (delay < tickDuration) {
            delay = tickDuration;
        }

        // Prepare the required parameters to schedule the timeout object.
        final long lastRoundDelay = delay % roundDuration;
        final long lastTickDelay = delay % tickDuration;
        final long relativeIndex = lastRoundDelay / tickDuration + (lastTickDelay != 0? 1 : 0);
        final long remainingRounds = delay / roundDuration - (delay % roundDuration == 0? 1 : 0);

        // Add the timeout to the wheel.
        lock.readLock().lock();
        try {
            if (shutdown) throw new IllegalStateException("cannot enqueue after shutdown");
            int stopIndex = (int) (wheelCursor + relativeIndex & mask);
            timeout.stopIndex = stopIndex;
            timeout.remainingRounds = remainingRounds;
            wheel[stopIndex].add(timeout);
        } finally {
            lock.readLock().unlock();
        }
    }

    private final class Worker implements Runnable {

        private long startTime;
        private long tick;

        Worker() {
            super();
        }
        
        private boolean shutdown() {
            lock.readLock().lock();
            try {
                return shutdown;
            } finally {
                lock.readLock().unlock();
            }
        }

        public void run() {
            List<HashedWheelTimeout> expiredTimeouts =
                new ArrayList<HashedWheelTimeout>();

            startTime = System.nanoTime();
            tick = 1;

            while (!shutdown()) {
                final long deadline = waitForNextTick();
                if (deadline > 0) {
                    fetchExpiredTimeouts(expiredTimeouts, deadline);
                    notifyExpiredTimeouts(expiredTimeouts);
                }
            }
        }

        private void fetchExpiredTimeouts(List<HashedWheelTimeout> expiredTimeouts, long deadline) {

            // Find the expired timeouts and decrease the round counter
            // if necessary.  Note that we don't send the notification
            // immediately to make sure the listeners are called without
            // an exclusive lock.
            lock.writeLock().lock();
            try {
                int newWheelCursor = wheelCursor = wheelCursor + 1 & mask;
                ReusableIterator<HashedWheelTimeout> i = iterators[newWheelCursor];
                fetchExpiredTimeouts(expiredTimeouts, i, deadline);
            } finally {
                lock.writeLock().unlock();
            }
        }

        private void fetchExpiredTimeouts(
                List<HashedWheelTimeout> expiredTimeouts,
                ReusableIterator<HashedWheelTimeout> i, long deadline) {

            List<HashedWheelTimeout> slipped = null;
            i.rewind();
            while (i.hasNext()) {
                HashedWheelTimeout timeout = i.next();
                if (timeout.remainingRounds <= 0) {
                    i.remove();
                    if (timeout.deadline <= deadline) {
                        expiredTimeouts.add(timeout);
                    } else {
                        // Handle the case where the timeout is put into a wrong
                        // place, usually one tick earlier.  For now, just add
                        // it to a temporary list - we will reschedule it in a
                        // separate loop.
                        if (slipped == null) {
                            slipped = new ArrayList<HashedWheelTimeout>();
                        }
                        slipped.add(timeout);
                    }
                } else {
                    timeout.remainingRounds --;
                }
            }

            // Reschedule the slipped timeouts.
            if (slipped != null) {
                for (HashedWheelTimeout timeout: slipped) {
                    scheduleTimeout(timeout, timeout.deadline - deadline);
                }
            }
        }

        private void notifyExpiredTimeouts(
                List<HashedWheelTimeout> expiredTimeouts) {
            // Notify the expired timeouts.
            for (int i = expiredTimeouts.size() - 1; i >= 0; i --) {
                expiredTimeouts.get(i).expire();
            }

            // Clean up the temporary list.
            expiredTimeouts.clear();
        }

        private long waitForNextTick() {
            long deadline = startTime + tickDuration * tick;

            for (;;) {
                final long currentTime = System.nanoTime();

                long sleepTime = tickDuration * tick - (currentTime - startTime);

                // Check if we run on windows, as if thats the case we will need
                // to round the sleepTime as workaround for a bug that only affect
                // the JVM if it runs on windows.
                //
                // See https://github.com/netty/netty/issues/356
                if (isWindows) {
                  sleepTime = (sleepTime / 10) * 10;
                }

                if (sleepTime <= 0) {
                    break;
                }

                try {
                    long milliSeconds = TimeUnit.NANOSECONDS.toMillis(sleepTime);
                    int nanoSeconds = (int) (sleepTime - (milliSeconds * 1000000));
                    Thread.sleep(milliSeconds, nanoSeconds);
                } catch (InterruptedException e) {
                    if (shutdown()) {
                        return -1;
                    }
                }
            }

            // Increase the tick.
            tick ++;
            return deadline;
        }
    }

    private final class HashedWheelTimeout implements Timeout {

        private static final int ST_INIT = 0;
        private static final int ST_CANCELLED = 1;
        private static final int ST_EXPIRED = 2;

        private final TimerTask task;
        final long deadline;
        volatile int stopIndex;
        volatile long remainingRounds;
        private final AtomicInteger state = new AtomicInteger(ST_INIT);

        HashedWheelTimeout(TimerTask task, long deadline) {
            this.task = task;
            this.deadline = deadline;
        }

        public Timer getTimer() {
            return HashedWheelTimer.this;
        }

        public TimerTask getTask() {
            return task;
        }

        public void cancel() {
            if (!state.compareAndSet(ST_INIT, ST_CANCELLED)) {
                // TODO return false
                return;
            }

            wheel[stopIndex].remove(this);
        }

        public boolean isCancelled() {
            return state.get() == ST_CANCELLED;
        }

        public boolean isExpired() {
            return state.get() != ST_INIT;
        }

        public void expire() {
            if (!state.compareAndSet(ST_INIT, ST_EXPIRED)) {
                return;
            }

            try {
                task.run(this);
            } catch (Throwable t) {
                logger.warning(
                        "An exception was thrown by " +
                        TimerTask.class.getSimpleName() + ".", t);
            }
        }

        @Override
        public String toString() {
            long currentTime = System.nanoTime();
            long remaining = deadline - currentTime;

            StringBuilder buf = new StringBuilder(192);
            buf.append(getClass().getSimpleName());
            buf.append('(');

            buf.append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining);
                buf.append(" ns later, ");
            } else if (remaining < 0) {
                buf.append(-remaining);
                buf.append(" ns ago, ");
            } else {
                buf.append("now, ");
            }

            if (isCancelled()) {
                buf.append (", cancelled");
            }

            return buf.append(')').toString();
        }
    }
}

