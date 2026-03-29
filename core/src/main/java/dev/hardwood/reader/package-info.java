/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

/// Parquet file readers with row-oriented and column-oriented APIs.
///
/// [ParquetFileReader] opens a single file and provides access to metadata and
/// schema. From there, create a [RowReader] for row-at-a-time access with typed
/// getters, or a [ColumnReader] for batch-oriented columnar access with
/// primitive arrays. [FilterPredicate] enables predicate pushdown at both the
/// row-group and page level.
///
/// For reading multiple files as a single dataset, use [MultiFileParquetReader]
/// via [dev.hardwood.Hardwood].
package dev.hardwood.reader;
