/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

/// Avro integration for materializing Parquet rows as Avro [org.apache.avro.generic.GenericRecord] instances.
///
/// [AvroReaders] provides factory methods for creating readers with optional
/// filter predicates and column projections. [AvroRowReader] wraps a
/// [dev.hardwood.reader.RowReader] and handles schema conversion and record
/// materialization.
package dev.hardwood.avro;
