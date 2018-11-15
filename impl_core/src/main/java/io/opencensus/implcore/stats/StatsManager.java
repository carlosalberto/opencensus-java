/*
 * Copyright 2017, OpenCensus Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opencensus.implcore.stats;

import static com.google.common.base.Preconditions.checkNotNull;

import io.opencensus.common.Clock;
import io.opencensus.implcore.internal.CurrentState;
import io.opencensus.implcore.internal.CurrentState.State;
import io.opencensus.implcore.internal.EventQueue;
import io.opencensus.metrics.export.Metric;
import io.opencensus.spi.stats.export.View;
import io.opencensus.spi.stats.export.ViewData;
import io.opencensus.tags.TagContext;
import java.util.Collection;
import java.util.Set;
import javax.annotation.Nullable;

/** Object that stores all views and stats. */
public final class StatsManager {
  private static final State DEFAULT_STATE = State.ENABLED;

  private static volatile StatsManager instance;

  private final EventQueue queue;

  // clock used throughout the stats implementation
  private final Clock clock;

  private final CurrentState currentState;
  private final MeasureToViewMap measureToViewMap = new MeasureToViewMap();

  /**
   * Returns a singleton of this class.
   *
   * @param queue the queue implementation.
   * @param clock the clock to use when recording stats.
   * @return a singleton of this class.
   */
  public static synchronized StatsManager getOrCreateInstance(EventQueue queue, Clock clock) {
    if (instance == null) {
      instance = new StatsManager(queue, clock, new CurrentState(DEFAULT_STATE));
    }
    return instance;
  }

  StatsManager(EventQueue queue, Clock clock) {
    this(queue, clock, new CurrentState(DEFAULT_STATE));
  }

  StatsManager(EventQueue queue, Clock clock, CurrentState currentState) {
    checkNotNull(queue, "EventQueue");
    checkNotNull(clock, "Clock");
    this.queue = queue;
    this.clock = clock;
    this.currentState = currentState;
  }

  void registerView(View view) {
    measureToViewMap.registerView(view, clock);
  }

  @Nullable
  ViewData getView(View.Name viewName) {
    return measureToViewMap.getView(viewName, clock, currentState.getInternal());
  }

  Set<View> getExportedViews() {
    return measureToViewMap.getExportedViews();
  }

  void record(TagContext tags, MeasureMapInternal measurementValues) {
    // TODO(songya): consider exposing No-op MeasureMap and use it when stats currentState is
    // DISABLED, so
    // that we don't need to create actual MeasureMapImpl.
    if (currentState.getInternal() == State.ENABLED) {
      queue.enqueue(new StatsEvent(this, tags, measurementValues));
    }
  }

  Collection<Metric> getMetrics() {
    return measureToViewMap.getMetrics(clock, currentState.getInternal());
  }

  void clearStats() {
    measureToViewMap.clearStats();
  }

  void resumeStatsCollection() {
    measureToViewMap.resumeStatsCollection(clock.now());
  }

  CurrentState getCurrentState() {
    return currentState;
  }

  // An EventQueue entry that records the stats from one call to StatsManager.record(...).
  private static final class StatsEvent implements EventQueue.Entry {
    private final TagContext tags;
    private final MeasureMapInternal stats;
    private final StatsManager statsManager;

    StatsEvent(StatsManager statsManager, TagContext tags, MeasureMapInternal stats) {
      this.statsManager = statsManager;
      this.tags = tags;
      this.stats = stats;
    }

    @Override
    public void process() {
      // Add Timestamp to value after it went through the DisruptorQueue.
      statsManager.measureToViewMap.record(tags, stats, statsManager.clock.now());
    }
  }
}
