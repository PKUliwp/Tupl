/*
 *  Copyright (C) 2017 Cojen.org
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cojen.tupl.repl;

import java.io.IOException;

import java.net.SocketAddress;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.ThreadLocalRandom;

import org.cojen.tupl.util.Latch;

import static org.cojen.tupl.io.Utils.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class Controller extends Latch implements Channel {
    private static final int ROLE_FOLLOWER = 0, ROLE_CANDIDATE = 1, ROLE_LEADER = 2;
    private static final int ELECTION_DELAY_LOW_MILLIS = 200, ELECTION_DELAY_HIGH_MILLIS = 300;
    private static final int QUERY_TERMS_RATE_MILLIS = 1;
    private static final int MISSING_DELAY_LOW_MILLIS = 400, MISSING_DELAY_HIGH_MILLIS = 600;
    private static final int MISSING_DATA_REQUEST_SIZE = 100_000;

    private final Scheduler mScheduler;
    private final ChannelManager mChanMan;
    private final StateLog mStateLog;

    private int mLocalRole;

    private Peer[] mPeers;
    private Channel[] mPeerChannels;

    private long mCurrentTerm;
    private long mVotedFor;
    private int mGrantsRemaining;
    private int mElectionValidated;

    private LogWriter mLeaderWriter;

    // Index used to check for missing data.
    private long mMissingContigIndex = Long.MAX_VALUE; // unknown initially
    private boolean mSkipMissingDataTask;

    // Limit the rate at which missing terms are queried.
    private volatile long mNextQueryTermTime = Long.MIN_VALUE;

    // Unused after starting.
    private Map<Long, Peer> mPeerMap;

    Controller(StateLog log, long auth) {
        mStateLog = log;
        mScheduler = new Scheduler();
        mChanMan = new ChannelManager(mScheduler, auth);
        mPeerMap = new HashMap<>();
    }

    public void addPeer(long memberId, SocketAddress addr) {
        acquireExclusive();
        try {
            Peer peer = new Peer(memberId, addr);
            if (mChanMan.getLocalMemberId() != 0) {
                throw new IllegalStateException();
            }

            Long key = memberId;
            if (mPeerMap.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate peer: " + memberId);
            }

            mPeerMap.put(key, peer);
        } finally {
            releaseExclusive();
        }
    }

    public void start(long localMemberId, SocketAddress addr) throws IOException {
        acquireExclusive();
        try {
            if (localMemberId == 0) {
                throw new IllegalArgumentException();
            }
            if (mChanMan.getLocalMemberId() != 0) {
                throw new IllegalStateException();
            }

            if (mPeerMap.containsKey(localMemberId)) {
                throw new IllegalStateException
                    ("Local member is also a peer: " + localMemberId);
            }

            Peer[] peers = new Peer[mPeerMap.size()];
            Channel[] peerChannels = new Channel[peers.length];

            mChanMan.setLocalMemberId(localMemberId);
            mChanMan.start(addr, this);

            int i = 0;
            for (Peer peer : mPeerMap.values()) {
                peers[i] = peer;
                peerChannels[i] = mChanMan.connect(peer, this);
                i++;
            }

            mPeers = peers;
            mPeerChannels = peerChannels;

            mPeerMap = null;
        } finally {
            releaseExclusive();
        }

        scheduleElectionTask();
        scheduleMissingDataTask();
    }

    // FIXME: testing
    public boolean writeData(byte[] data) throws IOException {
        Channel[] peerChannels;
        LogWriter writer;

        acquireShared();
        try {
            peerChannels = mPeerChannels;
            writer = mLeaderWriter;
            if (peerChannels.length == 0 && writer != null) {
                // Only a group of one, so commit changes immediately.
                long highestIndex = writer.index() + data.length;
                writer.write(data, 0, data.length, highestIndex);
                mStateLog.commit(highestIndex);
                return true;
            }
        } finally {
            releaseShared();
        }

        if (writer == null) {
            // Not leader anymore.
            return false;
        }

        long prevTerm = writer.prevTerm();
        long term = writer.term();
        long index = writer.index();

        writer.write(data, 0, data.length, index + data.length);

        mStateLog.captureHighest(writer);
        long highestIndex = writer.mHighestIndex;
        long commitIndex = writer.mCommitIndex;

        for (Channel peerChan : peerChannels) {
            peerChan.writeData(null, prevTerm, term, index, highestIndex, commitIndex, data);
        }

        return true;
    }

    // FIXME: testing
    public void readData(java.io.OutputStream out) throws IOException {
        long index = 0;
        byte[] buf = new byte[100];
        java.util.zip.Checksum checksum = org.cojen.tupl.io.CRC32C.newInstance();

        final int report = 1_000_000; // 10_000_000;
        int accum = 0;

        while (true) {
            LogReader reader = mStateLog.openReader(index, -1);

            while (true) {
                // FIXME: NullPointerException (just testing, so who cares?)
                int amt = reader.read(buf, 0, buf.length);
                if (amt < 0) {
                    index = reader.index();
                    reader.release();
                    break;
                }
                //System.out.write(buf, 0, amt);

                //out.write(buf, 0, amt);
                //out.flush();

                int rem = report - accum;
                if (rem >= amt) {
                    checksum.update(buf, 0, amt);
                    accum += amt;
                } else {
                    checksum.update(buf, 0, rem);
                    System.out.println("\t\t\t\tchecksum: " + checksum.getValue());
                    amt -= rem;
                    checksum.update(buf, rem, amt);
                    accum = amt;
                }
            }
        }
    }

    private void scheduleMissingDataTask() {
        int delayMillis = ThreadLocalRandom.current()
            .nextInt(MISSING_DELAY_LOW_MILLIS, MISSING_DELAY_HIGH_MILLIS);
        mScheduler.schedule(this::missingDataTask, delayMillis);
    }

    private void missingDataTask() {
        // FIXME: don't check if data is flowing in from queryDataReply

        if (tryAcquireShared()) {
            if (mLocalRole == ROLE_LEADER) {
                // Leader doesn't need to check for missing data.
                mMissingContigIndex = Long.MAX_VALUE; // reset to unknown
                mSkipMissingDataTask = true;
                releaseShared();
                return;
            }
            releaseShared();
        }

        class Collector implements IndexRange {
            long[] mRanges;
            int mSize;

            @Override
            public void range(long startIndex, long endIndex) {
                if (mRanges == null) {
                    mRanges = new long[16];
                } else if (mSize >= mRanges.length) {
                    mRanges = Arrays.copyOf(mRanges, mRanges.length << 1);
                }
                mRanges[mSize++] = startIndex;
                mRanges[mSize++] = endIndex;
            }
        };

        Collector collector = new Collector();
        mMissingContigIndex = mStateLog.checkForMissingData(mMissingContigIndex, collector);

        for (int i=0; i<collector.mSize; ) {
            long startIndex = collector.mRanges[i++];
            long endIndex = collector.mRanges[i++];
            requestMissingData(startIndex, endIndex);
        }

        scheduleMissingDataTask();
    }

    private void requestMissingData(long startIndex, long endIndex) {
        // FIXME: Need a way to abort outstanding requests.
        System.out.println("must call queryData! " + startIndex + ".." + endIndex);

        long remaining = endIndex - startIndex;

        // Distribute the request among the channels at random.

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        acquireShared();
        Channel[] channels = mPeerChannels;
        releaseShared();

        doRequestData: while (remaining > 0) {
            long amt = Math.min(remaining, MISSING_DATA_REQUEST_SIZE);

            int selected = rnd.nextInt(channels.length);
            int attempts = 0;

            while (true) {
                Channel channel = channels[selected];

                if (channel.queryData(this, startIndex, startIndex + amt)) {
                    break;
                }

                // Peer not available, so try another.

                if (++attempts >= channels.length) {
                    // Attempted all peers, and none are available.
                    break doRequestData;
                }

                selected++;
                if (selected >= channels.length) {
                    selected = 0;
                }
            }

            startIndex += amt;
            remaining -= amt;
        }
    }

    private void scheduleElectionTask() {
        int delayMillis = ThreadLocalRandom.current()
            .nextInt(ELECTION_DELAY_LOW_MILLIS, ELECTION_DELAY_HIGH_MILLIS);
        mScheduler.schedule(this::electionTask, delayMillis);
    }

    private void electionTask() {
        try {
            doElectionTask();
        } finally {
            scheduleElectionTask();
        }
    }

    private void doElectionTask() {
        Channel[] peerChannels;
        long term, candidateId;
        LogInfo info = new LogInfo();

        acquireExclusive();
        try {
            if (mLocalRole == ROLE_LEADER) {
                // FIXME: If commit index is lagging behind and not moving, switch to follower.
                // This isn't in the Raft algorithm, but it really should be.
                affirmLeadership();
                return;
            }

            if (mLocalRole == ROLE_CANDIDATE) {
                // Abort current election and start a new one.
                System.out.println("follower (1)");
                toFollower();
            }

            if (mElectionValidated >= 0) {
                // Current leader is still active, so don't start an election yet.
                mElectionValidated--;
                releaseExclusive();
                return;
            }

            // Convert to candidate.
            // FIXME: Don't permit rogue members to steal leadership if process is
            // suspended. Need to double check against local clock to detect this. Or perhaps
            // check with peers to see if leader is up.
            mLocalRole = ROLE_CANDIDATE;
            System.out.println("candidate! " + mElectionValidated);

            peerChannels = mPeerChannels;

            mStateLog.captureHighest(info);
            System.out.println("elect me: " + info);

            try {
                mCurrentTerm = term = mStateLog.incrementCurrentTerm(1);
            } catch (IOException e) {
                releaseExclusive();
                uncaught(e);
                return;
            }

            candidateId = mChanMan.getLocalMemberId();
            mVotedFor = candidateId;

            // Only need a majority of vote grants (already voted for self).
            mGrantsRemaining = (peerChannels.length + 1) / 2;
        } catch (Throwable e) {
            releaseExclusive();
            throw e;
        }

        if (mGrantsRemaining == 0) {
            // Only a group of one, so become leader immediately.
            toLeader(term, info.mHighestIndex);
        } else {
            releaseExclusive();

            System.out.println("vote for self with term: " + term);

            for (Channel peerChan : peerChannels) {
                peerChan.requestVote(null, term, candidateId, info.mTerm, info.mHighestIndex);
            }
        }
    }

    // Caller must acquire exclusive latch, which is released by this method.
    private void affirmLeadership() {
        Channel[] peerChannels = mPeerChannels;
        LogWriter writer = mLeaderWriter;
        releaseExclusive();

        if (writer == null) {
            // Not leader anymore.
            return;
        }

        long prevTerm = writer.prevTerm();
        long term = writer.term();
        long index = writer.index();

        mStateLog.captureHighest(writer);
        long highestIndex = writer.mHighestIndex;
        long commitIndex = writer.mCommitIndex;

        // FIXME: use a custom command
        // FIXME: ... or use standard client write method
        byte[] EMPTY_DATA = new byte[0];

        for (Channel peerChan : peerChannels) {
            peerChan.writeData(null, prevTerm, term, index, highestIndex, commitIndex, EMPTY_DATA);
        }
    }

    // Caller must hold exclusive latch.
    private void toFollower() {
        final int originalRole = mLocalRole;
        if (originalRole != ROLE_FOLLOWER) {
            mLocalRole = ROLE_FOLLOWER;
            mVotedFor = 0;
            mGrantsRemaining = 0;
            if (mLeaderWriter != null) {
                mLeaderWriter.release();
                mLeaderWriter = null;
            }
            if (originalRole == ROLE_LEADER && mSkipMissingDataTask) {
                mSkipMissingDataTask = false;
                scheduleMissingDataTask();
            }
        }
    }

    @Override
    public boolean nop(Channel from) {
        return true;
    }

    @Override
    public boolean requestVote(Channel from, long term, long candidateId,
                               long highestTerm, long highestIndex)
    {
        System.out.println("requestVote: " + from + ", " + term + ", " + candidateId + ", " +
                           highestTerm + ", " + highestIndex);

        long currentTerm;
        acquireExclusive();
        try {
            final long originalTerm = mCurrentTerm;

            try {
                mCurrentTerm = currentTerm = mStateLog.checkCurrentTerm(term);
            } catch (IOException e) {
                uncaught(e);
                return false;
            }

            System.out.println("term: " + term + ", original: " + originalTerm +
                               ", current: " + currentTerm);

            if (currentTerm > originalTerm) {
                System.out.println("convert to follower(2) " + term);
                toFollower();
            }

            if (currentTerm >= originalTerm
                && ((mVotedFor == 0 || mVotedFor == candidateId)
                    && !isBehind(highestTerm, highestIndex)))
            {
                mVotedFor = candidateId;
                System.out.println("mVotedFor: " + candidateId + ", role: " + mLocalRole
                                   + ", " + term + " >= " + currentTerm);
                // Set voteGranted result bit to true.
                currentTerm |= 1L << 63;
                mElectionValidated = 1;
            }
        } finally {
            releaseExclusive();
        }

        from.requestVoteReply(null, currentTerm);
        return true;
    }

    private boolean isBehind(long term, long index) {
        LogInfo info = new LogInfo();
        mStateLog.captureHighest(info);
        System.out.println("local term and highest: " + info.mTerm + ", " + info.mHighestIndex);
        return term < info.mTerm || (term == info.mTerm && index < info.mHighestIndex);
    }

    @Override
    public boolean requestVoteReply(Channel from, long term) {
        System.out.println("requestVoteReply: " + from + ", " + term);

        acquireExclusive();

        final long originalTerm = mCurrentTerm;

        if (term < 0 && (term &= ~(1L << 63)) == originalTerm) {
            // Vote granted.

            if (--mGrantsRemaining > 0 || mLocalRole != ROLE_CANDIDATE) {
                // Majority not received yet, or voting is over.
                releaseExclusive();
                return true;
            }

            if (mLocalRole == ROLE_CANDIDATE) {
                LogInfo info = new LogInfo();
                mStateLog.captureHighest(info);
                toLeader(term, info.mHighestIndex);
                return true;
            }
        } else {
            // Vote denied.

            try {
                mCurrentTerm = mStateLog.checkCurrentTerm(term);
            } catch (IOException e) {
                releaseExclusive();
                uncaught(e);
                return false;
            }

            if (mCurrentTerm > originalTerm) {
                System.out.println("greater term: " + mCurrentTerm + " > " + originalTerm);
                System.out.println("follower(3)");
            } else {
                // Remain candidate for now, waiting for more votes.
                releaseExclusive();
                return true;
            }
        }

        toFollower();
        releaseExclusive();
        return true;
    }

    // Caller must acquire exclusive latch, which is released by this method.
    private void toLeader(long term, long index) {
        try {
            mLeaderWriter = mStateLog.openWriter(0, term, index);
            mLocalRole = ROLE_LEADER;
            System.out.println("leader: " + mChanMan.getLocalMemberId()
                               + ", " + mCurrentTerm);
            for (Channel channel : mPeerChannels) {
                channel.peer().mMatchIndex = 0;
            }
        } catch (Throwable e) {
            releaseExclusive();
            uncaught(e);
        }

        affirmLeadership();
    }

    @Override
    public boolean queryTerms(Channel from, long startIndex, long endIndex) {
        mStateLog.queryTerms(startIndex, endIndex, (prevTerm, term, index) -> {
            from.queryTermsReply(null, prevTerm, term, index);
        });

        return true;
    }

    @Override
    public boolean queryTermsReply(Channel from, long prevTerm, long term, long startIndex) {
        try {
            mStateLog.defineTerm(prevTerm, term, startIndex);
        } catch (IOException e) {
            uncaught(e);
        }

        return true;
    }

    @Override
    public boolean queryData(Channel from, long startIndex, long endIndex) {
        if (endIndex <= startIndex) {
            return true;
        }

        try {
            LogReader reader = mStateLog.openReader(startIndex, 0);
            if (reader == null) {
                return true;
            }

            try {
                long remaining = endIndex - startIndex;
                byte[] buf = new byte[(int) Math.min(9000, remaining)];

                while (true) {
                    long prevTerm = reader.prevTerm();
                    long index = reader.index();
                    long term = reader.term();

                    int amt = reader.readAny(buf, 0, (int) Math.min(buf.length, remaining));

                    if (amt <= 0) {
                        if (amt < 0) {
                            // Move to next term.
                            reader.release();
                            reader = mStateLog.openReader(startIndex, 0);
                            if (reader != null) {
                                continue;
                            }
                        }
                        break;
                    }

                    // FIXME: stream it
                    byte[] data = new byte[amt];
                    System.arraycopy(buf, 0, data, 0, amt);

                    from.queryDataReply(null, prevTerm, term, index, data);

                    startIndex += amt;
                    remaining -= amt;

                    if (remaining <= 0) {
                        break;
                    }
                }
            } finally {
                reader.release();
            }
        } catch (IOException e) {
            uncaught(e);
        }

        return true;
    }

    @Override
    public boolean queryDataReply(Channel from, long prevTerm, long term,
                                  long index, byte[] data)
    {
        try {
            LogWriter writer = mStateLog.openWriter(prevTerm, term, index);
            if (writer != null) {
                try {
                    writer.write(data, 0, data.length, 0);
                } finally {
                    writer.release();
                }
            } else {
                System.out.println("no queryDataReply: " + from + ", " + prevTerm + ", " + term +
                                   ", " + index + ", " + data.length);
            }
        } catch (IOException e) {
            uncaught(e);
        }

        return true;
    }

    @Override
    public boolean writeData(Channel from, long prevTerm, long term, long index,
                             long highestIndex, long commitIndex, byte[] data)
    {
        checkTerm: {
            acquireShared();

            final long originalTerm = mCurrentTerm;

            if (term == originalTerm) {
                if (mElectionValidated > 0) {
                    releaseShared();
                    break checkTerm;
                } else if (tryUpgrade()) {
                    mElectionValidated = 1;
                    releaseExclusive();
                    break checkTerm;
                }
            }

            releaseShared();
            acquireExclusive();

            try {
                if (term == originalTerm) {
                    mElectionValidated = 1;
                } else {
                    try {
                        mCurrentTerm = mStateLog.checkCurrentTerm(term);
                    } catch (IOException e) {
                        uncaught(e);
                        return false;
                    }
                    if (mCurrentTerm > originalTerm) {
                        System.out.println("convert to follower(1)");
                        toFollower();
                    }
                }
            } finally {
                releaseExclusive();
            }
        }

        try {
            LogWriter writer = mStateLog.openWriter(prevTerm, term, index);

            if (writer == null) {
                // FIXME: stash the write for later
                // Cannot write because terms are missing, so request them.
                long now = System.currentTimeMillis();
                if (now >= mNextQueryTermTime) {
                    LogInfo info = new LogInfo();
                    mStateLog.captureHighest(info);
                    System.out.println(info + ", " + highestIndex);
                    if (highestIndex > info.mCommitIndex) {
                        System.out.println("call queryTerms: " + info + ", " + index);
                        System.out.println("was writeData: " + from + ", " + prevTerm + ", " +
                                           term + ", " +
                                           index + ", " + highestIndex + ", " + commitIndex +
                                           ", " + data.length);
                        from.queryTerms(null, info.mCommitIndex, index);
                    }
                    mNextQueryTermTime = now + QUERY_TERMS_RATE_MILLIS;
                }

                return true;
            }

            try {
                int amt = writer.write(data, 0, data.length, highestIndex);
                if (amt < data.length) {
                    System.out.println("unfinished write: " + amt + " < " + data.length);
                }
                mStateLog.commit(commitIndex);
                mStateLog.captureHighest(writer);
                long highestTerm = writer.mTerm;
                if (highestTerm < term) {
                    // If the highest term is lower than the leader's, don't bother reporting
                    // it. The leader will just reject the reply.
                    return true;
                }
                term = highestTerm;
                highestIndex = writer.mHighestIndex;
            } finally {
                writer.release();
            }
        } catch (IOException e) {
            uncaught(e);
        }

        // TODO: can skip reply if successful and highest didn't change
        from.writeDataReply(null, term, highestIndex);

        return true;
    }

    @Override
    public boolean writeDataReply(Channel from, long term, long highestIndex) {
        LogWriter writer;
        long commitIndex;

        acquireExclusive();
        try {
            writer = mLeaderWriter;
            if (mLocalRole != ROLE_LEADER || writer == null) {
                System.out.println("not leader, writeDataReply: " + from + ", " + term + ", "
                                   + highestIndex);
                return true;
            }

            final long originalTerm = mCurrentTerm;

            if (term != originalTerm) {
                try {
                    mCurrentTerm = mStateLog.checkCurrentTerm(term);
                } catch (IOException e) {
                    uncaught(e);
                    return false;
                }

                if (mCurrentTerm > originalTerm) {
                    System.out.println("convert to follower(5)");
                    toFollower();
                } else {
                    // Cannot commit on behalf of older terms.
                    System.out.println("cannot commit: " + originalTerm + " < " + term + ", "
                                       + from);
                }
                return true;
            }

            Peer peer = from.peer();
            long matchIndex = peer.mMatchIndex;

            if (highestIndex <= matchIndex) {
                return true;
            }

            // Updating and sorting the peers by match index is simple, but for large groups
            // (>10?), a tree data structure should be used instead.
            peer.mMatchIndex = highestIndex;
            Arrays.sort(mPeers);

            commitIndex = mPeers[mPeers.length >> 1].mMatchIndex;
        } finally {
            releaseExclusive();
        }

        mStateLog.commit(commitIndex);
        return true;
    }
}
