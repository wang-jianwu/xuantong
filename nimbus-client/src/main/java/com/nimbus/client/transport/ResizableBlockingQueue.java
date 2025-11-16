package com.nimbus.client.transport;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 可动态调整大小的阻塞队列（支持优雅缩容和监控）
 */
public class ResizableBlockingQueue<T> implements BlockingQueue<T> {
    private final LinkedBlockingQueue<T> queue;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile int maxSize;
    private final AtomicInteger currentSize = new AtomicInteger(0);
    public ResizableBlockingQueue(int initialSize, int maxSize) {
        this.queue = new LinkedBlockingQueue<>(initialSize);
        this.maxSize = maxSize;
    }

    public void resize(int newMaxSize) {
        lock.lock();
        try {
            int oldMaxSize = this.maxSize;
            this.maxSize = newMaxSize;

            // 缩容处理
            if (newMaxSize < oldMaxSize) {
                int toRemove = Math.max(0, currentSize.get() - newMaxSize);
                for (int i = 0; i < toRemove; i++) {
                    T element = queue.poll();
                    if (element != null) {
                        currentSize.decrementAndGet();
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }


    @Override
    public boolean offer(T t) {
        lock.lock();
        try {
            if (currentSize.get() >= maxSize) {
                return false;
            }
            boolean offered = queue.offer(t);
            if (offered) {
                currentSize.incrementAndGet();
            }
            return offered;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean offer(T t, long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            if (currentSize.get() >= maxSize) {
                return false;
            }
            boolean offered = queue.offer(t, timeout, unit);
            if (offered) {
                currentSize.incrementAndGet();
            }
            return offered;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        T element = queue.poll(timeout, unit);
        if (element != null) {
            currentSize.decrementAndGet();
        }
        return element;
    }

    @Override
    public T poll() {
        T element = queue.poll();
        if (element != null) {
            currentSize.decrementAndGet();
        }
        return element;
    }

    @Override
    public T take() throws InterruptedException {
        T element = queue.take();
        currentSize.decrementAndGet();
        return element;
    }

    // 以下为委托方法
    @Override
    public void put(T t) throws InterruptedException {
        queue.put(t);
        currentSize.incrementAndGet();
    }

    @Override
    public int remainingCapacity() {
        return maxSize - currentSize.get();
    }

    @Override
    public boolean remove(Object o) {
        boolean removed = queue.remove(o);
        if (removed) {
            currentSize.decrementAndGet();
        }
        return removed;
    }

    @Override
    public boolean contains(Object o) {
        return queue.contains(o);
    }

    @Override
    public int drainTo(Collection<? super T> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super T> c, int maxElements) {
        int count = queue.drainTo(c, maxElements);
        currentSize.addAndGet(-count);
        return count;
    }

    @Override
    public void clear() {
        queue.clear();
        currentSize.set(0);
    }

    @Override
    public int size() {
        return currentSize.get();
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public Iterator<T> iterator() {
        return queue.iterator();
    }

    @Override
    public Object[] toArray() {
        return queue.toArray();
    }

    @Override
    public <T1> T1[] toArray(T1[] a) {
        return queue.toArray(a);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return queue.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        boolean modified = queue.addAll(c);
        if (modified) {
            currentSize.addAndGet(c.size());
        }
        return modified;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean modified = queue.removeAll(c);
        if (modified) {
            currentSize.set(queue.size());
        }
        return modified;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        boolean modified = queue.retainAll(c);
        if (modified) {
            currentSize.set(queue.size());
        }
        return modified;
    }

    @Override
    public boolean add(T t) {
        boolean added = queue.add(t);
        if (added) {
            currentSize.incrementAndGet();
        }
        return added;
    }

    @Override
    public T remove() {
        T element = queue.remove();
        currentSize.decrementAndGet();
        return element;
    }

    @Override
    public T element() {
        return queue.element();
    }

    @Override
    public T peek() {
        return queue.peek();
    }
}