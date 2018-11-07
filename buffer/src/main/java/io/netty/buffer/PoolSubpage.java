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

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    private final int memoryMapIdx;
    private final int runOffset;
    private final int pageSize;
    private final long[] bitmap;

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    int elemSize;
    private int maxNumElems;
    private int bitmapLength;
    private int nextAvail;
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    //head
    //chunk          当前page所在chunk
    //memoryMapIdx   当前page在所在chunk.memoryMap中的index
    //runOffset      当前page之前的所有page的逻辑块大小
    //pageSize       当前page的大小
    //elemSize       分配大小
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            maxNumElems = numAvail = pageSize / elemSize;   //最大可用数 剩余可用数
            nextAvail = 0;                                  //第一个可用index

            // maxNumElems / 64
            // 一个elem是否可用 用一个long的bit表示
            // 所以一共需要bitmapLength个long表示
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) {     //如果maxNumElems不为64的整数倍，则再增加一个long
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        final int bitmapIdx = getNextAvail();   //计算可分配elem在当前page中的index
        int q = bitmapIdx >>> 6;                //计算当前elem在bitMap中的下标索引值
        int r = bitmapIdx & 63;                 //计算当前elem在bitMap[q]中的bit位置
        assert (bitmap[q] >>> r & 1) == 0;      //由于当前elem可分配，则必有bitMap[q]的低r bit位为0 则 &1 为0
        bitmap[q] |= 1L << r;                   //然后将bitMap[q]的低r bit位 置1，表示已分配

        if (-- numAvail == 0) {                 //然后将可分配数减一
            removeFromPool();
        }

        //编码当前elem位置信息，并返回
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    //bitmapIdx当前elem在当前page中的位置值
    //如果remove则返回false
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;       // 必然有bitmap[q]的低 r位为1
        bitmap[q] ^= 1L << r;                    // 将bitmap[q]的低 r bit 置0  表示可用

        setNextAvail(bitmapIdx);                 // 将其置为可用

        if (numAvail ++ == 0) {                  //先判断== 然后自加  如果之前的numAvail为0，现在free了一个，则当前page有可用了
            addToPool(head);                     // 则加入到pool中
            return true;
        }

        if (numAvail != maxNumElems) {           //如果当前可分配elem数不等于最大elem分配数 说明有elem已经被分配了
            return true;
        } else {   //否则，当前page的所有elem都是可分配的
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();     //从pool中移除
            return false;
        }
    }

    //将当前subPage插入到head后面
    //只是将当前page链入到head后
    //其和chunk的所属关系并没有改变
    private void  addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    //将当前subPage从链中断开
    //只是从head起头的链中断开，并没有在其所属的chunk remove掉
    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    //
    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) { //如果nextAvail大于等于0，直接返回，并将nextAvail置为-1
            this.nextAvail = -1;
            return nextAvail;
        }
        //否则，需要在bitMap中寻找可分配位置
        return findNextAvail();
    }

    //返回当前page中可分配的elem在当前page中的位置
    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            //如果当前取反不为0，则说明还有某一个bit为0
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    //i 为 bitmap的某一个下表索引
    //bits为 bitmap[i]
    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;         //基数  64的整数倍 64进制

        for (int j = 0; j < 64; j ++) {
            if ((bits & 1) == 0) {             //如果当前bits的最后一位为0，则未分配
                int val = baseVal | j;         //计算当前elem在page中的index baseVal+j
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;          //否则右移
        }
        return -1;
    }

    //bitmapIdx  分配的elem在当前page中的位置值
    //memoryMapIdx 当前page所在chunk.memoryMap的index值
    //唯一的确定了当前elem的位置(所在chunk,page中的位置)
    // 0x4000000000000000L？
    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
