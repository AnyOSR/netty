/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);
    private static final Handle NOOP_HANDLE = new Handle() { };
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    private static final int DEFAULT_INITIAL_MAX_CAPACITY = 32768; // Use 32k instances as default max capacity.
    private static final int DEFAULT_MAX_CAPACITY;
    private static final int INITIAL_CAPACITY;
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    private static final int LINK_CAPACITY;
    private static final int RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacity = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity.default", DEFAULT_INITIAL_MAX_CAPACITY);
        if (maxCapacity < 0) {
            maxCapacity = DEFAULT_INITIAL_MAX_CAPACITY;
        }
        DEFAULT_MAX_CAPACITY = maxCapacity;

        MAX_SHARED_CAPACITY_FACTOR = max(2, SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor", 2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0, SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacity.default: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacity.default: {}", DEFAULT_MAX_CAPACITY);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY, 256);
    }

    private final int maxCapacity;
    private final int maxSharedCapacityFactor;
    private final int ratioMask;                      // 最后几位都是1
    private final int maxDelayedQueuesPerThread;

    //线程局部变量 存储了一个 Stack<T> ，Stack里面存的是对象池里面的对象？
    //真正的存储空间？
    //每一个recycler实例里面都有一个threadlocal，这样就不会有冲突了
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacity, maxSharedCapacityFactor, ratioMask, maxDelayedQueuesPerThread);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY);
    }

    protected Recycler(int maxCapacity) {
        this(maxCapacity, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacity, int maxSharedCapacityFactor) {
        this(maxCapacity, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacity, int maxSharedCapacityFactor, int ratio, int maxDelayedQueuesPerThread) {
        ratioMask = safeFindNextPositivePowerOfTwo(ratio) - 1;
        if (maxCapacity <= 0) {
            this.maxCapacity = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacity = maxCapacity;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    @SuppressWarnings("unchecked")
    //从对象池中获取一个对象
    public final T get() {
        if (maxCapacity == 0) {
            return newObject(NOOP_HANDLE);
        }
        Stack<T> stack = threadLocal.get();   //获取到当前线程的stack<T>
        DefaultHandle handle = stack.pop();
        if (handle == null) {
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }

    public final boolean recycle(T o, Handle handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle h = (DefaultHandle) handle;
        if (h.stack.parent != this) {
            return false;
        }
        if (o != h.value) {
            throw new IllegalArgumentException("o does not belong to handle");
        }
        h.recycle();
        return true;
    }

    protected abstract T newObject(Handle handle);

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    public interface Handle { }

    static final class DefaultHandle implements Handle {
        private int lastRecycledId;
        private int recycleId;

        boolean hasBeenRecycled;

        private Stack<?> stack;
        private Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        public void recycle() {
            stack.push(this);
        }
    }

    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED = new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        @SuppressWarnings("serial")
        private static final class Link extends AtomicInteger {
            private final DefaultHandle[] elements = new DefaultHandle[LINK_CAPACITY];

            private int readIndex;
            private Link next;
        }
        // chain of data items
        private Link head, tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;

        private final WeakReference<Thread> owner;
        private final int id = ID_GENERATOR.getAndIncrement();               //每个WeakOrderQueue都有自己的id
        private final AtomicInteger availableSharedCapacity;

        private WeakOrderQueue() {
            owner = null;
            availableSharedCapacity = null;
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            head = tail = new Link();
            owner = new WeakReference<Thread>(thread);

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            availableSharedCapacity = stack.availableSharedCapacity;
        }

        //新建一个WeakOrderQueue，这个queue拥有thread的weak引用
        //将生成的queue设置成stack的head头节点
        //返回生成的这个queue
        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            stack.setHead(queue);
            return queue;
        }

        private void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        //stack和由其分配的queue，共享同一个availableSharedCapacity
        //当前stack是否还有LINK_CAPACITY的剩余空间，
        //有则新建queue，并将其设置成stack的head节点，否则，返回null
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            return reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY) ? newQueue(stack, thread) : null;
        }

        //检查capacity是否足够
        //足够则返回true，并减去可用的capacity值 分配空间
        private static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
            assert space >= 0;
            for (;;) {
                int available = availableSharedCapacity.get();
                if (available < space) {
                    return false;
                }
                if (availableSharedCapacity.compareAndSet(available, available - space)) {
                    return true;
                }
            }
        }

        //回收空间
        private void reclaimSpace(int space) {
            assert space >= 0;
            availableSharedCapacity.addAndGet(space);
        }

        //将handle加入到queue中，实际上是写入到queue里面的link.elements中
        //将数据加入到 queue中的时候，会将lastRecycledId置为当前queue的id
        void add(DefaultHandle handle) {
            handle.lastRecycledId = id;       // 每个WeakOrderQueue都有自己独有的id 有可能造成lastRecycledId和recycleId不一致

            Link tail = this.tail;
            int writeIndex;
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {                   //如果tail已经写到末尾了
                if (!reserveSpace(availableSharedCapacity, LINK_CAPACITY)) {    //当前queue没有剩余空间，直接返回
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = new Link();                   //否则，新建link,将其设置成queue的tail节点

                writeIndex = tail.get();         //新建link的writeIndex为0 atomicInteger的初始值为0，如果没有指定的话
            }
            tail.elements[writeIndex] = handle;   //将handle写入link中
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            tail.lazySet(writeIndex + 1);   //将writeIndex加1
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        //queue的实际数据储存场所是link
        //将link中的数据 transfer到stack中
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            Link head = this.head;     //从queue.head开始遍历
            if (head == null) {
                return false;
            }

            if (head.readIndex == LINK_CAPACITY) {  //如果当前link已经被读取过了，则设置head.next为head
                if (head.next == null) {
                    return false;
                }
                this.head = head = head.next;
            }

            final int srcStart = head.readIndex;         //读位置
            int srcEnd = head.get();                     //写位置
            final int srcSize = srcEnd - srcStart;       //可读size
            if (srcSize == 0) {
                return false;
            }

            final int dstSize = dst.size;                     //stack.size
            final int expectedCapacity = dstSize + srcSize;   //需要的capacity

            if (expectedCapacity > dst.elements.length) {    //需要的capacity大于stack的容纳能力
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);  //扩容到expectedCapacity
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);          // actualCapacity - dstSize + srcStart  实际link上能读到的位置
            }                                                          // 此时link上的数据不一定完全读完，可能只读了link上的一部分数据，如果真是这样，则stack一定达到了最大capacity

            if (srcStart != srcEnd) {                                  //如果能读到元素
                final DefaultHandle[] srcElems = head.elements;     //link上的待读数据源
                final DefaultHandle[] dstElems = dst.elements;      //stack上的待写数据源
                int newDstSize = dstSize;                          //stack下一个待写位置
                for (int i = srcStart; i < srcEnd; i++) {     //遍历待写数据源
                    DefaultHandle element = srcElems[i];      //待写数据
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null;

                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;
                    dstElems[newDstSize ++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) { //恰好将当前link的数据读完，且还有下一个link
                    // Add capacity back as the Link is GCed.
                    reclaimSpace(LINK_CAPACITY);

                    this.head = head.next;
                }

                head.readIndex = srcEnd;          // 设置head的读位置
                if (dst.size == newDstSize) {     // 没有transfer到数据
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                super.finalize();
            } finally {
                // We need to reclaim all space that was reserved by this WeakOrderQueue so we not run out of space in
                // the stack. This is needed as we not have a good life-time control over the queue as it is used in a
                // WeakHashMap which will drop it at any time.
                Link link = head;
                while (link != null) {
                    reclaimSpace(LINK_CAPACITY);
                    link = link.next;
                }
            }
        }
    }

    static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        final WeakReference<Thread> threadRef;
        final AtomicInteger availableSharedCapacity;
        final int maxDelayedQueues;
        private final int maxCapacity;
        private final int ratioMask;
        private int size;                    // 下一次 DefaultHandle push的位置   实现类似stack的功能
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.

        private DefaultHandle[] elements;         //stack储存数据的场所 数据(handle)在stack和queue之间流转 stack.elements  queue.link.elements
        private WeakOrderQueue cursor, prev;
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor, int ratioMask, int maxDelayedQueues) {
            this.parent = parent;
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.ratioMask = ratioMask;
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        // 返回increase之后的数组数
        // 有可能会减小，也可能不变，也可能变大，取决于入参以及maxCapacity的值
        // 返回扩容之后的实际capacity
        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;    //原来的handle数
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity); //直到newCapacity >= expectedCapacity或者newCapacity >= maxCapacity

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        //会校验lastRecycledId和recycleId是否相等
        //从stack中pop的时候，将recycleId和lastRecycledId置为0
        DefaultHandle pop() {
            int size = this.size;
            if (size == 0) {
                if (!scavenge()) {
                    return null;
                }
                size = this.size;
            }
            size --;                             // 得到读位置
            DefaultHandle ret = elements[size];
            elements[size] = null;
            if (ret.lastRecycledId != ret.recycleId) {  //不相等，则表示已经多次recycle了
                throw new IllegalStateException("recycled multiple times");
            }
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            this.size = size;                  //设置写位置
            return ret;
        }

        boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        //queue： prev cursor next
        boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {   //如果当前游标为null,则将prev置为null，将游标置为head,从头开始遍历
                prev = null;
                cursor = head;
                if (cursor == null) {        //如果还为null，返回false
                    return false;
                }
            } else {
                prev = this.prev;          //否则将prev置为this.prev
            }

            // prev cursor
            // null,head
            // this.prev,cursor
            boolean success = false;
            do {
                if (cursor.transfer(this)) {     //cursor 已经transfer的位置      从cursor transfer数据到stack
                    success = true;
                    break;
                }
                WeakOrderQueue next = cursor.next;
                if (cursor.owner.get() == null) {      // cursor的owner已经消失了
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    if (cursor.hasFinalData()) {       //如果cursor还有数据可以读
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {            //说明cursor不是头节点，则将cursor从链中断开
                        prev.setNext(next);
                    }
                } else {           //如果cursor的thread还存在，
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        //如果是当前线程，则放入stack.elements中，否则放入DELAYED_RECYCLED中
        void push(DefaultHandle item) {
            Thread currentThread = Thread.currentThread();
            if (threadRef.get() == currentThread) {   //如果是当前线程
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                pushNow(item);
            } else {                                   //否则
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                pushLater(item, currentThread);
            }
        }

        //将当前DefaultHandle 加入到stack.elements中
        //这时，会将item的lastRecycledId和recycleId设置成一样，
        // 则如果他们一样，则可能已经表示，这个handle已经被push到stack过了
        //数据从stack中pop的时候，会判断数据的recycleId和lastRecycledId是否相等且置为0，push到stack中的时候，会判断recycleId和lastRecycledId是否是否都为0且置为OWN_THREAD_ID
        //形成了一个闭环，假如数据只是在stack中流转，应该可以一直pop和push
        //当数据流入到queue中后，recycleId和lastRecycledId应该就回发生变化？
        private void pushNow(DefaultHandle item) {
            if ((item.recycleId | item.lastRecycledId) != 0) {   //recycleId 和 lastRecycledId 有不为0的
                throw new IllegalStateException("recycled already");
            }

            //此时，recycleId和lastRecycledId全部为0
            //将recycleId和lastRecycledId设置为OWN_THREAD_ID
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            elements[size] = item;
            this.size = size + 1;
        }

        //将当前item加入到DELAYED_RECYCLED中
        private void pushLater(DefaultHandle item, Thread thread) {
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {
                if (delayedRecycled.size() >= maxDelayedQueues) {                         //超过了stack.maxDelayedQueues
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);                      //value为 DUMMY，表示drop it
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {      // 没有空闲空间
                    // drop object
                    return;
                }
                delayedRecycled.put(this, queue);                                          // 将当前生成的queue放入 DELAYED_RECYCLED
            } else if (queue == WeakOrderQueue.DUMMY) {                                    //表示需要drop
                // drop object
                return;
            }

            queue.add(item);               //将item放入DELAYED_RECYCLED
        }

        //是否应该drop掉
        boolean dropHandle(DefaultHandle handle) {
            if (!handle.hasBeenRecycled) {
                if ((++handleRecycleCount & ratioMask) != 0) {      // (++handleRecycleCount) % (ratioMask+1) != 0
                    // Drop the object.                                概率是 1 - 1/(1+ratioMask)
                    return true;                                    // drop的概率为 1 - 1/(1+ratioMask)  接近于1，大概率
                }
                handle.hasBeenRecycled = true;                //否则将hasBeenRecycled标志设置为true，且返回false，表示不该被drop
            }
            return false;
        }

        DefaultHandle newHandle() {
            return new DefaultHandle(this);
        }
    }
}
