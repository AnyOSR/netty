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
package io.netty.util;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Default {@link AttributeMap} implementation which use simple synchronization per bucket to keep the memory overhead
 * as low as possible.
 */
public class DefaultAttributeMap implements AttributeMap {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultAttributeMap, AtomicReferenceArray> updater = AtomicReferenceFieldUpdater.newUpdater(DefaultAttributeMap.class, AtomicReferenceArray.class, "attributes");
    private static final int BUCKET_SIZE = 4;
    private static final int MASK = BUCKET_SIZE  - 1;

    // Initialize lazily to reduce memory consumption; updated by AtomicReferenceFieldUpdater above.
    @SuppressWarnings("UnusedDeclaration")
    private volatile AtomicReferenceArray<DefaultAttribute<?>> attributes;

    @SuppressWarnings("unchecked")
    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            // Not using ConcurrentHashMap due to high memory consumption.
            attributes = new AtomicReferenceArray<DefaultAttribute<?>>(BUCKET_SIZE);

            //更新失败后，需要重新获取this.attributes的引用
            //假如更新成功，不需要再获取this.attributes的引用，之前attributes就已经指向new的那个AtomicReferenceArray了
            if (!updater.compareAndSet(this, null, attributes)) {
                attributes = this.attributes;
            }
        }

        //每一个AttributeKey都有一个唯一的id
        int i = index(key);
        DefaultAttribute<?> head = attributes.get(i);
        if (head == null) {
            // No head exists yet which means we may be able to add the attribute without synchronization and just
            // use compare and set. At worst we need to fallback to synchronization and waste two allocations.
            head = new DefaultAttribute();                                     //头节点
            DefaultAttribute<T> attr = new DefaultAttribute<T>(head, key);     //具体的数据节点
            head.next = attr;
            attr.prev = head;
            if (attributes.compareAndSet(i, null, head)) {              //cas更新头节点的值
                // we were able to add it so return the attr right away
                return attr;
            } else {
                head = attributes.get(i);
            }
        }

        synchronized (head) {
            DefaultAttribute<?> curr = head;
            for (;;) {
                DefaultAttribute<?> next = curr.next;
                if (next == null) {
                    DefaultAttribute<T> attr = new DefaultAttribute<T>(head, key);
                    curr.next = attr;
                    attr.prev = curr;
                    return attr;
                }

                if (next.key == key && !next.removed) {  //key相等，且没有被remove
                    return (Attribute<T>) next;
                }
                curr = next;
            }
        }
    }

    private static int index(AttributeKey<?> key) {
        return key.id() & MASK;
    }

    //AtomicReference拥有的那个value就是AtomicReference.get()返回的
    @SuppressWarnings("serial")
    private static final class DefaultAttribute<T> extends AtomicReference<T> implements Attribute<T> {

        private static final long serialVersionUID = -2661411462200283011L;

        // The head of the linked-list this attribute belongs to
        private final DefaultAttribute<?> head;

        // Double-linked list to prev and next node to allow fast removal
        private DefaultAttribute<?> prev;
        private DefaultAttribute<?> next;

        // Will be set to true one the attribute is removed via getAndRemove() or remove()
        private volatile boolean removed;
        private final AttributeKey<T> key;

        DefaultAttribute(DefaultAttribute<?> head, AttributeKey<T> key) {
            this.head = head;
            this.key = key;
        }

        // Special constructor for the head of the linked-list.
        //头节点
        DefaultAttribute() {
            head = this;
            key = null;
        }

        @Override
        public AttributeKey<T> key() {
            return key;
        }

        @Override
        public T setIfAbsent(T value) {
            while (!compareAndSet(null, value)) {  //更新失败，已经有别的线程更新了
                T old = get();                            //返回旧值
                if (old != null) {
                    return old;
                }
            }
            return null;                       //更新成功返回null，也是之前的旧值
        }

        @Override
        public T getAndRemove() {
            removed = true;
            T oldValue = getAndSet(null);
            remove0();
            return oldValue;
        }

        @Override
        public void remove() {
            removed = true;
            set(null);
            remove0();
        }

        //将当前DefaultAttribute从链中断开
        private void remove0() {
            synchronized (head) {
                if (prev == null) {
                    // Removed before.
                    return;
                }

                prev.next = next;

                if (next != null) {
                    next.prev = prev;
                }

                // Null out prev and next - this will guard against multiple remove0() calls which may corrupt
                // the linked list for the bucket.
                prev = null;
                next = null;
            }
        }
    }
}
