/*
 *  Copyright 2011-2013 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.tupl;

import java.io.IOException;

import java.util.Arrays;
import java.util.BitSet;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.cojen.tupl.Utils.*;

/**
 * Used by PageManager to implement free lists.
 *
 * @author Brian S O'Neill
 */
final class PageQueue implements IntegerRef {
    /*

    Header structure is encoded as follows, in 44 bytes:

    +---------------------------------------------------+
    | long: remove page count                           |
    | long: remove node count                           |
    | long: remove head id                              |
    | int:  remove head offset                          |
    | long: remove head first page id (seed for deltas) |
    | long: append head/tail id                         |
    +---------------------------------------------------+

    The page queue nodes are encoded as follows:

    +---------------------------------------+
    | long: next node id                    |
    | long: first page id (seed for deltas) |
    +---------------------------------------+
    | remaining page ids (delta encoded)    |
    -                                       -
    |                                       |
    +---------------------------------------+

    The two longs in the queue node header are big endian encoded. The first
    byte in the node is almost always 0, aiding in quick detection of
    corruption if a page queue node is referenced by another data structure.

    */

    // Indexes of header entries.
    private static final int I_REMOVE_PAGE_COUNT         = 0;
    private static final int I_REMOVE_NODE_COUNT         = I_REMOVE_PAGE_COUNT + 8;
    private static final int I_REMOVE_HEAD_ID            = I_REMOVE_NODE_COUNT + 8;
    private static final int I_REMOVE_HEAD_OFFSET        = I_REMOVE_HEAD_ID + 8;
    private static final int I_REMOVE_HEAD_FIRST_PAGE_ID = I_REMOVE_HEAD_OFFSET + 4;
    private static final int I_APPEND_HEAD_ID            = I_REMOVE_HEAD_FIRST_PAGE_ID + 8;
            static final int HEADER_SIZE                 = I_APPEND_HEAD_ID + 8;

    // Indexes of node entries.
    private static final int I_NEXT_NODE_ID  = 0;
    private static final int I_FIRST_PAGE_ID = I_NEXT_NODE_ID + 8;
    private static final int I_NODE_START    = I_FIRST_PAGE_ID + 8;

    private final PageManager mManager;
    private final int mAllocMode;
    private final boolean mAggressive;

    // These fields are guarded by remove lock provided by caller.
    private long mRemovePageCount;
    private long mRemoveNodeCount;
    private final byte[] mRemoveHead;
    private long mRemoveHeadId;
    private int mRemoveHeadOffset;
    private long mRemoveHeadFirstPageId;
    private long mRemoveStoppedId;

    // Barrier between the remove and append lists. Remove stops when it
    // encounters the append head. Modification is permitted with the append
    // lock held, but no lock is required to access this id. Hence, volatile.
    private volatile long mAppendHeadId;

    // These fields are guarded by mAppendLock.
    private final ReentrantLock mAppendLock;
    private final IdHeap mAppendHeap;
    private final byte[] mAppendTail;
    private volatile long mAppendTailId;
    private long mAppendPageCount;
    private long mAppendNodeCount;
    private boolean mDrainInProgress;

    /**
     * Returns true if a valid PageQueue instance is encoded in the header.
     */
    static boolean exists(byte[] header, int offset) {
        return readLongLE(header, offset + I_REMOVE_HEAD_ID) != 0;
    }

    /**
     * @param manager used for allocating and deleting pages for the queue itself
     * @param allocMode PageManager.ALLOC_NORMAL or ALLOC_RESERVE
     * @param aggressive pass true if most appended pages are safe to remove
     */
    PageQueue(PageManager manager, int allocMode, boolean aggressive) {
        mManager = manager;
        mAllocMode = allocMode;
        mAggressive = aggressive;
        PageArray array = manager.pageArray();

        mRemoveHead = new byte[array.pageSize()];

        // This lock must be reentrant. The appendPage method can call into
        // drainAppendHeap, which calls allocPage, which can re-acquire the
        // append lock. The commit method acquires the remove lock too, and
        // then it calls drainAppendHeap, acquiring the locks again. Note
        // that locks are unfair. Pages are allocated and deleted by tree
        // operations, which unfairly acquire latches. Waiting for a fair lock
        // afterwards leads to priority inversion.
        mAppendLock = new ReentrantLock(false);

        mAppendHeap = new IdHeap(array.pageSize() - I_NODE_START);
        mAppendTail = new byte[array.pageSize()];
    }

    /**
     * Initialize a fresh (non-restored) queue.
     */
    void init(long headNodeId) throws IOException {
        mAppendLock.lock();
        try {
            mRemoveStoppedId = mAppendHeadId = mAppendTailId = headNodeId;
        } finally {
            mAppendLock.unlock();
        }
    }

    /**
     * Initialize a restored queue. Caller must hold append and remove locks.
     */
    void init(byte[] header, int offset) throws IOException {
        mRemovePageCount = readLongLE(header, offset + I_REMOVE_PAGE_COUNT);
        mRemoveNodeCount = readLongLE(header, offset + I_REMOVE_NODE_COUNT);

        mRemoveHeadId = readLongLE(header, offset + I_REMOVE_HEAD_ID);
        mRemoveHeadOffset = readIntLE(header, offset + I_REMOVE_HEAD_OFFSET);
        mRemoveHeadFirstPageId = readLongLE(header, offset + I_REMOVE_HEAD_FIRST_PAGE_ID);

        mAppendHeadId = mAppendTailId = readLongLE(header, offset + I_APPEND_HEAD_ID);

        if (mRemoveHeadId == 0) {
            mRemoveStoppedId = mAppendHeadId;
        } else {
            mManager.pageArray().readPage(mRemoveHeadId, mRemoveHead);
            if (mRemoveHeadFirstPageId == 0) {
                mRemoveHeadFirstPageId = readLongBE(mRemoveHead, I_FIRST_PAGE_ID);
            }
        }
    }

    /**
     * Delete or recycle all pages, effectively deleting this PageQueue. Only works for page
     * manager reserve list.
     *
     * @param upperBound inclusive; pages greater than the upper bound are discarded
     */
    void reclaim(Lock removeLock, long upperBound, boolean recycle) throws IOException {
        if (mAllocMode != PageManager.ALLOC_RESERVE || !mAggressive) {
            throw new IllegalStateException();
        }

        long pageId;
        while ((pageId = tryUnappend()) != 0) {
            if (pageId <= upperBound) {
                mManager.deletePage(pageId, recycle);
            }
        }

        while (true) {
            removeLock.lock();
            pageId = tryRemove(removeLock);
            if (pageId == 0) {
                removeLock.unlock();
                break;
            }
            if (pageId <= upperBound) {
                mManager.deletePage(pageId, recycle);
            }
        }

        pageId = mRemoveStoppedId;
        if (pageId != 0 && pageId <= upperBound) {
            // Always delete the empty tail node to finish it off. Queue node pages cannot be
            // recycled until after a commit.
            mManager.deletePage(pageId);
        }
    }

    /**
     * Remove a page to satisfy an allocation request. Caller must acquire
     * remove lock, which might be released by this method.
     *
     * @param lock lock to be released by this method, unless return value is 0
     * @return 0 if queue is empty or if remaining pages are off limits
     */
    long tryRemove(Lock lock) throws IOException {
        if (mRemoveHeadId == 0) {
            if (!mAggressive || mRemoveStoppedId == mAppendTailId) {
                return 0;
            }
            // Can continue removing aggressively now that a new append tail exists.
            loadRemoveNode(mRemoveStoppedId);
            mRemoveStoppedId = 0;
        }

        long pageId;
        long oldHeadId;

        try {
            pageId = mRemoveHeadFirstPageId;

            if (mManager.isPageOutOfBounds(pageId) && mAllocMode != PageManager.ALLOC_RESERVE) {
                throw new CorruptDatabaseException("Invalid page id in free list: " + pageId);
            }

            mRemovePageCount--;

            final byte[] head = mRemoveHead;
            if (mRemoveHeadOffset < head.length) {
                // Pass this as an IntegerRef to mRemoveHeadOffset.
                long delta = readUnsignedVarLong(head, this);
                if (delta > 0) {
                    mRemoveHeadFirstPageId = pageId + delta;
                    return pageId;
                }
                // Zero delta is a terminator.
            }

            oldHeadId = mRemoveHeadId;

            // Move to the next node in the list.
            long nextId = readLongBE(head, I_NEXT_NODE_ID);

            if (nextId == (mAggressive ? mAppendTailId : mAppendHeadId)) {
                // Cannot remove from the append list. Those pages are off limits.
                mRemoveHeadId = 0;
                mRemoveHeadOffset = 0;
                mRemoveHeadFirstPageId = 0;
                mRemoveStoppedId = nextId;
            } else {
                loadRemoveNode(nextId);
            }

            mRemoveNodeCount--;
        } finally {
            lock.unlock();
        }

        // Delete old head outside lock section to prevent deadlock with
        // commit. Lock acquisition order wouldn't match. Note that node is
        // deleted instead of used for next allocation. This ensures that no
        // important data is overwritten until after commit.
        mManager.deletePage(oldHeadId);

        return pageId;
    }

    // Caller must hold remove lock.
    private void loadRemoveNode(long id) throws IOException {
        if (mManager.isPageOutOfBounds(id)) {
            throw new CorruptDatabaseException("Invalid node id in free list: " + id);
        }
        byte[] head = mRemoveHead;
        mManager.pageArray().readPage(id, head);
        mRemoveHeadId = id;
        mRemoveHeadOffset = I_NODE_START;
        mRemoveHeadFirstPageId = readLongBE(head, I_FIRST_PAGE_ID);
    }

    /**
     * Append a page which has been deleted.
     *
     * @throws IllegalArgumentException if id is less than or equal to one
     */
    void append(long id) throws IOException {
        if (id <= 1) {
            throw new IllegalArgumentException("Page id: " + id);
        }

        final IdHeap appendHeap = mAppendHeap;

        mAppendLock.lock();
        try {
            appendHeap.add(id);
            mAppendPageCount++;
            if (!mDrainInProgress && appendHeap.shouldDrain()) {
                drainAppendHeap(appendHeap);
            }
            // If a drain is in progress, then append is called by allocPage
            // which is called by drainAppendHeap itself. The IdHeap has
            // padding for one more id, which gets drained when control returns
            // to the caller.
        } finally {
            mAppendLock.unlock();
        }
    }

    /**
     * Removes a page which was recently appended. To avoid deadlock, don't invoke with remove
     * lock held.
     *
     * @return 0 if none available
     */
    long tryUnappend() {
        mAppendLock.lock();
        try {
            final IdHeap appendHeap = mAppendHeap;
            if (mDrainInProgress && appendHeap.size() <= 1) {
                // Must leave at least one page, because drain will remove it.
                return 0;
            }
            long id = appendHeap.tryRemove();
            if (id != 0) {
                mAppendPageCount--;
            }
            return id;
        } finally {
            mAppendLock.unlock();
        }
    }

    // Caller must hold mAppendLock.
    private void drainAppendHeap(IdHeap appendHeap) throws IOException {
        if (mDrainInProgress) {
            throw new AssertionError();
        }

        mDrainInProgress = true;
        try {
            long newTailId = mManager.allocPage(mAllocMode);
            long firstPageId = appendHeap.remove();

            byte[] tailBuf = mAppendTail;
            writeLongBE(tailBuf, I_NEXT_NODE_ID, newTailId);
            writeLongBE(tailBuf, I_FIRST_PAGE_ID, firstPageId);

            int end = appendHeap.drain(firstPageId,
                                       tailBuf,
                                       I_NODE_START,
                                       tailBuf.length - I_NODE_START);

            // Clean out any cruft from previous usage and ensure delta terminator.
            Arrays.fill(tailBuf, end, tailBuf.length, (byte) 0);

            mManager.pageArray().writePage(mAppendTailId, tailBuf);

            mAppendNodeCount++;
            mAppendTailId = newTailId;
        } finally {
            mDrainInProgress = false;
        }
    }

    Lock appendLock() {
        return mAppendLock;
    }

    /**
     * Caller must hold append and remove locks.
     */
    void preCommit() throws IOException {
        final IdHeap appendHeap = mAppendHeap;
        while (appendHeap.size() > 0) {
            // A new mAppendTailId is assigned as a side-effect.
            drainAppendHeap(appendHeap);
        }
    }

    /**
     * Caller must hold append and remove locks and have called preCommit.
     */
    void commitStart(byte[] header, int offset) {
        writeLongLE(header, offset + I_REMOVE_PAGE_COUNT, mRemovePageCount + mAppendPageCount);
        writeLongLE(header, offset + I_REMOVE_NODE_COUNT, mRemoveNodeCount + mAppendNodeCount);

        if (mRemoveHeadId == 0 && mAppendPageCount > 0) {
            writeLongLE(header, offset + I_REMOVE_HEAD_ID, mAppendHeadId);
            writeIntLE (header, offset + I_REMOVE_HEAD_OFFSET, I_NODE_START);
            // First page is defined in node itself, and init method reads it.
            writeLongLE(header, offset + I_REMOVE_HEAD_FIRST_PAGE_ID, 0);
        } else {
            writeLongLE(header, offset + I_REMOVE_HEAD_ID, mRemoveHeadId);
            writeIntLE (header, offset + I_REMOVE_HEAD_OFFSET, mRemoveHeadOffset);
            writeLongLE(header, offset + I_REMOVE_HEAD_FIRST_PAGE_ID, mRemoveHeadFirstPageId);
        }

        // Post-commit, all appended pages are eligible to be removed.
        writeLongLE(header, offset + I_APPEND_HEAD_ID, mAppendTailId);

        // Increase counts now, but not all pages are not available until after
        // commitEnd is called.
        mRemovePageCount += mAppendPageCount;
        mRemoveNodeCount += mAppendNodeCount;

        mAppendPageCount = 0;
        mAppendNodeCount = 0;
    }

    /**
     * Caller must hold remove lock.
     *
     * @param header header with contents filled in by commitStart
     */
    void commitEnd(byte[] header, int offset) throws IOException {
        long newAppendHeadId = readLongLE(header, offset + I_APPEND_HEAD_ID);

        if (mRemoveHeadId == 0 && mRemoveStoppedId != newAppendHeadId) {
            // Allow removing of previously appended pages.
            loadRemoveNode(mRemoveStoppedId);
            mRemoveStoppedId = 0;
        }

        mAppendHeadId = newAppendHeadId;
    }

    /**
     * Caller must hold append and remove locks.
     */
    void addTo(PageDb.Stats stats) {
        stats.freePages +=
            mRemovePageCount + mAppendPageCount +
            mRemoveNodeCount + mAppendNodeCount;
    }

    /**
     * Scans all pages in the queue and checks if it matches the given range, assuming no
     * duplicates exist.
     */
    boolean verifyPageRange(long startId, long endId) throws IOException {
        // Be extra paranoid and use a hash for duplicate detection.
        long expectedHash = 0;
        for (long i=startId; i<endId; i++) {
            // Addition is commutative. Pages will not be observed in order.
            expectedHash += scramble(i);
        }

        long hash = 0;
        long count = 0;

        long nodeId = mRemoveHeadId;

        if (nodeId != 0) {
            byte[] node = mRemoveHead.clone();
            long pageId = mRemoveHeadFirstPageId;
            IntegerRef.Value nodeOffsetRef = new IntegerRef.Value();
            nodeOffsetRef.value = mRemoveHeadOffset;

            while (true) {
                if (pageId < startId || pageId >= endId) {
                    // Out of bounds.
                    return false;
                }

                hash += scramble(pageId);
                count++;

                if (nodeOffsetRef.value < node.length) {
                    long delta = readUnsignedVarLong(node, nodeOffsetRef);
                    if (delta > 0) {
                        pageId += delta;
                        continue;
                    }
                }

                if (nodeId >= startId && nodeId < endId) {
                    // Count in-range queue nodes too.
                    hash += scramble(nodeId);
                    count++;
                }

                // Move to the next queue node.

                nodeId = readLongBE(node, I_NEXT_NODE_ID);
                if (nodeId == mAppendTailId) {
                    break;
                }

                mManager.pageArray().readPage(nodeId, node);
                pageId = readLongBE(node, I_FIRST_PAGE_ID);
                nodeOffsetRef.value = I_NODE_START;
            }
        }

        return hash == expectedHash && count == (endId - startId);
    }

    /**
     * Clears bits representing all removable pages in the queue. Caller must
     * hold remove lock.
     */
    int traceRemovablePages(BitSet pages) throws IOException {
        int count = 0;

        // Even though not removable, also clear append head. Otherwise, it
        // gives the impression that one page is missing, even after startup.
        long nodeId = mAppendHeadId;
        if (nodeId < mManager.pageArray().getPageCount()) {
            count++;
            clearPageBit(pages, nodeId);
        }

        nodeId = mRemoveHeadId;

        if (nodeId == 0) {
            return count;
        }

        byte[] node = mRemoveHead.clone();
        long pageId = mRemoveHeadFirstPageId;
        IntegerRef.Value nodeOffsetRef = new IntegerRef.Value();
        nodeOffsetRef.value = mRemoveHeadOffset;

        while (true) {
            /*
            if (mManager.isPageOutOfBounds(pageId)) {
                throw new CorruptDatabaseException("Invalid page id in free list: " + pageId);
            }
            */

            count++;
            clearPageBit(pages, pageId);

            if (nodeOffsetRef.value < node.length) {
                long delta = readUnsignedVarLong(node, nodeOffsetRef);
                if (delta > 0) {
                    pageId += delta;
                    continue;
                }
            }

            // Indicate free list node itself as free and move to the next node
            // in the free list.

            count++;
            clearPageBit(pages, nodeId);

            nodeId = readLongBE(node, I_NEXT_NODE_ID);
            if (nodeId == mAppendHeadId || nodeId == mAppendTailId) {
                break;
            }

            mManager.pageArray().readPage(nodeId, node);
            pageId = readLongBE(node, I_FIRST_PAGE_ID);
            nodeOffsetRef.value = I_NODE_START;
        }

        return count;
    }

    private static void clearPageBit(BitSet pages, long pageId) throws CorruptDatabaseException {
        int index = (int) pageId;
        if (pages.get(index)) {
            pages.clear(index);
        } else if (index < pages.size()) {
            throw new CorruptDatabaseException("Doubly freed page: " + pageId);
        }
    }

    // Required by IntegerRef.
    @Override
    public int get() {
        return mRemoveHeadOffset;
    }

    // Required by IntegerRef.
    @Override
    public void set(int offset) {
        mRemoveHeadOffset = offset;
    }
}
