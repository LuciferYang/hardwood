/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.reader;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.BitSet;
import java.util.UUID;

import dev.hardwood.internal.predicate.RecordFilterEvaluator;
import dev.hardwood.internal.predicate.ResolvedPredicate;
import dev.hardwood.internal.reader.BatchDataView;
import dev.hardwood.internal.reader.FlatColumnData;
import dev.hardwood.internal.util.StringToIntMap;
import dev.hardwood.jfr.RecordFilterEvent;
import dev.hardwood.row.PqDoubleList;
import dev.hardwood.row.PqIntList;
import dev.hardwood.row.PqList;
import dev.hardwood.row.PqLongList;
import dev.hardwood.row.PqMap;
import dev.hardwood.row.PqStruct;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.ProjectedSchema;

/// Base class for RowReader implementations providing iteration control and accessor methods.
/// Subclasses must implement [#initialize()], [#loadNextBatch()], and [#close()].
abstract class AbstractRowReader implements RowReader {

    private static final System.Logger LOG = System.getLogger(AbstractRowReader.class.getName());

    protected BatchDataView dataView;
    protected ResolvedPredicate filterPredicate;
    protected ProjectedSchema projectedSchemaRef;

    // Iteration state shared by all row readers
    protected int rowIndex = -1;
    protected int batchSize = 0;
    protected boolean exhausted = false;
    protected volatile boolean closed = false;
    protected boolean initialized = false;

    // Row limit: 0 = unlimited
    protected long maxRows;

    // Cached flat arrays for direct access (bypasses dataView virtual dispatch)
    private Object[] flatValueArrays;
    private BitSet[] flatNulls;
    private boolean flatFastPath;
    // Cached name-to-projected-index mapping for named fast path (built once)
    private StringToIntMap nameCache;

    // Iteration strategy: selected once during cacheFlatBatch(), dispatched on every
    // hasNext()/next() call. Avoids per-row branching on filter and maxRows state.
    private IterationStrategy strategy;
    private boolean recordFilterWarningEmitted;

    /// Computes a batch size that keeps all column arrays for one batch within the L2 cache.
    ///
    /// Each batch allocates one primitive array per projected column. The total memory for a
    /// batch is approximately `batchSize * sum(bytesPerColumn)`. This method sizes the batch
    /// so that total stays under the target (6 MB), clamped to [`16 384`, `524 288`]
    /// rows.
    ///
    /// For example, 3 projected DOUBLE columns (8 bytes each = 24 bytes/row) yields
    /// `6 MB / 24 = 262 144` rows per batch.
    static int computeOptimalBatchSize(ProjectedSchema projectedSchema) {
        // Initally target 6 MB (fits comfortably in L2 cache)
        long targetBytes = 6L * 1024 * 1024;
        int minBatch = 16384;
        int maxBatch = 524288;

        int bytesPerRow = 0;
        for (int i = 0; i < projectedSchema.getProjectedColumnCount(); i++) {
            bytesPerRow += columnByteWidth(projectedSchema.getProjectedColumn(i));
        }

        if (bytesPerRow == 0) {
            bytesPerRow = 8;
        }

        int batchSize = (int) (targetBytes / bytesPerRow);
        return Math.max(minBatch, Math.min(maxBatch, batchSize));
    }

    /// Returns the estimated byte width of a single value for the given column's physical type.
    /// Variable-length types use a 16-byte estimate (pointer + average payload).
    private static int columnByteWidth(ColumnSchema col) {
        return switch (col.type()) {
            case INT32, FLOAT -> 4;
            case INT64, DOUBLE -> 8;
            case BOOLEAN -> 1;
            case INT96 -> 12;
            case BYTE_ARRAY -> 16;
            case FIXED_LEN_BYTE_ARRAY -> col.typeLength() != null ? col.typeLength() : 16;
        };
    }

    /// Ensures the reader is initialized. Called by metadata methods that may be
    /// invoked before iteration starts.
    protected abstract void initialize();

    /// Loads the next batch of data.
    /// @return true if a batch was loaded, false if no more data
    protected abstract boolean loadNextBatch();

    /// Populates cached flat arrays from the current batch data for direct access.
    /// This eliminates virtual dispatch through BatchDataView for primitive accessors.
    /// On first call, also selects the [IterationStrategy] for the reader's lifetime.
    private void cacheFlatBatch() {
        FlatColumnData[] flatColumnData = dataView.getFlatColumnData();
        if (flatColumnData == null) {
            flatFastPath = false;
            if (strategy == null) {
                selectStrategy(false);
            }
            return;
        }
        flatFastPath = true;
        int columns = flatColumnData.length;
        if (flatValueArrays == null || flatValueArrays.length != columns) {
            flatValueArrays = new Object[columns];
            flatNulls = new BitSet[columns];
        }
        for (int i = 0; i < columns; i++) {
            flatNulls[i] = flatColumnData[i].nulls();
            flatValueArrays[i] = extractValueArray(flatColumnData[i]);
        }
        // Build name cache once for named fast path
        if (nameCache == null) {
            int fieldCount = dataView.getFieldCount();
            nameCache = new StringToIntMap(fieldCount);
            for (int i = 0; i < fieldCount; i++) {
                nameCache.put(dataView.getFieldName(i), i);
            }
        }

        // Select iteration strategy once (first batch only)
        if (strategy == null) {
            boolean recordFilterActive = filterPredicate != null && flatFastPath && nameCache != null;
            if (filterPredicate != null && !recordFilterActive && !recordFilterWarningEmitted) {
                recordFilterWarningEmitted = true;
                LOG.log(System.Logger.Level.WARNING,
                        "Record-level filtering is not active because the schema contains nested columns (structs, lists, or maps). " +
                        "Row-group and page-level filtering still apply, but non-matching rows within surviving pages will not be filtered out.");
            }
            selectStrategy(recordFilterActive);
        }
    }

    /// Selects the iteration strategy once, based on whether record-level filtering
    /// is active and whether a row limit is set.
    private void selectStrategy(boolean recordFilterActive) {
        if (recordFilterActive) {
            int[] columnMapping = buildColumnMapping();
            strategy = new FilteredIteration(columnMapping);
        }
        else {
            strategy = new PlainIteration();
        }
        if (maxRows > 0) {
            strategy = new LimitedIteration(strategy);
        }
    }

    private static Object extractValueArray(FlatColumnData flatColumnData) {
        return switch (flatColumnData) {
            case FlatColumnData.LongColumn lc -> lc.values();
            case FlatColumnData.DoubleColumn dc -> dc.values();
            case FlatColumnData.IntColumn ic -> ic.values();
            case FlatColumnData.FloatColumn fc -> fc.values();
            case FlatColumnData.BooleanColumn bc -> bc.values();
            case FlatColumnData.ByteArrayColumn bac -> bac.values();
        };
    }

    // ==================== Iteration Control ====================

    @Override
    public boolean hasNext() {
        if (closed || exhausted) {
            return false;
        }
        if (!initialized) {
            initialize();
            if (!exhausted) {
                cacheFlatBatch();
            }
            return !exhausted && strategy.hasNextInBatch();
        }
        if (strategy.hasNextInBatch()) {
            return true;
        }
        boolean loaded = loadNextBatch();
        if (loaded) {
            cacheFlatBatch();
        }
        return loaded && strategy.hasNextInBatch();
    }

    @Override
    public void next() {
        if (!initialized) {
            initialize();
            cacheFlatBatch();
        }
        strategy.advanceRow();
        dataView.setRowIndex(rowIndex);
    }

    // ==================== Iteration Strategies ====================

    /// Strategy for row iteration, selected once during [#cacheFlatBatch()].
    /// Eliminates per-row branching on filter and maxRows state.
    private interface IterationStrategy {
        boolean hasNextInBatch();
        void advanceRow();
    }

    /// Plain iteration: no filter, no row limit. The common case.
    /// Accesses `rowIndex` and `batchSize` from the enclosing AbstractRowReader.
    private final class PlainIteration implements IterationStrategy {
        @Override
        public boolean hasNextInBatch() {
            return rowIndex + 1 < batchSize;
        }

        @Override
        public void advanceRow() {
            rowIndex++;
        }
    }

    /// Filtered iteration: record-level predicate evaluation with batch matching.
    private final class FilteredIteration implements IterationStrategy {
        private final int[] columnMapping;
        private int pendingMatchRow = -1;
        private BitSet matchingRowsInBatch;
        private long totalRecords;
        private long recordsKept;

        FilteredIteration(int[] columnMapping) {
            this.columnMapping = columnMapping;
        }

        @Override
        public boolean hasNextInBatch() {
            return findNextMatch();
        }

        @Override
        public void advanceRow() {
            if (pendingMatchRow >= 0) {
                rowIndex = pendingMatchRow;
                pendingMatchRow = -1;
            }
            else {
                findNextMatch();
                rowIndex = pendingMatchRow;
                pendingMatchRow = -1;
            }
        }

        /// Scans forward from `rowIndex + 1` to find the next row matching the filter.
        /// Loads new batches as needed. Returns true if a match is found.
        private boolean findNextMatch() {
            while (true) {
                if (matchingRowsInBatch == null) {
                    matchingRowsInBatch = RecordFilterEvaluator.matchBatch(filterPredicate, batchSize,
                            flatValueArrays, flatNulls, columnMapping);
                    totalRecords += batchSize;
                    recordsKept += matchingRowsInBatch.cardinality();
                }

                int nextMatchingRow = matchingRowsInBatch.nextSetBit(rowIndex + 1);
                if (nextMatchingRow >= 0 && nextMatchingRow < batchSize) {
                    pendingMatchRow = nextMatchingRow;
                    return true;
                }

                matchingRowsInBatch = null;
                if (!loadNextBatch()) {
                    exhausted = true;
                    emitFilterEvent();
                    return false;
                }
                cacheFlatBatch();
                rowIndex = -1;
            }
        }

        private void emitFilterEvent() {
            if (totalRecords > 0) {
                RecordFilterEvent event = new RecordFilterEvent();
                event.totalRecords = totalRecords;
                event.recordsKept = recordsKept;
                event.recordsSkipped = totalRecords - recordsKept;
                event.commit();
            }
        }
    }

    /// Row-limited iteration: wraps another strategy and stops after maxRows.
    private final class LimitedIteration implements IterationStrategy {
        private final IterationStrategy delegate;
        private long emittedRows;

        LimitedIteration(IterationStrategy delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNextInBatch() {
            if (emittedRows >= maxRows) {
                exhausted = true;
                return false;
            }
            return delegate.hasNextInBatch();
        }

        @Override
        public void advanceRow() {
            delegate.advanceRow();
            emittedRows++;
        }
    }

    /// Builds a mapping from schema column index to projected array index.
    /// Uses [ProjectedSchema] to invert the projected -> original mapping.
    /// Entries for non-projected columns are set to -1.
    private int[] buildColumnMapping() {
        if (projectedSchemaRef == null) {
            return new int[0];
        }
        int projectedCount = projectedSchemaRef.getProjectedColumnCount();
        int maxOriginalIndex = 0;
        for (int i = 0; i < projectedCount; i++) {
            maxOriginalIndex = Math.max(maxOriginalIndex, projectedSchemaRef.toOriginalIndex(i));
        }
        int[] mapping = new int[maxOriginalIndex + 1];
        java.util.Arrays.fill(mapping, -1);
        for (int projectedIndex = 0; projectedIndex < projectedCount; projectedIndex++) {
            int originalIndex = projectedSchemaRef.toOriginalIndex(projectedIndex);
            mapping[originalIndex] = projectedIndex;
        }
        return mapping;
    }

    // ==================== Primitive Type Accessors ====================

    @Override
    public int getInt(String name) {
        if (flatFastPath) {
            int idx = nameCache.get(name);
            if (idx >= 0 && flatValueArrays[idx] instanceof int[]) {
                BitSet n = flatNulls[idx];
                if (n != null && n.get(rowIndex)) {
                    throw new NullPointerException("Column '" + name + "' is null at row " + rowIndex);
                }
                return ((int[]) flatValueArrays[idx])[rowIndex];
            }
        }
        return dataView.getInt(name);
    }

    @Override
    public int getInt(int columnIndex) {
        if (flatFastPath) {
            BitSet n = flatNulls[columnIndex];
            if (n != null && n.get(rowIndex)) {
                throw new NullPointerException("Column '" + dataView.getFieldName(columnIndex) + "' is null at row " + rowIndex);
            }
            return ((int[]) flatValueArrays[columnIndex])[rowIndex];
        }
        return dataView.getInt(columnIndex);
    }

    @Override
    public long getLong(String name) {
        if (flatFastPath) {
            int idx = nameCache.get(name);
            if (idx >= 0 && flatValueArrays[idx] instanceof long[]) {
                BitSet n = flatNulls[idx];
                if (n != null && n.get(rowIndex)) {
                    throw new NullPointerException("Column '" + name + "' is null at row " + rowIndex);
                }
                return ((long[]) flatValueArrays[idx])[rowIndex];
            }
        }
        return dataView.getLong(name);
    }

    @Override
    public long getLong(int columnIndex) {
        if (flatFastPath) {
            BitSet n = flatNulls[columnIndex];
            if (n != null && n.get(rowIndex)) {
                throw new NullPointerException("Column '" + dataView.getFieldName(columnIndex) + "' is null at row " + rowIndex);
            }
            return ((long[]) flatValueArrays[columnIndex])[rowIndex];
        }
        return dataView.getLong(columnIndex);
    }

    @Override
    public float getFloat(String name) {
        if (flatFastPath) {
            int idx = nameCache.get(name);
            if (idx >= 0 && flatValueArrays[idx] instanceof float[]) {
                BitSet n = flatNulls[idx];
                if (n != null && n.get(rowIndex)) {
                    throw new NullPointerException("Column '" + name + "' is null at row " + rowIndex);
                }
                return ((float[]) flatValueArrays[idx])[rowIndex];
            }
        }
        return dataView.getFloat(name);
    }

    @Override
    public float getFloat(int columnIndex) {
        if (flatFastPath) {
            BitSet n = flatNulls[columnIndex];
            if (n != null && n.get(rowIndex)) {
                throw new NullPointerException("Column '" + dataView.getFieldName(columnIndex) + "' is null at row " + rowIndex);
            }
            return ((float[]) flatValueArrays[columnIndex])[rowIndex];
        }
        return dataView.getFloat(columnIndex);
    }

    @Override
    public double getDouble(String name) {
        if (flatFastPath) {
            int idx = nameCache.get(name);
            if (idx >= 0 && flatValueArrays[idx] instanceof double[]) {
                BitSet n = flatNulls[idx];
                if (n != null && n.get(rowIndex)) {
                    throw new NullPointerException("Column '" + name + "' is null at row " + rowIndex);
                }
                return ((double[]) flatValueArrays[idx])[rowIndex];
            }
        }
        return dataView.getDouble(name);
    }

    @Override
    public double getDouble(int columnIndex) {
        if (flatFastPath) {
            BitSet n = flatNulls[columnIndex];
            if (n != null && n.get(rowIndex)) {
                throw new NullPointerException("Column '" + dataView.getFieldName(columnIndex) + "' is null at row " + rowIndex);
            }
            return ((double[]) flatValueArrays[columnIndex])[rowIndex];
        }
        return dataView.getDouble(columnIndex);
    }

    @Override
    public boolean getBoolean(String name) {
        if (flatFastPath) {
            int idx = nameCache.get(name);
            if (idx >= 0 && flatValueArrays[idx] instanceof boolean[]) {
                BitSet n = flatNulls[idx];
                if (n != null && n.get(rowIndex)) {
                    throw new NullPointerException("Column '" + name + "' is null at row " + rowIndex);
                }
                return ((boolean[]) flatValueArrays[idx])[rowIndex];
            }
        }
        return dataView.getBoolean(name);
    }

    @Override
    public boolean getBoolean(int columnIndex) {
        if (flatFastPath) {
            BitSet n = flatNulls[columnIndex];
            if (n != null && n.get(rowIndex)) {
                throw new NullPointerException("Column '" + dataView.getFieldName(columnIndex) + "' is null at row " + rowIndex);
            }
            return ((boolean[]) flatValueArrays[columnIndex])[rowIndex];
        }
        return dataView.getBoolean(columnIndex);
    }

    // ==================== Object Type Accessors ====================

    @Override
    public String getString(String name) {
        return dataView.getString(name);
    }

    @Override
    public String getString(int columnIndex) {
        return dataView.getString(columnIndex);
    }

    @Override
    public byte[] getBinary(String name) {
        return dataView.getBinary(name);
    }

    @Override
    public byte[] getBinary(int columnIndex) {
        return dataView.getBinary(columnIndex);
    }

    @Override
    public LocalDate getDate(String name) {
        return dataView.getDate(name);
    }

    @Override
    public LocalDate getDate(int columnIndex) {
        return dataView.getDate(columnIndex);
    }

    @Override
    public LocalTime getTime(String name) {
        return dataView.getTime(name);
    }

    @Override
    public LocalTime getTime(int columnIndex) {
        return dataView.getTime(columnIndex);
    }

    @Override
    public Instant getTimestamp(String name) {
        return dataView.getTimestamp(name);
    }

    @Override
    public Instant getTimestamp(int columnIndex) {
        return dataView.getTimestamp(columnIndex);
    }

    @Override
    public BigDecimal getDecimal(String name) {
        return dataView.getDecimal(name);
    }

    @Override
    public BigDecimal getDecimal(int columnIndex) {
        return dataView.getDecimal(columnIndex);
    }

    @Override
    public UUID getUuid(String name) {
        return dataView.getUuid(name);
    }

    @Override
    public UUID getUuid(int columnIndex) {
        return dataView.getUuid(columnIndex);
    }

    // ==================== Nested Type Accessors (by name) ====================

    @Override
    public PqStruct getStruct(String name) {
        return dataView.getStruct(name);
    }

    @Override
    public PqIntList getListOfInts(String name) {
        return dataView.getListOfInts(name);
    }

    @Override
    public PqLongList getListOfLongs(String name) {
        return dataView.getListOfLongs(name);
    }

    @Override
    public PqDoubleList getListOfDoubles(String name) {
        return dataView.getListOfDoubles(name);
    }

    @Override
    public PqList getList(String name) {
        return dataView.getList(name);
    }

    @Override
    public PqMap getMap(String name) {
        return dataView.getMap(name);
    }

    // ==================== Nested Type Accessors (by index) ====================

    @Override
    public PqStruct getStruct(int columnIndex) {
        return dataView.getStruct(columnIndex);
    }

    @Override
    public PqIntList getListOfInts(int columnIndex) {
        return dataView.getListOfInts(columnIndex);
    }

    @Override
    public PqLongList getListOfLongs(int columnIndex) {
        return dataView.getListOfLongs(columnIndex);
    }

    @Override
    public PqDoubleList getListOfDoubles(int columnIndex) {
        return dataView.getListOfDoubles(columnIndex);
    }

    @Override
    public PqList getList(int columnIndex) {
        return dataView.getList(columnIndex);
    }

    @Override
    public PqMap getMap(int columnIndex) {
        return dataView.getMap(columnIndex);
    }

    // ==================== Generic Fallback ====================

    @Override
    public Object getValue(String name) {
        return dataView.getValue(name);
    }

    @Override
    public Object getValue(int columnIndex) {
        return dataView.getValue(columnIndex);
    }

    // ==================== Metadata ====================

    @Override
    public boolean isNull(String name) {
        if (flatFastPath) {
            int idx = nameCache.get(name);
            if (idx >= 0) {
                BitSet n = flatNulls[idx];
                return n != null && n.get(rowIndex);
            }
        }
        return dataView.isNull(name);
    }

    @Override
    public boolean isNull(int columnIndex) {
        if (flatFastPath) {
            BitSet n = flatNulls[columnIndex];
            return n != null && n.get(rowIndex);
        }
        return dataView.isNull(columnIndex);
    }

    @Override
    public int getFieldCount() {
        initialize();
        return dataView.getFieldCount();
    }

    @Override
    public String getFieldName(int index) {
        initialize();
        return dataView.getFieldName(index);
    }
}
