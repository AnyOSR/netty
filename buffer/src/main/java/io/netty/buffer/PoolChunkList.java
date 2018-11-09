/*
 * Copyright 2012 The Netty Project
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

package io.netty.buffer;

import io.netty.util.internal.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static java.lang.Math.*;

//PoolChunkList自身构成了一个链表
//然后PoolChunkList内部的chunk也构成了一个链表
final class PoolChunkList<T> implements PoolChunkListMetric {
    private static final Iterator<PoolChunkMetric> EMPTY_METRICS = Collections.<PoolChunkMetric>emptyList().iterator();

    private final PoolArena<T> arena;
    private final PoolChunkList<T> nextList;
    private PoolChunkList<T> prevList;        // This is only update once when create the linked like list of PoolChunkList in PoolArena constructor.

    private final int minUsage;
    private final int maxUsage;
    private final int maxCapacity;
    private PoolChunk<T> head;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunkList(PoolArena<T> arena, PoolChunkList<T> nextList, int minUsage, int maxUsage, int chunkSize) {
        assert minUsage <= maxUsage;
        this.arena = arena;
        this.nextList = nextList;
        this.minUsage = minUsage;
        this.maxUsage = maxUsage;
        maxCapacity = calculateMaxCapacity(minUsage, chunkSize);
    }

    /**
     * Calculates the maximum capacity of a buffer that will ever be possible to allocate out of the {@link PoolChunk}s
     * that belong to the {@link PoolChunkList} with the given {@code minUsage} and {@code maxUsage} settings.
     */
    //计算给定minUsage和chunkSize条件下的最大容量
    private static int calculateMaxCapacity(int minUsage, int chunkSize) {
        minUsage = minUsage0(minUsage);

        if (minUsage == 100) {
            // If the minUsage is 100 we can not allocate anything out of this list.
            return 0;
        }

        // Calculate the maximum amount of bytes that can be allocated from a PoolChunk in this PoolChunkList.
        //
        // As an example:
        // - If a PoolChunkList has minUsage == 25 we are allowed to allocate at most 75% of the chunkSize because
        //   this is the maximum amount available in any PoolChunk in this PoolChunkList.
        return  (int) (chunkSize * (100L - minUsage) / 100L);
    }

    //只有在prevList没有设置的情况下才去设置prevList
    void prevList(PoolChunkList<T> prevList) {
        assert this.prevList == null;
        this.prevList = prevList;
    }

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        if (head == null || normCapacity > maxCapacity) {
            // Either this PoolChunkList is empty or the requested capacity is larger then the capacity which can
            // be handled by the PoolChunks that are contained in this PoolChunkList.
            return false;
        }

        for (PoolChunk<T> cur = head;;) {
            long handle = cur.allocate(normCapacity);
            if (handle < 0) {           //小于0，分配失败
                cur = cur.next;         //去当前chunk链表的下一个chunk进行分配
                if (cur == null) {
                    return false;
                }
            } else {                                       //分配成功
                cur.initBuf(buf, handle, reqCapacity);
                if (cur.usage() >= maxUsage) {             //如果chunk的利用率大于当前chunkList的最大利用率，则将当前chunk向后移动
                    remove(cur);
                    nextList.add(cur);
                }
                return true;
            }
        }
    }

    //true表示将chunk位置合理
    boolean free(PoolChunk<T> chunk, long handle) {
        chunk.free(handle);
        if (chunk.usage() < minUsage) {   //小于了chunkList的minUsage
            remove(chunk);                //首先将chunk从当前chunkList清除
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);          //尝试将chunk添加到chunkList.prevList中
        }
        return true;
    }

    //返回false表示需要将chunk destroy
    private boolean move(PoolChunk<T> chunk) {
        assert chunk.usage() < maxUsage;

        if (chunk.usage() < minUsage) {   //如果当前可用率比minUsage还要小
            return move0(chunk);
        }

        // PoolChunk fits into this PoolChunkList, adding it here.
        // 如果chunk的利用率在当前chunkList的可用率区间范围之中，则添加到当前chunkList
        add0(chunk);
        return true;
    }

    /**
     * Moves the {@link PoolChunk} down the {@link PoolChunkList} linked-list so it will end up in the right
     * {@link PoolChunkList} that has the correct minUsage / maxUsage in respect to {@link PoolChunk#usage()}.
     */
    private boolean move0(PoolChunk<T> chunk) {
        if (prevList == null) {             //如果为null，说明当前chunkList已经是头节点，结合move方法，可知chunk的可用率比链表中第二个节点的minUsage还要小
            // There is no previous PoolChunkList so return false which result in having the PoolChunk destroyed and
            // all memory associated with the PoolChunk will be released.
            assert chunk.usage() == 0;      //但为什么这儿能确定chunk的usage为0？链表中第二个节点的最小可用率为1？
            return false;                   //return false 表示chunk没有被加入到chunkList中
        }
        return prevList.move(chunk);
    }

    // 那么在chunkList构成的链表中，
    // 在前面的chunkList的使用率较低，可分配的空闲空间较多
    // 在后面的chunkList的使用率较高，可分配的空闲空间较少
    void add(PoolChunk<T> chunk) {
        if (chunk.usage() >= maxUsage) { //如果chunk的使用率大于等于当前chunkList的使用率
            nextList.add(chunk);         //则尝试在 当前chunkList.nextList上添加该chunk
            return;
        }
        add0(chunk);                     //否则将chunk加入到当前chunkList
    }

    /**
     * Adds the {@link PoolChunk} to this {@link PoolChunkList}.
     */
    //将chunk加入到当前chunkList,并使其成为头节点
    void add0(PoolChunk<T> chunk) {
        chunk.parent = this;
        if (head == null) {
            head = chunk;
            chunk.prev = null;
            chunk.next = null;
        } else {
            chunk.prev = null;
            chunk.next = head;
            head.prev = chunk;
            head = chunk;
        }
    }

    //从当前chunkList中删除chunk
    private void remove(PoolChunk<T> cur) {
        if (cur == head) {
            head = cur.next;
            if (head != null) {
                head.prev = null;
            }
        } else {
            PoolChunk<T> next = cur.next;
            cur.prev.next = next;
            if (next != null) {
                next.prev = cur.prev;
            }
        }
    }

    @Override
    public int minUsage() {
        return minUsage0(minUsage);
    }

    //保证返回值小于等于100
    @Override
    public int maxUsage() {
        return min(maxUsage, 100);
    }

    //保证返回值大于等于1
    private static int minUsage0(int value) {
        return max(1, value);
    }

    @Override
    public Iterator<PoolChunkMetric> iterator() {
        synchronized (arena) {
            if (head == null) {
                return EMPTY_METRICS;
            }
            List<PoolChunkMetric> metrics = new ArrayList<PoolChunkMetric>();
            for (PoolChunk<T> cur = head;;) {
                metrics.add(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
            }
            return metrics.iterator();
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        synchronized (arena) {
            if (head == null) {
                return "none";
            }

            for (PoolChunk<T> cur = head;;) {
                buf.append(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
                buf.append(StringUtil.NEWLINE);
            }
        }
        return buf.toString();
    }

    void destroy(PoolArena<T> arena) {
        PoolChunk<T> chunk = head;
        while (chunk != null) {
            arena.destroyChunk(chunk);
            chunk = chunk.next;
        }
        head = null;
    }
}
