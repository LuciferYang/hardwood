/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

/// Value types for nested Parquet structures accessed through [dev.hardwood.reader.RowReader].
///
/// [PqStruct] provides typed access to struct fields. [PqList], [PqIntList],
/// [PqLongList], and [PqDoubleList] represent list columns with primitive
/// specializations to avoid boxing. [PqMap] represents map columns.
package dev.hardwood.row;
