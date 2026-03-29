/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

/// Java Flight Recorder (JFR) events for diagnosing read performance.
///
/// Events are emitted during file opening, page decoding, row group filtering,
/// and batch prefetching. Enable them via JFR configuration or the
/// `jdk.jfr.consumer` API to monitor I/O patterns and identify bottlenecks
/// in production.
package dev.hardwood.jfr;
