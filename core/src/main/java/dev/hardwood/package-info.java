/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

/// Core entry points for reading Parquet files.
///
/// [Hardwood] manages a shared thread pool for multi-file scenarios.
/// [InputFile] abstracts the storage backend (local files, S3, in-memory).
/// For single-file reading, use [dev.hardwood.reader.ParquetFileReader] directly.
package dev.hardwood;
