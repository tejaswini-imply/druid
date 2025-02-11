/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.apache.druid.msq.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.druid.client.indexing.TaskPayloadResponse;
import org.apache.druid.client.indexing.TaskStatusResponse;
import org.apache.druid.error.DruidException;
import org.apache.druid.frame.Frame;
import org.apache.druid.frame.processor.FrameProcessors;
import org.apache.druid.indexer.TaskLocation;
import org.apache.druid.indexer.TaskState;
import org.apache.druid.indexer.TaskStatusPlus;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.msq.counters.ChannelCounters;
import org.apache.druid.msq.counters.CounterSnapshots;
import org.apache.druid.msq.counters.CounterSnapshotsTree;
import org.apache.druid.msq.counters.QueryCounterSnapshot;
import org.apache.druid.msq.counters.SegmentGenerationProgressCounter;
import org.apache.druid.msq.indexing.MSQControllerTask;
import org.apache.druid.msq.indexing.destination.DataSourceMSQDestination;
import org.apache.druid.msq.indexing.destination.DurableStorageMSQDestination;
import org.apache.druid.msq.indexing.destination.MSQDestination;
import org.apache.druid.msq.indexing.destination.TaskReportMSQDestination;
import org.apache.druid.msq.indexing.report.MSQStagesReport;
import org.apache.druid.msq.indexing.report.MSQTaskReportPayload;
import org.apache.druid.msq.kernel.StageDefinition;
import org.apache.druid.msq.sql.SqlStatementState;
import org.apache.druid.msq.sql.entity.ColumnNameAndTypes;
import org.apache.druid.msq.sql.entity.PageInformation;
import org.apache.druid.msq.sql.entity.SqlStatementResult;
import org.apache.druid.segment.ColumnSelectorFactory;
import org.apache.druid.segment.ColumnValueSelector;
import org.apache.druid.segment.Cursor;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.sql.calcite.planner.ColumnMappings;
import org.apache.druid.sql.calcite.run.SqlResults;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class SqlStatementResourceHelper
{
  public static Optional<List<ColumnNameAndTypes>> getSignature(
      MSQControllerTask msqControllerTask
  )
  {
    // only populate signature for select q's
    if (!MSQControllerTask.isIngestion(msqControllerTask.getQuerySpec())) {
      ColumnMappings columnMappings = msqControllerTask.getQuerySpec().getColumnMappings();
      List<SqlTypeName> sqlTypeNames = msqControllerTask.getSqlTypeNames();
      if (sqlTypeNames == null || sqlTypeNames.size() != columnMappings.size()) {
        return Optional.empty();
      }
      List<ColumnType> nativeTypeNames = msqControllerTask.getNativeTypeNames();
      if (nativeTypeNames == null || nativeTypeNames.size() != columnMappings.size()) {
        return Optional.empty();
      }
      List<ColumnNameAndTypes> signature = new ArrayList<>(columnMappings.size());
      int index = 0;
      for (String colName : columnMappings.getOutputColumnNames()) {
        signature.add(new ColumnNameAndTypes(
            colName,
            sqlTypeNames.get(index).getName(),
            nativeTypeNames.get(index).asTypeString()
        ));
        index++;
      }
      return Optional.of(signature);
    }
    return Optional.empty();
  }


  public static void isMSQPayload(TaskPayloadResponse taskPayloadResponse, String queryId) throws DruidException
  {
    if (taskPayloadResponse == null || taskPayloadResponse.getPayload() == null) {
      throw DruidException.forPersona(DruidException.Persona.USER)
                          .ofCategory(DruidException.Category.INVALID_INPUT)
                          .build(
                              "Query[%s] not found", queryId);
    }

    if (MSQControllerTask.class != taskPayloadResponse.getPayload().getClass()) {
      throw DruidException.forPersona(DruidException.Persona.USER)
                          .ofCategory(DruidException.Category.INVALID_INPUT)
                          .build(
                              "Query[%s] not found", queryId);
    }
  }

  public static SqlStatementState getSqlStatementState(TaskStatusPlus taskStatusPlus)
  {
    TaskState state = taskStatusPlus.getStatusCode();
    if (state == null) {
      return SqlStatementState.ACCEPTED;
    }

    switch (state) {
      case FAILED:
        return SqlStatementState.FAILED;
      case RUNNING:
        if (TaskLocation.unknown().equals(taskStatusPlus.getLocation())) {
          return SqlStatementState.ACCEPTED;
        } else {
          return SqlStatementState.RUNNING;
        }
      case SUCCESS:
        return SqlStatementState.SUCCESS;
      default:
        throw new ISE("Unrecognized state[%s] found.", state);
    }
  }

  @SuppressWarnings("unchecked")


  public static long getLastIndex(Long numberOfRows, long start)
  {
    final long last;
    if (numberOfRows == null) {
      last = Long.MAX_VALUE;
    } else {
      long finalIndex;
      try {
        finalIndex = Math.addExact(start, numberOfRows);
      }
      catch (ArithmeticException e) {
        finalIndex = Long.MAX_VALUE;
      }
      last = finalIndex;
    }
    return last;
  }

  /**
   * Populates pages list from the {@link CounterSnapshotsTree}.
   * <br>
   * The number of pages changes with respect to the destination
   * <ol>
   *   <li>{@link DataSourceMSQDestination} a single page is returned which adds all the counters of {@link SegmentGenerationProgressCounter.Snapshot}</li>
   *   <li>{@link TaskReportMSQDestination} a single page is returned which adds all the counters of {@link ChannelCounters}</li>
   *   <li>{@link DurableStorageMSQDestination} a page is returned for each worker which has generated output rows.
   *   If the worker generated 0 rows, we do no populated a page for it. {@link PageInformation#id} is equal to the worker number</li>
   * </ol>
   */
  public static Optional<List<PageInformation>> populatePageList(
      MSQTaskReportPayload msqTaskReportPayload,
      MSQDestination msqDestination
  )
  {
    if (msqTaskReportPayload.getStages() == null || msqTaskReportPayload.getCounters() == null) {
      return Optional.empty();
    }
    int finalStage = msqTaskReportPayload.getStages().getStages().size() - 1;
    Map<Integer, CounterSnapshots> workerCounters = msqTaskReportPayload.getCounters().snapshotForStage(finalStage);
    if (workerCounters == null) {
      return Optional.empty();
    }

    if (msqDestination instanceof DataSourceMSQDestination) {
      long rows = 0L;
      for (CounterSnapshots counterSnapshots : workerCounters.values()) {
        QueryCounterSnapshot queryCounterSnapshot = counterSnapshots.getMap()
                                                                    .getOrDefault("segmentGenerationProgress", null);
        if (queryCounterSnapshot != null && queryCounterSnapshot instanceof SegmentGenerationProgressCounter.Snapshot) {
          rows += ((SegmentGenerationProgressCounter.Snapshot) queryCounterSnapshot).getRowsPushed();
        }
      }
      if (rows != 0L) {
        return Optional.of(ImmutableList.of(new PageInformation(rows, null, 0)));
      } else {
        return Optional.empty();
      }
    } else if (msqDestination instanceof TaskReportMSQDestination) {
      long rows = 0L;
      long size = 0L;
      for (CounterSnapshots counterSnapshots : workerCounters.values()) {
        QueryCounterSnapshot queryCounterSnapshot = counterSnapshots.getMap().getOrDefault("output", null);
        if (queryCounterSnapshot != null && queryCounterSnapshot instanceof ChannelCounters.Snapshot) {
          rows += Arrays.stream(((ChannelCounters.Snapshot) queryCounterSnapshot).getRows()).sum();
          size += Arrays.stream(((ChannelCounters.Snapshot) queryCounterSnapshot).getBytes()).sum();
        }
      }
      if (rows != 0L) {
        return Optional.of(ImmutableList.of(new PageInformation(rows, size, 0)));
      } else {
        return Optional.empty();
      }

    } else if (msqDestination instanceof DurableStorageMSQDestination) {
      List<PageInformation> pageList = new ArrayList<>();
      for (Map.Entry<Integer, CounterSnapshots> counterSnapshots : workerCounters.entrySet()) {
        long rows = 0L;
        long size = 0L;
        QueryCounterSnapshot queryCounterSnapshot = counterSnapshots.getValue().getMap().getOrDefault("output", null);
        if (queryCounterSnapshot != null && queryCounterSnapshot instanceof ChannelCounters.Snapshot) {
          rows += Arrays.stream(((ChannelCounters.Snapshot) queryCounterSnapshot).getRows()).sum();
          size += Arrays.stream(((ChannelCounters.Snapshot) queryCounterSnapshot).getBytes()).sum();
        }
        // do not populate a page if the worker generated 0 rows.
        if (rows != 0L) {
          pageList.add(new PageInformation(rows, size, counterSnapshots.getKey()));
        }
      }
      Collections.sort(pageList, PageInformation.getIDComparator());
      return Optional.of(pageList);
    } else {
      return Optional.empty();
    }
  }

  public static Optional<SqlStatementResult> getExceptionPayload(
      String queryId,
      TaskStatusResponse taskResponse,
      TaskStatusPlus statusPlus,
      SqlStatementState sqlStatementState,
      Map<String, Object> msqPayload
  )
  {
    Map<String, Object> exceptionDetails = getQueryExceptionDetails(getPayload(msqPayload));
    Map<String, Object> exception = getMap(exceptionDetails, "error");
    if (exceptionDetails == null || exception == null) {
      return Optional.of(new SqlStatementResult(
          queryId,
          sqlStatementState,
          taskResponse.getStatus().getCreatedTime(),
          null,
          taskResponse.getStatus().getDuration(),
          null,
          DruidException.forPersona(DruidException.Persona.DEVELOPER)
                        .ofCategory(DruidException.Category.UNCATEGORIZED)
                        .build(taskResponse.getStatus().getErrorMsg()).toErrorResponse()
      ));
    }

    final String errorMessage = String.valueOf(exception.getOrDefault("errorMessage", statusPlus.getErrorMsg()));
    exception.remove("errorMessage");
    String errorCode = String.valueOf(exception.getOrDefault("errorCode", "unknown"));
    exception.remove("errorCode");
    Map<String, String> stringException = new HashMap<>();
    for (Map.Entry<String, Object> exceptionKeys : exception.entrySet()) {
      stringException.put(exceptionKeys.getKey(), String.valueOf(exceptionKeys.getValue()));
    }
    return Optional.of(new SqlStatementResult(
        queryId,
        sqlStatementState,
        taskResponse.getStatus().getCreatedTime(),
        null,
        taskResponse.getStatus().getDuration(),
        null,
        DruidException.fromFailure(new DruidException.Failure(errorCode)
        {
          @Override
          protected DruidException makeException(DruidException.DruidExceptionBuilder bob)
          {
            DruidException ex = bob.forPersona(DruidException.Persona.USER)
                                   .ofCategory(DruidException.Category.UNCATEGORIZED)
                                   .build(errorMessage);
            ex.withContext(stringException);
            return ex;
          }
        }).toErrorResponse()
    ));
  }

  public static Sequence<Object[]> getResultSequence(
      MSQControllerTask msqControllerTask,
      StageDefinition finalStage,
      Frame frame,
      ObjectMapper jsonMapper
  )
  {
    final Cursor cursor = FrameProcessors.makeCursor(frame, finalStage.getFrameReader());

    final ColumnSelectorFactory columnSelectorFactory = cursor.getColumnSelectorFactory();
    final ColumnMappings columnMappings = msqControllerTask.getQuerySpec().getColumnMappings();
    @SuppressWarnings("rawtypes")
    final List<ColumnValueSelector> selectors = columnMappings.getMappings()
                                                              .stream()
                                                              .map(mapping -> columnSelectorFactory.makeColumnValueSelector(
                                                                  mapping.getQueryColumn()))
                                                              .collect(Collectors.toList());

    final List<SqlTypeName> sqlTypeNames = msqControllerTask.getSqlTypeNames();
    Iterable<Object[]> retVal = () -> new Iterator<Object[]>()
    {
      @Override
      public boolean hasNext()
      {
        return !cursor.isDone();
      }

      @Override
      public Object[] next()
      {
        final Object[] row = new Object[columnMappings.size()];
        for (int i = 0; i < row.length; i++) {
          final Object value = selectors.get(i).getObject();
          if (sqlTypeNames == null || msqControllerTask.getSqlResultsContext() == null) {
            // SQL type unknown, or no SQL results context: pass-through as is.
            row[i] = value;
          } else {
            row[i] = SqlResults.coerce(
                jsonMapper,
                msqControllerTask.getSqlResultsContext(),
                value,
                sqlTypeNames.get(i),
                columnMappings.getOutputColumnName(i)
            );
          }
        }
        cursor.advance();
        return row;
      }
    };
    return Sequences.simple(retVal);
  }

  @Nullable
  public static MSQStagesReport.Stage getFinalStage(MSQTaskReportPayload msqTaskReportPayload)
  {
    if (msqTaskReportPayload == null || msqTaskReportPayload.getStages().getStages() == null) {
      return null;
    }
    int finalStageNumber = msqTaskReportPayload.getStages().getStages().size() - 1;

    for (MSQStagesReport.Stage stage : msqTaskReportPayload.getStages().getStages()) {
      if (stage.getStageNumber() == finalStageNumber) {
        return stage;
      }
    }
    return null;
  }
  public static Map<String, Object> getQueryExceptionDetails(Map<String, Object> payload)
  {
    return getMap(getMap(payload, "status"), "errorReport");
  }

  public static Map<String, Object> getMap(Map<String, Object> map, String key)
  {
    if (map == null) {
      return null;
    }
    return (Map<String, Object>) map.get(key);
  }

  public static Map<String, Object> getPayload(Map<String, Object> results)
  {
    Map<String, Object> msqReport = getMap(results, "multiStageQuery");
    Map<String, Object> payload = getMap(msqReport, "payload");
    return payload;
  }
}
