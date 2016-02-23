/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.dispatch;

import akka.util.Unsafe;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free MPSC linked queue implementation based on Dmitriy Vyukov's non-intrusive MPSC queue:
 * http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue
 */
@SuppressWarnings("serial")
public abstract class AbstractNodeQueue<T> extends AtomicReference<AbstractNodeQueue.Node<T>> {
    // Extends AtomicReference for the "head" slot (which is the one that is appended to) since
    // Unsafe does not expose XCHG operation intrinsically before JDK 8
    @SuppressWarnings("unused")
    private volatile Node<T> _tailDoNotCallMeDirectly;

    protected AbstractNodeQueue() {
       final Node<T> n = new Node<T>();
       _tailDoNotCallMeDirectly = n;
       set(n);
    }

    /*
     * Use this method only from the consumer thread!
     * 
     * !!! There is a copy of this code in pollNode() !!!
     */
    @SuppressWarnings("unchecked")
    protected final Node<T> peekNode() {
        final Node<T> tail = ((Node<T>)Unsafe.instance.getObjectVolatile(this, tailOffset));
        Node<T> next = tail.next();
        if (next == null && get() != tail) {
            // if tail != head this is not going to change until producer makes progress
            // we can avoid reading the head and just spin on next until it shows up
            do {
                next = tail.next();
            } while (next == null);
        }
        return next;
    }
    /*
     * Use this method only from the consumer thread!
     */
    public final T peek() {
        final Node<T> n = peekNode();
        return (n != null) ? n.value : null;
    }

    public final void add(final T value) {
        final Node<T> n = new Node<T>(value);
        getAndSet(n).setNext(n);
    }
    
    public final void addNode(final Node<T> n) {
        n.setNext(null);
        getAndSet(n).setNext(n);
    }

    public final boolean isEmpty() {
        return Unsafe.instance.getObjectVolatile(this, tailOffset) == get();
    }

    public final int count() {
        int count = 0;
        for(Node<T> n = peekNode();n != null && count < Integer.MAX_VALUE; n = n.next())
          ++count;
        return count;
    }

    /*
     * Use this method only from the consumer thread!
     */
    public final T poll() {
        final Node<T> next = pollNode();
        if (next == null) return null;
        else {
          T value = next.value;
          next.value = null;
          return value;
        }
    }
    
    /*
     * Use this method only from the consumer thread!
     */
    @SuppressWarnings("unchecked")
    public final Node<T> pollNode() {
      Node<T> tail;
      Node<T> next;
      for(;;) {
        tail = ((Node<T>)Unsafe.instance.getObjectVolatile(this, tailOffset));
        next = tail.next();
        if (next != null || get() == tail)
          break;
      }
      if (next == null) return null;
      else {
        tail.value = next.value;
        next.value = null;
        Unsafe.instance.putOrderedObject(this, tailOffset, next);
        tail.setNext(null);
        return tail;
      }
    }

    private final static long tailOffset;

    static {
        try {
          tailOffset = Unsafe.instance.objectFieldOffset(AbstractNodeQueue.class.getDeclaredField("_tailDoNotCallMeDirectly"));
        } catch(Throwable t){
            throw new ExceptionInInitializerError(t);
        }
    }

    public static class Node<T> {
        public T value;
        @SuppressWarnings("unused")
        private volatile Node<T> _nextDoNotCallMeDirectly;

        public Node() {
            this(null);
        }

        public Node(final T value) {
            this.value = value;
        }

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
