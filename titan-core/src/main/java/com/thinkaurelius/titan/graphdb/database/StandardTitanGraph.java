package com.thinkaurelius.titan.graphdb.database;

import com.carrotsearch.hppc.LongArrayList;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.*;
import com.thinkaurelius.titan.core.*;
import com.thinkaurelius.titan.core.Cardinality;
import com.thinkaurelius.titan.core.schema.ConsistencyModifier;
import com.thinkaurelius.titan.core.Multiplicity;
import com.thinkaurelius.titan.core.schema.ModifierType;
import com.thinkaurelius.titan.core.schema.TitanManagement;
import com.thinkaurelius.titan.diskstorage.util.StaticArrayEntry;
import com.thinkaurelius.titan.diskstorage.util.time.Timepoint;
import com.thinkaurelius.titan.diskstorage.util.time.TimestampProvider;
import com.thinkaurelius.titan.diskstorage.*;
import com.thinkaurelius.titan.diskstorage.configuration.ModifiableConfiguration;
import com.thinkaurelius.titan.diskstorage.indexing.IndexEntry;
import com.thinkaurelius.titan.diskstorage.indexing.IndexTransaction;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.*;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.cache.KCVSCache;
import com.thinkaurelius.titan.diskstorage.log.Log;
import com.thinkaurelius.titan.diskstorage.util.BufferUtil;
import com.thinkaurelius.titan.diskstorage.util.RecordIterator;
import com.thinkaurelius.titan.graphdb.blueprints.TitanBlueprintsGraph;
import com.thinkaurelius.titan.graphdb.blueprints.TitanFeatures;
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;
import com.thinkaurelius.titan.graphdb.database.cache.SchemaCache;
import com.thinkaurelius.titan.graphdb.database.idassigner.VertexIDAssigner;
import com.thinkaurelius.titan.graphdb.database.idhandling.IDHandler;
import com.thinkaurelius.titan.graphdb.database.idhandling.VariableLong;
import com.thinkaurelius.titan.graphdb.database.log.TransactionLogHeader;
import com.thinkaurelius.titan.graphdb.database.log.LogTxStatus;
import com.thinkaurelius.titan.graphdb.database.management.ManagementLogger;
import com.thinkaurelius.titan.graphdb.database.management.ManagementSystem;
import com.thinkaurelius.titan.graphdb.database.serialize.DataOutput;
import com.thinkaurelius.titan.graphdb.database.serialize.Serializer;
import com.thinkaurelius.titan.graphdb.idmanagement.IDInspector;
import com.thinkaurelius.titan.graphdb.idmanagement.IDManager;
import com.thinkaurelius.titan.graphdb.internal.InternalRelation;
import com.thinkaurelius.titan.graphdb.internal.InternalRelationType;
import com.thinkaurelius.titan.graphdb.internal.InternalVertex;
import com.thinkaurelius.titan.graphdb.relations.EdgeDirection;
import com.thinkaurelius.titan.graphdb.transaction.StandardTitanTx;
import com.thinkaurelius.titan.graphdb.transaction.StandardTransactionBuilder;
import com.thinkaurelius.titan.graphdb.transaction.TransactionConfiguration;
import com.thinkaurelius.titan.graphdb.types.CompositeIndexType;
import com.thinkaurelius.titan.graphdb.types.MixedIndexType;
import com.thinkaurelius.titan.graphdb.types.SchemaStatus;
import com.thinkaurelius.titan.graphdb.types.system.BaseKey;
import com.thinkaurelius.titan.graphdb.types.system.BaseRelationType;
import com.thinkaurelius.titan.graphdb.types.vertices.TitanSchemaVertex;
import com.thinkaurelius.titan.graphdb.util.ExceptionFactory;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Features;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration.REGISTRATION_TIME;

public class StandardTitanGraph extends TitanBlueprintsGraph {

    private static final Logger log =
            LoggerFactory.getLogger(StandardTitanGraph.class);


    private final GraphDatabaseConfiguration config;
    private final Backend backend;
    private final IDManager idManager;
    private final VertexIDAssigner idAssigner;
    private final TimestampProvider times;

    //Serializers
    protected final IndexSerializer indexSerializer;
    protected final EdgeSerializer edgeSerializer;
    protected final Serializer serializer;

    //Caches
    public final SliceQuery vertexExistenceQuery;
    private final RelationQueryCache queryCache;
    private final SchemaCache schemaCache;

    //Log
    private final ManagementLogger mgmtLogger;

    //Shutdown hook
    private final ShutdownThread shutdownHook;

    private volatile boolean isOpen = true;
    private AtomicLong txCounter;

    private Set<StandardTitanTx> openTransactions;

    public StandardTitanGraph(GraphDatabaseConfiguration configuration) {
        this.config = configuration;
        this.backend = configuration.getBackend();

        this.idAssigner = config.getIDAssigner(backend);
        this.idManager = idAssigner.getIDManager();

        this.serializer = config.getSerializer();
        StoreFeatures storeFeatures = backend.getStoreFeatures();
        this.indexSerializer = new IndexSerializer(this.serializer, this.backend.getIndexInformation(),storeFeatures.isDistributed() && storeFeatures.isKeyOrdered());
        this.edgeSerializer = new EdgeSerializer(this.serializer);
        this.vertexExistenceQuery = edgeSerializer.getQuery(BaseKey.VertexExists, Direction.OUT, new EdgeSerializer.TypedInterval[0]).setLimit(1);
        this.queryCache = new RelationQueryCache(this.edgeSerializer);
        this.schemaCache = configuration.getTypeCache(typeCacheRetrieval);
        this.times = configuration.getTimestampProvider();

        isOpen = true;
        txCounter = new AtomicLong(0);
        openTransactions = Collections.newSetFromMap(new ConcurrentHashMap<StandardTitanTx, Boolean>(100,0.75f,1));

        //Register instance and ensure uniqueness
        String uniqueInstanceId = configuration.getUniqueGraphId();
        ModifiableConfiguration globalConfig = GraphDatabaseConfiguration.getGlobalSystemConfig(backend);
        if (globalConfig.has(REGISTRATION_TIME,uniqueInstanceId)) {
            throw new TitanException(String.format("A Titan graph with the same instance id [%s] is already open. Might required forced shutdown.",uniqueInstanceId));
        }
        globalConfig.set(REGISTRATION_TIME, config.getTimestampProvider().getTime(), uniqueInstanceId);

        Log mgmtLog = backend.getSystemMgmtLog();
        mgmtLogger = new ManagementLogger(this,mgmtLog,schemaCache,this.times);
        mgmtLog.registerReader(mgmtLogger);

        shutdownHook = new ShutdownThread(this);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public synchronized void shutdown() throws TitanException {
        if (!isOpen) return;
        try {
            //Unregister instance
            ModifiableConfiguration globalConfig = GraphDatabaseConfiguration.getGlobalSystemConfig(backend);
            globalConfig.remove(REGISTRATION_TIME,config.getUniqueGraphId());

            super.shutdown();
            idAssigner.close();
            backend.close();
            queryCache.close();

            // Remove shutdown hook to avoid reference retention
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (StorageException e) {
            throw new TitanException("Could not close storage backend", e);
        } finally {
            isOpen = false;
        }
    }

    // ################### Simple Getters #########################

    @Override
    public Features getFeatures() {
        return TitanFeatures.getFeatures(getConfiguration(), backend.getStoreFeatures());
    }

    public IndexSerializer getIndexSerializer() {
        return indexSerializer;
    }

    public Backend getBackend() {
        return backend;
    }

    public IDInspector getIDInspector() {
        return idManager.getIdInspector();
    }

    public IDManager getIDManager() {
        return idManager;
    }

    public EdgeSerializer getEdgeSerializer() {
        return edgeSerializer;
    }

    public Serializer getDataSerializer() {
        return serializer;
    }

    //TODO: premature optimization, re-evaluate later
//    public RelationQueryCache getQueryCache() {
//        return queryCache;
//    }

    public SchemaCache getSchemaCache() {
        return schemaCache;
    }

    public GraphDatabaseConfiguration getConfiguration() {
        return config;
    }

    @Override
    public TitanManagement getManagementSystem() {
        return new ManagementSystem(this,backend.getGlobalSystemConfig(),backend.getSystemMgmtLog(), mgmtLogger);
    }

    public Set<? extends TitanTransaction> getOpenTransactions() {
        return Sets.newHashSet(openTransactions);
    }

    // ################### TRANSACTIONS #########################

    @Override
    public TitanTransaction newTransaction() {
        return buildTransaction().start();
    }

    @Override
    public StandardTransactionBuilder buildTransaction() {
        return new StandardTransactionBuilder(getConfiguration(), this);
    }

    @Override
    public TitanTransaction newThreadBoundTransaction() {
        return buildTransaction().threadBound().start();
    }

    public StandardTitanTx newTransaction(final TransactionConfiguration configuration) {
        if (!isOpen) ExceptionFactory.graphShutdown();
        try {
            StandardTitanTx tx = new StandardTitanTx(this, configuration);
            tx.setBackendTransaction(openBackendTransaction(tx));
            openTransactions.add(tx);
            return tx;
        } catch (StorageException e) {
            throw new TitanException("Could not start new transaction", e);
        }
    }

    private BackendTransaction openBackendTransaction(StandardTitanTx tx) throws StorageException {
        IndexSerializer.IndexInfoRetriever retriever = indexSerializer.getIndexInfoRetriever(tx);
        return backend.beginTransaction(tx.getConfiguration(),retriever);
    }

    public void closeTransaction(StandardTitanTx tx) {
        openTransactions.remove(tx);
    }

    // ################### READ #########################

    private final SchemaCache.StoreRetrieval typeCacheRetrieval = new SchemaCache.StoreRetrieval() {

        @Override
        public Long retrieveSchemaByName(String typeName, StandardTitanTx tx) {
            TitanVertex v = Iterables.getOnlyElement(tx.getVertices(BaseKey.SchemaName, typeName),null);
            return v!=null?v.getID():null;
        }

        @Override
        public EntryList retrieveSchemaRelations(final long schemaId, final BaseRelationType type, final Direction dir, final StandardTitanTx tx) {
            SliceQuery query = queryCache.getQuery(type,dir);
            return edgeQuery(schemaId, query, tx.getTxHandle());
        }

    };

    public RecordIterator<Long> getVertexIDs(final BackendTransaction tx) {
        Preconditions.checkArgument(backend.getStoreFeatures().hasOrderedScan() ||
                backend.getStoreFeatures().hasUnorderedScan(),
                "The configured storage backend does not support global graph operations - use Faunus instead");

        final KeyIterator keyiter;
        if (backend.getStoreFeatures().hasUnorderedScan()) {
            keyiter = tx.edgeStoreKeys(vertexExistenceQuery);
        } else {
            keyiter = tx.edgeStoreKeys(new KeyRangeQuery(IDHandler.MIN_KEY, IDHandler.MAX_KEY, vertexExistenceQuery));
        }

        return new RecordIterator<Long>() {

            @Override
            public boolean hasNext() {
                return keyiter.hasNext();
            }

            @Override
            public Long next() {
                return idManager.getKeyID(keyiter.next());
            }

            @Override
            public void close() throws IOException {
                keyiter.close();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("Removal not supported");
            }
        };
    }

    public EntryList edgeQuery(long vid, SliceQuery query, BackendTransaction tx) {
        Preconditions.checkArgument(vid > 0);
        return tx.edgeStoreQuery(new KeySliceQuery(idManager.getKey(vid), query));
    }

    public List<EntryList> edgeMultiQuery(LongArrayList vids, SliceQuery query, BackendTransaction tx) {
        Preconditions.checkArgument(vids != null && !vids.isEmpty());
        List<StaticBuffer> vertexIds = new ArrayList<StaticBuffer>(vids.size());
        for (int i = 0; i < vids.size(); i++) {
            Preconditions.checkArgument(vids.get(i) > 0);
            vertexIds.add(idManager.getKey(vids.get(i)));
        }
        Map<StaticBuffer,EntryList> result = tx.edgeStoreMultiQuery(vertexIds, query);
        List<EntryList> resultList = new ArrayList<EntryList>(result.size());
        for (StaticBuffer v : vertexIds) resultList.add(result.get(v));
        return resultList;
    }


    // ################### WRITE #########################

    public void assignID(InternalRelation relation) {
        idAssigner.assignID(relation);
    }

    public void assignID(InternalVertex vertex, VertexLabel label) {
        idAssigner.assignID(vertex,label);
    }

    public static boolean acquireLock(InternalRelation relation, int pos, boolean acquireLocksConfig) {
        InternalRelationType type = (InternalRelationType)relation.getType();
        return acquireLocksConfig && type.getConsistencyModifier()== ConsistencyModifier.LOCK &&
                ( type.getMultiplicity().isUnique(EdgeDirection.fromPosition(pos))
                        || pos==0 && type.getMultiplicity()== Multiplicity.SIMPLE);
    }

    public static boolean acquireLock(CompositeIndexType index, boolean acquireLocksConfig) {
        return acquireLocksConfig && index.getConsistencyModifier()==ConsistencyModifier.LOCK
                && index.getCardinality()!= Cardinality.LIST;
    }

    public boolean prepareCommit(final Collection<InternalRelation> addedRelations,
                                     final Collection<InternalRelation> deletedRelations,
                                     final Predicate<InternalRelation> filter,
                                     final BackendTransaction mutator, final StandardTitanTx tx,
                                     final boolean acquireLocks) throws StorageException {


        ListMultimap<Long, InternalRelation> mutations = ArrayListMultimap.create();
        ListMultimap<InternalVertex, InternalRelation> mutatedProperties = ArrayListMultimap.create();
        List<IndexSerializer.IndexUpdate> indexUpdates = Lists.newArrayList();
        //1) Collect deleted edges and their index updates and acquire edge locks
        for (InternalRelation del : Iterables.filter(deletedRelations,filter)) {
            Preconditions.checkArgument(del.isRemoved());
            for (int pos = 0; pos < del.getLen(); pos++) {
                InternalVertex vertex = del.getVertex(pos);
                if (pos == 0 || !del.isLoop()) {
                    if (del.isProperty()) mutatedProperties.put(vertex,del);
                    mutations.put(vertex.getID(), del);
                }
                if (acquireLock(del,pos,acquireLocks)) {
                    Entry entry = edgeSerializer.writeRelation(del, pos, tx);
                    mutator.acquireEdgeLock(idManager.getKey(vertex.getID()), entry);
                }
            }
            indexUpdates.addAll(indexSerializer.getIndexUpdates(del));
        }

        //2) Collect added edges and their index updates and acquire edge locks
        for (InternalRelation add : Iterables.filter(addedRelations,filter)) {
            Preconditions.checkArgument(add.isNew());

            for (int pos = 0; pos < add.getLen(); pos++) {
                InternalVertex vertex = add.getVertex(pos);
                if (pos == 0 || !add.isLoop()) {
                    if (add.isProperty()) mutatedProperties.put(vertex,add);
                    mutations.put(vertex.getID(), add);
                }
                if (!vertex.isNew() && acquireLock(add,pos,acquireLocks)) {
                    Entry entry = edgeSerializer.writeRelation(add, pos, tx);
                    mutator.acquireEdgeLock(idManager.getKey(vertex.getID()), entry.getColumn());
                }
            }
            Collection<IndexSerializer.IndexUpdate> updates = indexSerializer.getIndexUpdates(add);
            Integer ttl = ((InternalRelationType)add.getType()).getTtl();
            if (null != ttl && ttl > 0) {
                for (IndexSerializer.IndexUpdate update : updates) {
                    if (update.isAddition() && update.isCompositeIndex()) {
                        ((StaticArrayEntry)update.getEntry()).setMetaData(EntryMetaData.TTL,ttl);
                    }
                }
            }
            indexUpdates.addAll(updates);
        }

        //3) Collect all index update for vertices
        for (InternalVertex v : mutatedProperties.keySet()) {
            indexUpdates.addAll(indexSerializer.getIndexUpdates(v,mutatedProperties.get(v)));
        }
        //4) Acquire index locks (deletions first)
        for (IndexSerializer.IndexUpdate update : indexUpdates) {
            if (!update.isCompositeIndex() || !update.isDeletion()) continue;
            CompositeIndexType iIndex = (CompositeIndexType) update.getIndex();
            if (acquireLock(iIndex,acquireLocks)) {
                mutator.acquireIndexLock((StaticBuffer)update.getKey(), (Entry)update.getEntry());
            }
        }
        for (IndexSerializer.IndexUpdate update : indexUpdates) {
            if (!update.isCompositeIndex() || !update.isAddition()) continue;
            CompositeIndexType iIndex = (CompositeIndexType) update.getIndex();
            if (acquireLock(iIndex,acquireLocks)) {
                mutator.acquireIndexLock((StaticBuffer)update.getKey(), ((Entry)update.getEntry()).getColumn());
            }
        }

        //5) Add relation mutations
        for (Long vertexid : mutations.keySet()) {
            Preconditions.checkArgument(vertexid > 0, "Vertex has no id: %s", vertexid);
            List<InternalRelation> edges = mutations.get(vertexid);
            List<Entry> additions = new ArrayList<Entry>(edges.size());
            List<Entry> deletions = new ArrayList<Entry>(Math.max(10, edges.size() / 10));
            for (InternalRelation edge : edges) {
                InternalRelationType baseType = (InternalRelationType) edge.getType();
                assert baseType.getBaseType()==null;

                Integer ttl = baseType.getTtl();

                for (InternalRelationType type : baseType.getRelationIndexes()) {
                    if (type.getStatus()== SchemaStatus.DISABLED) continue;
                    for (int pos = 0; pos < edge.getArity(); pos++) {
                        if (!type.isUnidirected(Direction.BOTH) && !type.isUnidirected(EdgeDirection.fromPosition(pos)))
                            continue; //Directionality is not covered
                        if (edge.getVertex(pos).getID()==vertexid) {
                            StaticArrayEntry entry = edgeSerializer.writeRelation(edge, type, pos, tx);

                            if (null != ttl && ttl > 0) {
                                entry.setMetaData(EntryMetaData.TTL, ttl);
                            }

                            if (edge.isRemoved()) {
                                deletions.add(entry);
                            } else {
                                Preconditions.checkArgument(edge.isNew());
                                additions.add(entry);
                            }
                        }
                    }
                }
            }

            StaticBuffer vertexKey = idManager.getKey(vertexid);
            mutator.mutateEdges(vertexKey, additions, deletions);
        }

        //6) Add index updates
        for (IndexSerializer.IndexUpdate indexUpdate : indexUpdates) {
            assert indexUpdate.isAddition() || indexUpdate.isDeletion();
            if (indexUpdate.isCompositeIndex()) {
                IndexSerializer.IndexUpdate<StaticBuffer,Entry> update = indexUpdate;
                if (update.isAddition())
                    mutator.mutateIndex(update.getKey(), Lists.newArrayList(update.getEntry()), KCVSCache.NO_DELETIONS);
                else
                    mutator.mutateIndex(update.getKey(), KeyColumnValueStore.NO_ADDITIONS, Lists.newArrayList(update.getEntry()));
            } else {
                IndexSerializer.IndexUpdate<String,IndexEntry> update = indexUpdate;
                IndexTransaction itx = mutator.getIndexTransaction(update.getIndex().getBackingIndexName());
                String indexStore = ((MixedIndexType)update.getIndex()).getStoreName();
                if (update.isAddition())
                    itx.add(indexStore,update.getKey(),update.getEntry().field,update.getEntry().value,update.getElement().isNew());
                else
                    itx.delete(indexStore,update.getKey(),update.getEntry().field,update.getEntry().value,update.getElement().isRemoved());
            }
        }
        return !mutations.isEmpty();
    }

    private static final Predicate<InternalRelation> SCHEMA_FILTER = new Predicate<InternalRelation>() {
        @Override
        public boolean apply(@Nullable InternalRelation internalRelation) {
            return internalRelation.getType() instanceof BaseRelationType && internalRelation.getVertex(0) instanceof TitanSchemaVertex;
        }
    };

    private static final Predicate<InternalRelation> NO_SCHEMA_FILTER = new Predicate<InternalRelation>() {
        @Override
        public boolean apply(@Nullable InternalRelation internalRelation) {
            return !SCHEMA_FILTER.apply(internalRelation);
        }
    };

    private static final Predicate<InternalRelation> NO_FILTER = Predicates.alwaysTrue();

    public void commit(final Collection<InternalRelation> addedRelations,
                     final Collection<InternalRelation> deletedRelations, final StandardTitanTx tx) {

        //1. Finalize transaction
        log.debug("Saving transaction. Added {}, removed {}", addedRelations.size(), deletedRelations.size());
        if (!tx.getConfiguration().hasCommitTime()) tx.getConfiguration().setCommitTime(times.getTime());
        final Timepoint txTimestamp = tx.getConfiguration().getCommitTime();
        final long transactionId = txCounter.incrementAndGet();

        //2. Assign TitanVertex IDs
        if (!tx.getConfiguration().hasAssignIDsImmediately())
            idAssigner.assignIDs(addedRelations);

        //3. Commit
        BackendTransaction mutator = tx.getTxHandle();
        final boolean acquireLocks = tx.getConfiguration().hasAcquireLocks();
        final boolean hasTxIsolation = backend.getStoreFeatures().hasTxIsolation();
        final boolean logTransaction = config.hasLogTransactions() && !tx.getConfiguration().hasEnabledBatchLoading();
        final Log txLog = logTransaction?backend.getSystemTxLog():null;
        final TransactionLogHeader txLogHeader = new TransactionLogHeader(transactionId,txTimestamp);

        //3.1 Commit schema elements and their associated relations
        try {
            boolean hasSchemaElements = !Iterables.isEmpty(Iterables.filter(deletedRelations,SCHEMA_FILTER))
                    || !Iterables.isEmpty(Iterables.filter(addedRelations,SCHEMA_FILTER));
            Preconditions.checkArgument(!hasSchemaElements || (!tx.getConfiguration().hasEnabledBatchLoading() && acquireLocks),
                    "Attempting to create schema elements in inconsistent state");

            if (hasSchemaElements && !hasTxIsolation) {
                /*
                 * On storage without transactional isolation, create separate
                 * backend transaction for schema aspects to make sure that
                 * those are persisted prior to and independently of other
                 * mutations in the tx. If the storage supports transactional
                 * isolation, then don't create a separate tx.
                 */
                final BackendTransaction schemaMutator = openBackendTransaction(tx);

                try {
                    //[FAILURE] If the preparation throws an exception abort directly - nothing persisted since batch-loading cannot be enabled for schema elements
                    prepareCommit(addedRelations,deletedRelations, SCHEMA_FILTER, schemaMutator, tx, acquireLocks);

                    if (logTransaction) {
                        //[FAILURE] If transaction logging fails immediately, abort - nothing persisted yet
                        logTransaction(txLog,schemaMutator,tx.getConfiguration(),txLogHeader,LogTxStatus.PREFLUSH_SYSTEM);
                    }
                } catch (Throwable e) {
                    //Roll back schema tx and escalate exception
                    schemaMutator.rollback();
                    throw e;
                }

                LogTxStatus status = LogTxStatus.SUCCESS_SYSTEM;

                try {
                    schemaMutator.commit();
                } catch (Throwable e) {
                    //[FAILURE] Primary persistence failed => abort but log failure (if possible)
                    status = LogTxStatus.FAILURE_SYSTEM;
                    log.error("Could not commit transaction ["+transactionId+"] due to storage exception in system-commit",e);
                    throw e;
                } finally {
                    if (logTransaction) {
                        DataOutput out = txLogHeader.serializeHeader(serializer,20,status);
                        //[FAILURE] An exception here will swallow any (very likely) previous exception in schemaMutator.commit()
                        //which then has to be looked up from the error log
                        txLog.add(out.getStaticBuffer(),txLogHeader.getLogKey());
                    }
                }
            }

            //[FAILURE] Exceptions during preparation here cause the entire transaction to fail on transactional systems
            //or just the non-system part on others. Nothing has been persisted unless batch-loading
            boolean hasModifications = prepareCommit(addedRelations,deletedRelations, hasTxIsolation? NO_FILTER : NO_SCHEMA_FILTER, mutator, tx, acquireLocks);
            if (hasModifications) {

                if (logTransaction) {
                    //[FAILURE] If transaction logging fails, abort the non-system part - nothing persisted unless batch loading
                    logTransaction(txLog,mutator,tx.getConfiguration(),txLogHeader,LogTxStatus.PRECOMMIT);
                }

                LogTxStatus status = LogTxStatus.SUCCESS;
                boolean storageSuccess = false;
                Map<String,Throwable> indexFailures = ImmutableMap.of();
                boolean triggerSuccess = false;
                try {
                    //1. Commit storage - failures lead to immediate abort
                    try {
                        mutator.commitStorage();
                        storageSuccess = true;
                    } catch (Throwable e) {
                        //[FAILURE] If primary storage persistence fails abort directly (partial persistence possible)
                        status = LogTxStatus.FAILURE;
                        log.error("Could not commit transaction ["+transactionId+"] due to storage exception in commit",e);
                        throw e;
                    }
                    //2. Commit indexes - [FAILURE] all exceptions are collected and logged but nothing is aborted
                    indexFailures = mutator.commitIndexes();
                    if (!indexFailures.isEmpty()) {
                        status = LogTxStatus.FAILURE;
                        for (Map.Entry<String,Throwable> entry : indexFailures.entrySet()) {
                            log.error("Error while commiting index mutations for transaction ["+transactionId+"] on index: " +entry.getKey(),entry.getValue());
                        }
                    }
                    //3. Log transaction if configured - [FAILURE] is recorded but does not cause exception
                    String logTxIdentifier = tx.getConfiguration().getLogIdentifier();
                    if (logTxIdentifier!=null) {
                        try {
                            final Log triggerLog = backend.getTriggerLog(logTxIdentifier);
                            DataOutput out = serializer.getDataOutput(20 + (addedRelations.size()+deletedRelations.size())*40);
                            out.putLong(txLogHeader.getTimestamp(times.getUnit()));
                            out.putLong(txLogHeader.getId());
                            logRelations(out, addedRelations, tx);
                            logRelations(out, deletedRelations,tx);
                            triggerLog.add(out.getStaticBuffer());
                            triggerSuccess=true;
                        } catch (Throwable e) {
                            log.error("Could not trigger-log committed transaction ["+transactionId+"] to " + logTxIdentifier, e);
                        }
                    }
                } finally {
                    if (logTransaction) {
                        DataOutput out = txLogHeader.serializeHeader(serializer,20,status);
                        if (status==LogTxStatus.FAILURE) {
                            out.putBoolean(storageSuccess);
                            out.putBoolean(triggerSuccess);
                            out.putInt(indexFailures.size());
                            for (String index : indexFailures.keySet()) {
                                assert StringUtils.isNotBlank(index);
                                out.writeObjectNotNull(index);
                            }
                        }
                        //[FAILURE] An exception here will swallow any (very likely) previous exception in mutator.flushStorage()
                        //which then has to be looked up from the error log
                        txLog.add(out.getStaticBuffer(),txLogHeader.getLogKey());
                    }
                }
            } else { //Just commit everything at once
                //[FAILURE] This case only happens when there are no non-system mutations in which case all changes
                //are already flushed. Hence, an exception here is unlikely and should abort
                mutator.commit();
            }
        } catch (Throwable e) {
            log.error("Could not commit transaction ["+transactionId+"] due to exception",e);
            try {
                //Clean up any left-over transaction handles
                mutator.rollback();
            } catch (Throwable e2) {
                log.error("Could not roll-back transaction ["+transactionId+"] after failure due to exception",e2);
            }
            if (e instanceof RuntimeException) throw (RuntimeException)e;
            else throw new TitanException("Unexpected exception",e);
        }
    }

    private void logTransaction(Log txLog, BackendTransaction mutator, TransactionConfiguration txConfig,
                                TransactionLogHeader txLogHeader, LogTxStatus status) {
        DataOutput out = txLogHeader.serializeHeader(serializer,256, status,txConfig);
        mutator.logMutations(out);
        txLog.add(out.getStaticBuffer(),txLogHeader.getLogKey());
    }

    private void logRelations(DataOutput out, final Collection<InternalRelation> relations, StandardTitanTx tx) {
        VariableLong.writePositive(out,relations.size());
        for (InternalRelation rel : relations) {
            VariableLong.writePositive(out,rel.getVertex(0).getID());
            Entry entry = edgeSerializer.writeRelation(rel, 0, tx);
            BufferUtil.writeEntry(out,entry);
        }
    }

    private static class ShutdownThread extends Thread {
        private final StandardTitanGraph graph;

        public ShutdownThread(StandardTitanGraph graph) {
            this.graph = graph;
        }

        @Override
        public void start() {
            if (graph.isOpen && log.isDebugEnabled())
                log.debug("Shutting down graph {} using built-in shutdown hook.", graph);

            graph.shutdown();
        }
    }
}
