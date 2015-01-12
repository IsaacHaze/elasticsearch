/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.engine;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Accountables;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.lucene.index.ElasticsearchDirectoryReader;
import org.elasticsearch.common.util.concurrent.ReleasableLock;
import org.elasticsearch.index.deletionpolicy.SnapshotIndexCommit;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * TODO: document me!
 */
public class ShadowEngine extends Engine {

    private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    private final ReleasableLock readLock = new ReleasableLock(rwl.readLock());
    private final ReleasableLock writeLock = new ReleasableLock(rwl.writeLock());
    private final Lock failReleasableLock = new ReentrantLock();

    private final RecoveryCounter onGoingRecoveries;
    private volatile boolean closedOrFailed = false;
    private volatile SearcherManager searcherManager;

    private DirectoryReader indexReader = null;

    public ShadowEngine(EngineConfig engineConfig) {
        super(engineConfig);
        store.incRef();
        SearcherFactory searcherFactory = new EngineSearcherFactory(engineConfig);
        this.onGoingRecoveries = new RecoveryCounter(store);
        try {
            openNewReader();
            this.searcherManager = new SearcherManager(this.indexReader, searcherFactory);
        } catch (IOException e) {
            logger.warn("failed to create new reader", e);
            store.decRef();
        }
    }

    private final void openNewReader() throws IOException {
        try (ReleasableLock _ = writeLock.acquire()) {
            if (indexReader == null) {
                Directory d = store.directory();
                indexReader = ElasticsearchDirectoryReader.wrap(DirectoryReader.open(d), shardId);
            } else {
                // TODO might not need this, the reader is never re-opened here
                DirectoryReader oldReader = indexReader;
                DirectoryReader newReader = DirectoryReader.openIfChanged(indexReader);
                if (newReader != null) {
                    indexReader = ElasticsearchDirectoryReader.wrap(newReader, shardId);
                    oldReader.close();
                }
            }
        }
    }

    @Override
    public void create(Create create) throws EngineException {
        // no-op
        logger.info("cowardly refusing to CREATE");
    }

    @Override
    public void index(Index index) throws EngineException {
        // no-op
        logger.info("cowardly refusing to INDEX");
    }

    @Override
    public void delete(Delete delete) throws EngineException {
        // no-op
        logger.info("cowardly refusing to DELETE");
    }

    @Override
    public void delete(DeleteByQuery delete) throws EngineException {
        // no-op
        logger.info("cowardly refusing to DELETE-BY-QUERY");
    }

    @Override
    public void flush() throws EngineException {
        flush(false, false);
    }

    @Override
    public void flush(boolean force, boolean waitIfOngoing) throws EngineException {
        logger.info("cowardly refusing to FLUSH");
    }

    @Override
    public void forceMerge(boolean flush, boolean waitForMerge) {
        forceMerge(flush, waitForMerge, 1, false, false);
    }

    @Override
    public void forceMerge(boolean flush, boolean waitForMerge, int maxNumSegments, boolean onlyExpungeDeletes, boolean upgrade) throws EngineException {
        logger.info("cowardly refusing to FORCE_MERGE, since the since the primary will do it");
    }

    @Override
    public GetResult get(Get get) throws EngineException {
        // There is no translog, so we can get it directly from the searcher
        return getFromSearcher(get);
    }

    protected Searcher newSearcher(String source, IndexSearcher searcher, SearcherManager manager) {
        return new EngineSearcher(source, searcher, manager, store, logger);
    }

    @Override
    public Searcher acquireSearcher(String source) throws EngineException {
        boolean success = false;
         /* Acquire order here is store -> manager since we need
          * to make sure that the store is not closed before
          * the searcher is acquired. */
        store.incRef();
        try {
            final SearcherManager manager = this.searcherManager; // can never be null
            assert manager != null : "SearcherManager is null";
            /* This might throw NPE but that's fine we will run ensureOpen()
            *  in the catch block and throw the right exception */
            final IndexSearcher searcher = manager.acquire();
            try {
                final Searcher retVal = newSearcher(source, searcher, manager);
                success = true;
                return retVal;
            } finally {
                if (!success) {
                    manager.release(searcher);
                }
            }
        } catch (EngineClosedException ex) {
            throw ex;
        } catch (Throwable ex) {
            ensureOpen(); // throw EngineCloseException here if we are already closed
            logger.error("failed to acquire searcher, source {}", ex, source);
            throw new EngineException(shardId, "failed to acquire searcher, source " + source, ex);
        } finally {
            if (!success) {  // release the ref in the case of an error...
                store.decRef();
            }
        }
    }

    @Override
    public SegmentsStats segmentsStats() {
        ensureOpen();
        try (final Searcher searcher = acquireSearcher("segments_stats")) {
            SegmentsStats stats = new SegmentsStats();
            for (LeafReaderContext reader : searcher.reader().leaves()) {
                // TODO refactor segmentReader into abstract or utility class?
                final SegmentReader segmentReader = InternalEngine.segmentReader(reader.reader());
                stats.add(1, segmentReader.ramBytesUsed());
                stats.addTermsMemoryInBytes(guardedRamBytesUsed(segmentReader.getPostingsReader()));
                stats.addStoredFieldsMemoryInBytes(guardedRamBytesUsed(segmentReader.getFieldsReader()));
                stats.addTermVectorsMemoryInBytes(guardedRamBytesUsed(segmentReader.getTermVectorsReader()));
                stats.addNormsMemoryInBytes(guardedRamBytesUsed(segmentReader.getNormsReader()));
                stats.addDocValuesMemoryInBytes(guardedRamBytesUsed(segmentReader.getDocValuesReader()));
            }
            // No version map for shadow engine
            stats.addVersionMapMemoryInBytes(0);
            // Since there is no IndexWriter, these are 0
            stats.addIndexWriterMemoryInBytes(0);
            stats.addIndexWriterMaxMemoryInBytes(0);
            return stats;
        }
    }

    @Override
    public List<Segment> segments(boolean verbose) {
        try (ReleasableLock _ = readLock.acquire()) {
            ensureOpen();
            Map<String, Segment> segments = new HashMap<>();

            // first, go over and compute the search ones...
            Searcher searcher = acquireSearcher("segments");
            try {
                for (LeafReaderContext reader : searcher.reader().leaves()) {
                    SegmentCommitInfo info = InternalEngine.segmentReader(reader.reader()).getSegmentInfo();
                    assert !segments.containsKey(info.info.name);
                    Segment segment = new Segment(info.info.name);
                    segment.search = true;
                    segment.docCount = reader.reader().numDocs();
                    segment.delDocCount = reader.reader().numDeletedDocs();
                    segment.version = info.info.getVersion();
                    segment.compound = info.info.getUseCompoundFile();
                    try {
                        segment.sizeInBytes = info.sizeInBytes();
                    } catch (IOException e) {
                        logger.trace("failed to get size for [{}]", e, info.info.name);
                    }
                    final SegmentReader segmentReader = InternalEngine.segmentReader(reader.reader());
                    segment.memoryInBytes = segmentReader.ramBytesUsed();
                    if (verbose) {
                        segment.ramTree = Accountables.namedAccountable("root", segmentReader);
                    }
                    // TODO: add more fine grained mem stats values to per segment info here
                    segments.put(info.info.name, segment);
                }
            } finally {
                searcher.close();
            }

            Segment[] segmentsArr = segments.values().toArray(new Segment[segments.values().size()]);
            Arrays.sort(segmentsArr, new Comparator<Segment>() {
                @Override
                public int compare(Segment o1, Segment o2) {
                    return (int) (o1.getGeneration() - o2.getGeneration());
                }
            });

            // fill in the merges flag
            // TODO uncomment me
//            Set<OnGoingMerge> onGoingMerges = mergeScheduler.onGoingMerges();
//            for (OnGoingMerge onGoingMerge : onGoingMerges) {
//                for (SegmentCommitInfo segmentInfoPerCommit : onGoingMerge.getMergedSegments()) {
//                    for (Segment segment : segmentsArr) {
//                        if (segment.getName().equals(segmentInfoPerCommit.info.name)) {
//                            segment.mergeId = onGoingMerge.getId();
//                            break;
//                        }
//                    }
//                }
//            }

            return Arrays.asList(segmentsArr);
        }
    }

    @Override
    public boolean refreshNeeded() {
        if (store.tryIncRef()) {
            /*
              we need to inc the store here since searcherManager.isSearcherCurrent()
              acquires a searcher internally and that might keep a file open on the
              store. this violates the assumption that all files are closed when
              the store is closed so we need to make sure we increment it here
             */
            try {
                // if a merge has finished, we should refresh
                return searcherManager.isSearcherCurrent() == false;
            } catch (IOException e) {
                logger.error("failed to access searcher manager", e);
                failEngine("failed to access searcher manager", e);
                throw new EngineException(shardId, "failed to access searcher manager", e);
            } finally {
                store.decRef();
            }
        }
        return false;
    }

    @Override
    public void refresh(String source) throws EngineException {
        // we obtain a read lock here, since we don't want a flush to happen while we are refreshing
        // since it flushes the index as well (though, in terms of concurrency, we are allowed to do it)
        try (ReleasableLock _ = readLock.acquire()) {
            ensureOpen();
            searcherManager.maybeRefreshBlocking();
        } catch (AlreadyClosedException e) {
            ensureOpen();
        } catch (EngineClosedException e) {
            throw e;
        } catch (Throwable t) {
            failEngine("refresh failed", t);
            throw new RefreshFailedEngineException(shardId, t);
        }
    }

    @Override
    public SnapshotIndexCommit snapshotIndex() throws EngineException {
        // we have to flush outside of the readlock otherwise we might have a problem upgrading
        // the to a write lock when we fail the engine in this operation
        flush(false, true);
        try (ReleasableLock _ = readLock.acquire()) {
            ensureOpen();
            return deletionPolicy.snapshot();
        } catch (IOException e) {
            throw new SnapshotFailedEngineException(shardId, e);
        }
    }

    // TODO refactor into abstract helper
    private boolean maybeFailEngine(Throwable t, String source) {
        if (Lucene.isCorruptionException(t)) {
            if (engineConfig.isFailEngineOnCorruption()) {
                failEngine("corrupt file detected source: [" + source + "]", t);
                return true;
            } else {
                logger.warn("corrupt file detected source: [{}] but [{}] is set to [{}]", t, source,
                        EngineConfig.INDEX_FAIL_ON_CORRUPTION_SETTING, engineConfig.isFailEngineOnCorruption());
            }
        } else if (ExceptionsHelper.isOOM(t)) {
            failEngine("out of memory", t);
            return true;
        }
        return false;
    }

    // TODO refactor into abstract helper
    private Throwable wrapIfClosed(Throwable t) {
        if (closedOrFailed) {
            if (t != failedEngine && failedEngine != null) {
                t.addSuppressed(failedEngine);
            }
            return new EngineClosedException(shardId, t);
        }
        return t;
    }

    @Override
    public void recover(RecoveryHandler recoveryHandler) throws EngineException {
        // take a write lock here so it won't happen while a flush is in progress
        // this means that next commits will not be allowed once the lock is released
        try (ReleasableLock _ = writeLock.acquire()) {
            if (closedOrFailed) {
                throw new EngineClosedException(shardId, failedEngine);
            }
            onGoingRecoveries.startRecovery();
        }

        SnapshotIndexCommit phase1Snapshot;
        try {
            phase1Snapshot = deletionPolicy.snapshot();
        } catch (Throwable e) {
            maybeFailEngine(e, "recovery");
            Releasables.closeWhileHandlingException(onGoingRecoveries);
            throw new RecoveryEngineException(shardId, 1, "Snapshot failed", e);
        }

        boolean success = false;
        try {
            recoveryHandler.phase1(phase1Snapshot);
            success = true;
        } catch (Throwable e) {
            maybeFailEngine(e, "recovery phase 1");
            Releasables.closeWhileHandlingException(onGoingRecoveries, phase1Snapshot);
            throw new RecoveryEngineException(shardId, 1, "Execution failed", wrapIfClosed(e));
        } finally {
            Releasables.close(success, onGoingRecoveries, writeLock, phase1Snapshot);
        }

        // Since operations cannot be replayed from a translog on a shadow
        // engine, there is no phase2 and phase3 of recovery
    }

    @Override
    public void failEngine(String reason, Throwable failure) {
        // Note, there is no IndexWriter, so nothing to rollback here
        assert failure != null;
        if (failReleasableLock.tryLock()) {
            try {
                try {
                    // we first mark the store as corrupted before we notify any listeners
                    // this must happen first otherwise we might try to reallocate so quickly
                    // on the same node that we don't see the corrupted marker file when
                    // the shard is initializing
                    if (Lucene.isCorruptionException(failure)) {
                        try {
                            store.markStoreCorrupted(ExceptionsHelper.unwrapCorruption(failure));
                        } catch (IOException e) {
                            logger.warn("Couldn't marks store corrupted", e);
                        }
                    }
                } finally {
                    if (failedEngine != null) {
                        logger.debug("tried to fail engine but engine is already failed. ignoring. [{}]", reason, failure);
                        return;
                    }
                    logger.warn("failed engine [{}]", failure, reason);
                    // we must set a failure exception, generate one if not supplied
                    failedEngine = failure;
                    failedEngineListener.onFailedEngine(shardId, reason, failure);
                }
            } catch (Throwable t) {
                // don't bubble up these exceptions up
                logger.warn("failEngine threw exception", t);
            } finally {
                closedOrFailed = true;
            }
        } else {
            logger.debug("tried to fail engine but could not acquire lock - engine should be failed by now [{}]", reason, failure);
        }
    }

    @Override
    public void close() throws IOException {
        logger.debug("shadow replica close now acquiring writeLock");
        try (ReleasableLock _ = writeLock.acquire()) {
            logger.debug("shadow replica close acquired writeLock");
            if (isClosed.compareAndSet(false, true)) {
                try {
                    logger.debug("shadow replica close searcher manager");
                    IOUtils.close(searcherManager);
                } catch (Throwable t) {
                    logger.warn("shadow replica failed to close searcher manager", t);
                } finally {
                    store.decRef();
                }
            }
        }
    }
}
