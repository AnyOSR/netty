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

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated           page：最小内存分配块单元
 * > chunk - a chunk is a collection of pages                                            chunk：page的集合
 * > in this code chunkSize = 2^{maxOrder} * pageSize                                    chunk：2 ^ maxOrder个page
 *
 * To begin we allocate a byte array of size = chunkSize                                        首先分配chunkSize长度的字节数组
 * Whenever a ByteBuf of given size needs to be created we search for the first position        当需要分配给定长度的ByteBuf时，在字节数组里面寻找第一个能满足 拥有足够空闲长度的能容纳要求size的位置，
 * in the byte array that has enough empty space to accommodate the requested size and          并返回一个 编码了这个偏移量信息的handle，然后，这个内存片段被标记为reserved，因此，这个内存片段只会被一个ByteBuf使用
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method            为了简单，所有的size都被归一化了，根据PoolArena.normalizeCapacity方法
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity   这个保证了，当我们要求分配一个比pageSize大的内存片段时，归一化长度为大于当前要求内存片段大小的第一个2的整数倍 (为了尽可能少的浪费空间，需要合理决定pageSize的值)
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a  为了找出第一个满足要求大小的偏移位置，构造了一颗完全平衡二叉树，存在了数组里
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap               （由于是完全平衡二叉树，所以可以根据其在数组里面的index推断出其在树中的位置）两者完全等效
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)                                                                 深度d的节点数为 2^d 则每个节点代表的内存大小为 chunkSize/2^d
 * depth=1        2 nodes (chunkSize/2)                                                              一共有maxOrder层(从0开始)，最后一层每个节点代表的内存大小为pageSize
 * ..                                                                                                则有，pageSize * 2^maxOrder = chunkSize
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)                                                           每个不同深度的size不一样
 * ..                                                                                                 对于深度d,每个数组元素的值，表示了是否有连续chunkSize/2^d 大小的空闲内存存在
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)                                那么寻找 指定大小空闲内存的时候，首先归一化，然后根据归一化数值计算深度d，遍历当前层就可以了
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated              memoryMap[id] = x 中的 x表示了可以完整分配 内存大小深度为 x
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free       深度x的内存大小为 chunkSize/ 2^x
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node    初始化的时候，memoryMap存储了每个节点的深度 0 1 1 2 2 2 2 3 3 3 3 3 3 3 3.....
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated                                                             那么，如果memoryMap[id]等于深度depth_of_id，则还没有被分配过
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but          如果，memoryMap[id]大于深度depth_of_id，说明已经被分配过
 *                                    some of its children can still be allocated based on their availability              如果，memoryMap[id]等于maxOrder + 1，这棵树代表的内存已经完全分配完了
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it          memoryMap[id]等于maxOrder时，表示最底层还有内存可以分配，大小为pageSize
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]             allocateNode(d) 找到第一个可以分配的节点
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information (i.e, 2 byte vals) as a short value in memoryMap
 *
 * memoryMap[id]= (depth_of_id, x)                                   //节点深度 可以分配完整内存的深度
 * where as per convention defined above
 * the second value (i.e, x) indicates that the first node which is free to be allocated is at depth x (from root)
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;              // 31

    final T memory;
    final boolean unpooled;
    final int offset;

    private final byte[] memoryMap;
    private final byte[] depthMap;
    private final PoolSubpage<T>[] subpages;
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    private final int subpageOverflowMask;
    private final int pageSize;               // 为2的n次幂 pageSize = 2^pageShifts
    private final int pageShifts;             // 为n pageSize二进制1后面0的个数
    private final int maxOrder;
    private final int chunkSize;              // 2^maxOrder * pageSize = 2^maxOrder * 2^pageShifts = 2^(maxOrder+pageShifts) = chunkSize
    private final int log2ChunkSize;          // maxOrder + pageShifts
    private final int maxSubpageAllocs;
    private final byte unusable;           /** Used to mark memory as unusable */
    private int freeBytes;

    final PoolArena<T> arena;
    PoolChunkList<T> parent;
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;
        unusable = (byte) (maxOrder + 1);                       //说明当前tree已经完全分配
        log2ChunkSize = log2(chunkSize);                        //
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;           // 2^maxOrder  最大subPage数

        // Generate the memory map.
        memoryMap = new byte[maxSubpageAllocs << 1];
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time             //d 深度
            int depth = 1 << d;                                                                      //每层节点数
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;                                                //可分配节点的深度
                depthMap[memoryMapIndex] = (byte) d;                                                 //空间换时间   记录memoryMap中每个元素的深度
                memoryMapIndex ++;
            }
        }

        subpages = newSubpageArray(maxSubpageAllocs);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    //分配空间
    long allocate(int normCapacity) {
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            return allocateRun(normCapacity);
        } else {                //否则，大小小于pageSize
            return allocateSubpage(normCapacity);
        }
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            int parentId = id >>> 1;        //获取父节点id
            byte val1 = value(id);
            byte val2 = value(id ^ 1);         //找到父节点另一个子节点的深度
            byte val = val1 < val2 ? val1 : val2;  //取最小值
            setValue(parentId, val);               //更新父节点深度
            id = parentId;                         //一直更新
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id  为当前page在所在chunk.memoryMap中的索引值
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;     //计算子节点的深度
        while (id > 1) {
            int parentId = id >>> 1;      //计算父节点在chunk.memoryMap中的索引值
            byte val1 = value(id);         //当前page的可分配深度
            byte val2 = value(id ^ 1); //当前page的父节点的另一子节点的可分配深度
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     */
    //如果存在d层可分配，则在第d层上一定存在 memoryMap[id] = d 的元素
    //memoryMap[1]的数值决定了可以分配最大的连续内存块，也即可以分配的最低深度
    private int allocateNode(int d) {
        int id = 1;                        //从最顶点开始遍历

        // ....0000 1 000
        // ....1111 0 111
        // ....1111 1 000
        // 最后d位为0，其余都为1
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        byte val = value(id);
        if (val > d) { // unusable          不可分配
            return -1;
        }
        // id & initial == 0说明 id <= 2^(d)-1    由于d-1层的id范围为 2^(d-1) ~ 2^(d)-1   则说明id处于d层之上
        // d层的id范围为 2^d ~ 2^d+1-1 ,则 initial & id = 2^d
        // 即如果id所在的层在d层之上，则说明 id & initial ==0
        // 如果id在第d层，则 id & initial == 1 << d
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1;                    //向左深入                (一个父节点的左子节点的id为父节点id乘以2，父节点的右子节点的id为父节点的id乘以2然后加上1，左节点id永远为偶数，即最后一位为0，右子节点最后一位为1)
            val = value(id);             //左节点可分配深度
            if (val > d) {               //如果左节点不可分配
                id ^= 1;                 //则尝试右子节点
                val = value(id);
            }
        }   //一直到当前节点的可分配深度 =d 且 当前id 处于第d层  一定可以找到，如果存在的话
        byte value = value(id);
        //所以，在这里一定有 value == d && (id & initial) == 1 << d
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d", value, id & initial, d);
        setValue(id, unusable); // mark as unusable  标记为不可用
        updateParentsAlloc(id);  //由于当前已经被分配，需要更新父辈节点的可分配深度
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    //分配大于等于pageSize的内存
    //其高32位肯定为0
    private long allocateRun(int normCapacity) {

        // 2^(maxOrder+pageShifts) = chunkSize
        // chunkSize / normCapacity = 2^d                     --->   2^(maxOrder+pageShifts) / normCapacity = 2^d
        // 2^(maxOrder+pageShifts) = 2^d * normCapacity       --->   maxOrder+pageShifts = d + log2(normCapacity)
        // d = maxOrder - (log2(normCapacity) - pageShifts)
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        int id = allocateNode(d);
        if (id < 0) {
            return id;
        }
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create/ initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created/ initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        synchronized (head) {
            int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
            //小于pageSize，则直接在最底层分配
            int id = allocateNode(d);
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;  //当前chunk的所有subPage
            final int pageSize = this.pageSize;               //每个page的大小

            freeBytes -= pageSize;

            int subpageIdx = subpageIdx(id);                  //当前id在底层的偏移量 0-based
            PoolSubpage<T> subpage = subpages[subpageIdx];    //取到偏移量为subpageIdx的PoolSubpage
            if (subpage == null) {                            //如果为null，则分配 ，否则初始化
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                subpage.init(head, normCapacity);
            }
            return subpage.allocate();            //然后分配
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    //free page中的某一个elem
    void free(long handle) {
        int memoryMapIdx = memoryMapIdx(handle);      //计算当前elem所在page在chunk.memoryMap中的索引值
        int bitmapIdx = bitmapIdx(handle);            //计算当前elem在所在page中的位置值

        //不为0，当前handle肯定是page中某一个elem的handle
        if (bitmapIdx != 0) { // free a subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];       //当前elem所属page
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);  //每个不同elemSize大小的page组成了一条链？然后会有多条莲？
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {      //将最高两位位置0
                    return;
                }
            }
        }
        //如果subpage被remove
        freeBytes += runLength(memoryMapIdx);
        setValue(memoryMapIdx, depth(memoryMapIdx)); //重新置为可用
        updateParentsFree(memoryMapIdx);
    }

    void initBuf(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            buf.init(this, handle, runOffset(memoryMapIdx) + offset, reqCapacity, runLength(memoryMapIdx),
                     arena.parent.threadCache());
        } else {
            initBufWithSubpage(buf, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        initBufWithSubpage(buf, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle);

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        buf.init(
            this, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    //可用深度
    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    //某个数组元素在树中的深度
    private byte depth(int id) {
        return depthMap[id];
    }

    //floor(log2(x))
    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    //计算index为id所在树深度的 逻辑块大小
    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        // chunkSize / 2 ^ depth(id)
        // 2^log2ChunkSize / 2^depth(id)  = 2 ^ (log2ChunkSize - depth(id)) = 1 << log2ChunkSize - depth(id)
        return 1 << log2ChunkSize - depth(id);
    }

    //id应该是memoryMap中的下标索引值
    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        // 1 << depth(id)  代表    d层的逻辑块数  也是d层第一个逻辑块在memoryMap中的索引值
        // 1 << depth(id) ^ id     将id的最高位1去掉了，则shift为当前元素在当前层的偏移量 第一个元素的偏移量为0
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);    // 则返回的是，id所在层的前面的块的逻辑大小和
    }

    //memoryMapIdx为最底层的idx
    //将idx的最高位 置0
    //则返回的是当前idx在底层的偏移量，0-based
    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    //低32位
    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    //高32位
    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
