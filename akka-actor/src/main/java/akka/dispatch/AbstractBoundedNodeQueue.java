/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch;

import akka.util.Unsafe;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free bounded non-blocking multiple-producer single-consumer queue inspired by the works of:
 *
 * Andriy Plokhotnuyk (https://github.com/plokhotnyuk)
 *   - https://github.com/plokhotnyuk/actors/blob/master/src/test/scala/akka/dispatch/NonBlockingBoundedMailbox.scala
 *     (Apache V2)
 *
 * Dmitriy Vyukov's non-intrusive MPSC queue:
 *   - http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue
 *   (Simplified BSD)
 */
@SuppressWarnings("serial")
public abstract class AbstractBoundedNodeQueue<T> extends AtomicReference<AbstractBoundedNodeQueue.Node<T>> {
    private final int capacity;

    @SuppressWarnings("unused")
    private volatile Node<T> _enqDoNotCallMeDirectly;

    protected AbstractBoundedNodeQueue(final int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("AbstractBoundedNodeQueue.capacity must be >= 0");
        this.capacity = capacity;
        final Node<T> n = new Node<T>();
        setDeq(n);
        setEnq(n);
    }

    private final void setEnq(Node<T> n) {
        Unsafe.instance.putObjectVolatile(this, enqOffset, n);
    }

    @SuppressWarnings("unchecked")
    private final Node<T> getEnq() {
        return (Node<T>)Unsafe.instance.getObjectVolatile(this, enqOffset);
    }

    private final boolean casEnq(Node<T> old, Node<T> nju) {
        return Unsafe.instance.compareAndSwapObject(this, enqOffset, old, nju);
    }

    private final void setDeq(Node<T> n) {
        set(n);
    }

    private final Node<T> getDeq() {
        return get();
    }

    private final Node<T> popDeq(Node<T> nju) {
        return getAndSet(nju);
    }

    @SuppressWarnings("unchecked")
    protected final Node<T> peekNode() {
        for(;;) {
          final Node<T> deq = getDeq();
          final Node<T> next = deq.next();
          if (next != null || getEnq() == deq)
            return next;
        }
    }

    public final T peek() {
        final Node<T> n = peekNode();
        return (n != null) ? n.value : null;
    }

    public final int capacity() {
        return capacity;
    }

    public final boolean add(final T value) {
        for(Node<T> n = null;;) {
            final Node<T> lastNode = getEnq();
            final long lastNodeCount = lastNode.count;
            if (lastNodeCount - getDeq().count < capacity) {
              // Trade a branch for avoiding to create a new node if full,
              // and to avoid creating multiple nodes on write conflict á la Be Kind to Your GC
              if (n == null) {
                  n = new Node<T>();
                  n.value = value;
              }

              n.count = lastNodeCount + 1; // Piggyback on the HB-edge between getEnq() and casEnq()

              // Try to append the node to the end, if we fail we continue loopin'
              if(casEnq(lastNode, n)) {
                  lastNode.setNext(n);
                  return true;
              }
            } else return false; // Over capacity—couldn't add the node
        }
    }

     public final boolean addNode(final Node<T> n) {
         for(;;) {
             final Node<T> lastNode = getEnq();
             final long lastNodeCount = lastNode.count;
             if (lastNodeCount - getDeq().count < capacity) {
                 n.count = lastNodeCount + 1; // Piggyback on the HB-edge between getEnq() and casEnq()
                 n.setNext(null);

                 // Try to append the node to the end, if we fail we continue loopin'
                 if(casEnq(lastNode, n)) {
                     lastNode.setNext(n);
                     return true;
                 }
             } else return false; // Over capacity—couldn't add the node
         }
    }

    public final boolean isEmpty() {
        return peek() == null;
    }

    /**
     * Will return Integer.MAX_VALUE is current count exceeds Integer.MAX_VALUE
     */
    public final int size() {
        return (int)Math.min(getEnq().count - getDeq().count, (long)Integer.MAX_VALUE);
    }

    /*
     * !!! There is a copy of this code in pollNode() !!!
     */
    public final T poll() {
        final Node<T> n = pollNode();
        return (n != null) ? n.value : null;
    }

    public final Node<T> pollNode() {
        for(;;) {
            final Node<T> deq = getDeq();
            final Node<T> next = deq.next();
            if (next != null) {
                final Node<T> wasDeq = popDeq(next);
                if (wasDeq == deq) {
                    deq.value = next.value;
                    next.value = null;
                    return deq;
                } else throw new IllegalStateException("Concurrent consumers detected!");
            } else if (getEnq() == deq) return null; // If we got a null and head meets tail, we are empty
        }
    }

    private final static long enqOffset;

    static {
        try {
          enqOffset = Unsafe.instance.objectFieldOffset(AbstractBoundedNodeQueue.class.getDeclaredField("_enqDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }

    public static class Node<T> {
        protected T value;
        @SuppressWarnings("unused")
        private volatile Node<T> _nextDoNotCallMeDirectly;
        // Might as well be a long, objects are aligned to 8-byte boundaries and we already have 2 references
        protected long count;

        @SuppressWarnings("unchecked")
        public final Node<T> next() {
            return (Node<T>)Unsafe.instance.getObjectVolatile(this, nextOffset);
        }

        protected final void setNext(final Node<T> newNext) {
          Unsafe.instance.putOrderedObject(this, nextOffset, newNext);
        }
        
        private final static long nextOffset;
        
        static {
            try {
                nextOffset = Unsafe.instance.objectFieldOffset(Node.class.getDeclaredField("_nextDoNotCallMeDirectly"));
            } catch(Throwable t){
                throw new ExceptionInInitializerError(t);
            } 
        }
    } 
}
